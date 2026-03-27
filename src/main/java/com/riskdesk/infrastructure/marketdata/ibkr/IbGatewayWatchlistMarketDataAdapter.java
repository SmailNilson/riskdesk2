package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types.BarSize;
import com.ib.client.Types.DurationUnit;
import com.ib.client.Types.SecType;
import com.ib.client.Types.WhatToShow;
import com.ib.controller.Bar;
import com.riskdesk.domain.marketdata.port.WatchlistInstrumentMarketDataPort;
import com.riskdesk.domain.model.IbkrWatchlistInstrument;
import com.riskdesk.domain.model.WatchlistCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class IbGatewayWatchlistMarketDataAdapter implements WatchlistInstrumentMarketDataPort {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayWatchlistMarketDataAdapter.class);
    private static final DateTimeFormatter IB_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter IB_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
    private static final int MAX_BACKFILL_CHUNKS = 32;

    private final IbGatewayNativeClient nativeClient;

    public IbGatewayWatchlistMarketDataAdapter(IbGatewayNativeClient nativeClient) {
        this.nativeClient = nativeClient;
    }

    @Override
    public List<WatchlistCandle> fetchHistory(IbkrWatchlistInstrument instrument, String timeframe, int count) {
        Optional<Contract> contract = resolveContract(instrument);
        if (contract.isEmpty()) {
            log.warn("IB Gateway watchlist historical fetch skipped for {}: no contract resolved", instrument.getInstrumentCode());
            return List.of();
        }

        if (supportsDeepBackfill(timeframe)) {
            return fetchDeepHistory(instrument, timeframe, count, contract.get());
        }

        HistoricalQuery query = queryFor(timeframe, count);
        List<Bar> bars = nativeClient.requestHistoricalBars(
            contract.get(),
            query.duration(),
            query.durationUnit(),
            query.barSize(),
            whatToShowFor(instrument),
            false
        );

        return bars.stream()
            .map(bar -> toCandle(instrument, timeframe, bar))
            .sorted(Comparator.comparing(WatchlistCandle::getTimestamp))
            .toList();
    }

    @Override
    public Optional<BigDecimal> fetchLatestPrice(IbkrWatchlistInstrument instrument) {
        return resolveContract(instrument)
            .flatMap(nativeClient::requestSnapshotPrice);
    }

    private List<WatchlistCandle> fetchDeepHistory(IbkrWatchlistInstrument instrument, String timeframe, int targetCount, Contract contract) {
        HistoricalQuery chunkQuery = chunkQueryFor(timeframe);
        long stepSeconds = timeframeSeconds(timeframe);
        Instant endDateTime = null;
        Instant previousOldest = null;
        Map<Instant, WatchlistCandle> merged = new LinkedHashMap<>();

        for (int chunkIndex = 0; chunkIndex < MAX_BACKFILL_CHUNKS && merged.size() < targetCount; chunkIndex++) {
            List<Bar> bars = nativeClient.requestHistoricalBars(
                contract,
                endDateTime,
                chunkQuery.duration(),
                chunkQuery.durationUnit(),
                chunkQuery.barSize(),
                whatToShowFor(instrument),
                false
            );

            if (bars.isEmpty()) {
                break;
            }

            List<WatchlistCandle> candles = bars.stream()
                .map(bar -> toCandle(instrument, timeframe, bar))
                .sorted(Comparator.comparing(WatchlistCandle::getTimestamp))
                .toList();

            if (candles.isEmpty()) {
                break;
            }

            Instant oldest = candles.get(0).getTimestamp();
            for (WatchlistCandle candle : candles) {
                merged.putIfAbsent(candle.getTimestamp(), candle);
            }

            if (previousOldest != null && !oldest.isBefore(previousOldest)) {
                break;
            }
            previousOldest = oldest;
            endDateTime = oldest.minusSeconds(Math.max(stepSeconds, 1));
        }

        return merged.values().stream()
            .sorted(Comparator.comparing(WatchlistCandle::getTimestamp))
            .toList();
    }

    private Optional<Contract> resolveContract(IbkrWatchlistInstrument instrument) {
        Contract base = buildBaseContract(instrument);
        if (base == null) {
            return Optional.empty();
        }

        if (instrument.getConid() != null && instrument.getConid() > 0) {
            return Optional.of(base);
        }

        List<ContractDetails> details = nativeClient.requestContractDetails(base);
        if (!details.isEmpty()) {
            return Optional.of(details.get(0).contract());
        }
        return Optional.of(base);
    }

    private Contract buildBaseContract(IbkrWatchlistInstrument instrument) {
        if (instrument.getConid() == null && blank(instrument.getSymbol()) && blank(instrument.getLocalSymbol())) {
            return null;
        }

        Contract contract = new Contract();
        if (instrument.getConid() != null && instrument.getConid() > 0) {
            contract.conid(Math.toIntExact(instrument.getConid()));
        }

        SecType secType = secTypeFor(instrument.getAssetClass());
        if (secType != null) {
            contract.secType(secType);
        }

        if (!blank(instrument.getSymbol())) {
            contract.symbol(instrument.getSymbol().trim().toUpperCase(Locale.ROOT));
        }

        if (secType == SecType.STK) {
            contract.exchange("SMART");
        } else if (secType == SecType.CASH) {
            contract.exchange("IDEALPRO");
        }
        contract.includeExpired(false);
        return contract;
    }

    private SecType secTypeFor(String assetClass) {
        String normalized = assetClass == null ? "" : assetClass.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STK" -> SecType.STK;
            case "FUT" -> SecType.FUT;
            case "CASH" -> SecType.CASH;
            case "IND" -> SecType.IND;
            case "CFD" -> SecType.CFD;
            default -> null;
        };
    }

    private WhatToShow whatToShowFor(IbkrWatchlistInstrument instrument) {
        String assetClass = instrument.getAssetClass() == null ? "" : instrument.getAssetClass().trim().toUpperCase(Locale.ROOT);
        return "CASH".equals(assetClass) || "IND".equals(assetClass)
            ? WhatToShow.MIDPOINT
            : WhatToShow.TRADES;
    }

    private WatchlistCandle toCandle(IbkrWatchlistInstrument instrument, String timeframe, Bar bar) {
        long epochSeconds = parseBarTime(bar);
        long volume = bar.volume() != null && bar.volume().isValid() ? bar.volume().longValue() : 0L;

        return new WatchlistCandle(
            instrument.getInstrumentCode(),
            instrument.getConid(),
            timeframe,
            Instant.ofEpochSecond(epochSeconds),
            decimal(bar.open()),
            decimal(bar.high()),
            decimal(bar.low()),
            decimal(bar.close()),
            volume
        );
    }

    private long parseBarTime(Bar bar) {
        if (bar.time() != Long.MAX_VALUE) {
            return bar.time();
        }

        String raw = bar.timeStr();
        if (raw == null || raw.isBlank()) {
            return Instant.now().getEpochSecond();
        }

        String normalized = raw.trim().replaceAll("\\s+", " ");
        try {
            if (normalized.length() == 8) {
                return LocalDate.parse(normalized, IB_DATE)
                    .atStartOfDay()
                    .toEpochSecond(ZoneOffset.UTC);
            }

            String dateTime = normalized.length() >= 17 ? normalized.substring(0, 17) : normalized;
            return LocalDateTime.parse(dateTime, IB_DATE_TIME)
                .toEpochSecond(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            log.warn("Unable to parse IB historical bar time '{}', falling back to now.", raw);
            return Instant.now().getEpochSecond();
        }
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }

    private boolean supportsDeepBackfill(String timeframe) {
        return "10m".equals(timeframe) || "1h".equals(timeframe);
    }

    private HistoricalQuery chunkQueryFor(String timeframe) {
        return switch (timeframe) {
            case "10m" -> new HistoricalQuery(10, DurationUnit.DAY, BarSize._10_mins);
            case "1h" -> new HistoricalQuery(30, DurationUnit.DAY, BarSize._1_hour);
            default -> queryFor(timeframe, 500);
        };
    }

    private HistoricalQuery queryFor(String timeframe, int count) {
        return switch (timeframe) {
            case "5m" -> daysQuery(count, 5, BarSize._5_mins);
            case "10m" -> daysQuery(count, 10, BarSize._10_mins);
            case "1h" -> daysQuery(count, 60, BarSize._1_hour);
            case "4h" -> durationQuery(daysFor(count, 240), BarSize._4_hours);
            case "1d" -> durationQuery(daysFor(count, 1440), BarSize._1_day);
            default -> new HistoricalQuery(1, DurationUnit.DAY, BarSize._1_hour);
        };
    }

    private long timeframeSeconds(String timeframe) {
        return switch (timeframe) {
            case "5m" -> 5L * 60L;
            case "10m" -> 10L * 60L;
            case "1h" -> 60L * 60L;
            case "4h" -> 4L * 60L * 60L;
            case "1d" -> 24L * 60L * 60L;
            default -> 60L;
        };
    }

    private HistoricalQuery daysQuery(int count, int candleMinutes, BarSize barSize) {
        int days = daysFor(count, candleMinutes);
        return new HistoricalQuery(days, DurationUnit.DAY, barSize);
    }

    private int daysFor(int count, int candleMinutes) {
        double totalMinutes = Math.max(count, 1) * candleMinutes;
        return Math.max(2, (int) Math.ceil(totalMinutes / (60.0 * 24.0)) + 2);
    }

    private HistoricalQuery durationQuery(int daysEstimate, BarSize barSize) {
        int weeksEstimate = Math.max(1, (int) Math.ceil(daysEstimate / 7.0));
        if (weeksEstimate <= 52) {
            return new HistoricalQuery(weeksEstimate, DurationUnit.WEEK, barSize);
        }
        int years = Math.max(1, (int) Math.ceil(weeksEstimate / 52.0));
        return new HistoricalQuery(years, DurationUnit.YEAR, barSize);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record HistoricalQuery(int duration, DurationUnit durationUnit, BarSize barSize) {
    }
}

package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Types.BarSize;
import com.ib.client.Types.DurationUnit;
import com.ib.client.Types.WhatToShow;
import com.ib.controller.Bar;
import com.riskdesk.domain.marketdata.port.HistoricalDataProvider;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class IbGatewayHistoricalProvider implements HistoricalDataProvider {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayHistoricalProvider.class);
    private static final DateTimeFormatter IB_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter IB_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
    private static final int MAX_BACKFILL_CHUNKS = 32;

    private final IbGatewayNativeClient nativeClient;
    private final IbGatewayContractResolver contractResolver;

    public IbGatewayHistoricalProvider(IbGatewayNativeClient nativeClient, IbGatewayContractResolver contractResolver) {
        this.nativeClient = nativeClient;
        this.contractResolver = contractResolver;
    }

    @Override
    public List<Candle> fetchHistory(Instrument instrument, String timeframe, int count) {
        return contractResolver.resolve(instrument)
            .map(resolved -> {
                if (supportsDeepBackfill(timeframe)) {
                    return fetchDeepHistory(instrument, timeframe, count, resolved.contract());
                }

                HistoricalQuery query = queryFor(timeframe, count);
                List<Bar> bars = nativeClient.requestHistoricalBars(
                    resolved.contract(),
                    query.duration(),
                    query.durationUnit(),
                    query.barSize(),
                    WhatToShow.TRADES,
                    false
                );

                return bars.stream()
                    .map(bar -> toCandle(instrument, timeframe, bar))
                    .sorted(Comparator.comparing(Candle::getTimestamp))
                    .toList();
            })
            .orElseGet(() -> {
                log.warn("IB Gateway historical fetch skipped for {} {}: no contract resolved", instrument, timeframe);
                return List.of();
            });
    }

    @Override
    public boolean supports(Instrument instrument, String timeframe) {
        return switch (timeframe) {
            case "5m", "10m", "1h", "4h", "1d" -> true;
            default -> false;
        };
    }

    private List<Candle> fetchDeepHistory(Instrument instrument, String timeframe, int targetCount, com.ib.client.Contract contract) {
        HistoricalQuery chunkQuery = chunkQueryFor(timeframe);
        long stepSeconds = timeframeSeconds(timeframe);
        Instant endDateTime = null;
        Instant previousOldest = null;
        Map<Instant, Candle> merged = new LinkedHashMap<>();

        for (int chunkIndex = 0; chunkIndex < MAX_BACKFILL_CHUNKS && merged.size() < targetCount; chunkIndex++) {
            List<Bar> bars = nativeClient.requestHistoricalBars(
                contract,
                endDateTime,
                chunkQuery.duration(),
                chunkQuery.durationUnit(),
                chunkQuery.barSize(),
                WhatToShow.TRADES,
                false
            );

            if (bars.isEmpty()) {
                break;
            }

            List<Candle> candles = bars.stream()
                .map(bar -> toCandle(instrument, timeframe, bar))
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();

            if (candles.isEmpty()) {
                break;
            }

            Instant oldest = candles.get(0).getTimestamp();
            Instant newest = candles.get(candles.size() - 1).getTimestamp();

            for (Candle candle : candles) {
                merged.putIfAbsent(candle.getTimestamp(), candle);
            }

            log.debug("IB Gateway historical backfill {} {} chunk {}/{} -> {} candles ({} .. {}), total={}",
                instrument, timeframe, chunkIndex + 1, MAX_BACKFILL_CHUNKS, candles.size(), oldest, newest, merged.size());

            if (previousOldest != null && !oldest.isBefore(previousOldest)) {
                break;
            }
            previousOldest = oldest;
            endDateTime = oldest.minusSeconds(Math.max(stepSeconds, 1));
        }

        List<Candle> mergedCandles = merged.values().stream()
            .sorted(Comparator.comparing(Candle::getTimestamp))
            .toList();
        if (!mergedCandles.isEmpty()) {
            log.info("IB Gateway historical backfill {} {} completed with {} candles ({} .. {})",
                instrument,
                timeframe,
                mergedCandles.size(),
                mergedCandles.get(0).getTimestamp(),
                mergedCandles.get(mergedCandles.size() - 1).getTimestamp());
        }
        return mergedCandles;
    }

    private Candle toCandle(Instrument instrument, String timeframe, Bar bar) {
        long epochSeconds = parseBarTime(bar);
        long volume = bar.volume() != null && bar.volume().isValid() ? bar.volume().longValue() : 0L;

        return new Candle(
            instrument,
            timeframe,
            Instant.ofEpochSecond(epochSeconds),
            round(bar.open(), instrument),
            round(bar.high(), instrument),
            round(bar.low(), instrument),
            round(bar.close(), instrument),
            volume
        );
    }

    /**
     * Parses the bar timestamp from IBKR.  The primary path uses {@code bar.time()}
     * which returns epoch seconds (timezone-safe).  The string fallback ({@code bar.timeStr()})
     * is treated as UTC, which is correct when the TWS API Controller pre-parses the time.
     * <p>
     * <b>Caution:</b> if IBKR ever returns raw exchange-timezone strings in the fallback
     * path, they would be mis-interpreted as UTC (4-5 h offset for CME).  A warning is
     * logged whenever the fallback activates so this can be detected in production.
     */
    private long parseBarTime(Bar bar) {
        if (bar.time() != Long.MAX_VALUE) {
            return bar.time();
        }

        String raw = bar.timeStr();
        if (raw == null || raw.isBlank()) {
            return Instant.now().getEpochSecond();
        }

        log.warn("IBKR bar.time() unavailable, falling back to timeStr '{}' parsed as UTC. "
                + "Verify this is not exchange-local time.", raw);

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

    private BigDecimal round(double value, Instrument instrument) {
        return BigDecimal.valueOf(value).setScale(instrument.getTickSize().scale(), RoundingMode.HALF_UP);
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
        // Futures are nearly 24h instruments, so estimate against full calendar time
        // instead of an equities-style session. Add a small buffer for weekends/holidays.
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

    private record HistoricalQuery(int duration, DurationUnit durationUnit, BarSize barSize) {}
}

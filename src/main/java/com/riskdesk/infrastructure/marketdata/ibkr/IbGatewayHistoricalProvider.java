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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class IbGatewayHistoricalProvider implements HistoricalDataProvider {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayHistoricalProvider.class);
    private static final DateTimeFormatter IB_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter IB_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
    private static final int MAX_BACKFILL_CHUNKS = 32;
    /** Max expired contracts to walk backward through for deep backfill. */
    private static final int MAX_CONTRACT_WALK = 24;

    private final IbGatewayNativeClient nativeClient;
    private final IbGatewayContractResolver contractResolver;

    public IbGatewayHistoricalProvider(IbGatewayNativeClient nativeClient, IbGatewayContractResolver contractResolver) {
        this.nativeClient = nativeClient;
        this.contractResolver = contractResolver;
    }

    @Override
    public List<Candle> fetchHistory(Instrument instrument, String timeframe, int count) {
        if (!instrument.isExchangeTradedFuture()) {
            return List.of();
        }
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
                    .filter(c -> c.getTimestamp().getEpochSecond() > 0)
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
        if (!instrument.isExchangeTradedFuture()) {
            return false;
        }
        return switch (timeframe) {
            case "5m", "10m", "1h", "4h", "1d" -> true;
            default -> false;
        };
    }

    /**
     * Multi-contract deep backfill with volume-based rollover detection.
     *
     * Fetches data from the current (front-month) contract, then walks backward
     * through expired contracts. At each overlap zone, compares bar volume between
     * the two contracts to determine the actual rollover point — matching the
     * approach used by TradingView for continuous contracts.
     *
     * Each candle is tagged with its source contract month for proper tracking.
     * MCL/MGC roll monthly, E6/MNQ roll quarterly — the walk depth adapts accordingly.
     */
    private List<Candle> fetchDeepHistory(Instrument instrument, String timeframe, int targetCount, com.ib.client.Contract contract) {
        Map<Instant, Candle> merged = new LinkedHashMap<>();
        String currentMonth = normalizeMonth(contract.lastTradeDateOrContractMonth());
        int contractsUsed = 1;

        // Phase 1: fetch from current (front-month) contract
        fetchChunksFromContract(instrument, timeframe, targetCount, contract, currentMonth, merged);

        // Phase 2: walk backward through expired contracts with volume-based rollover
        int maxWalk = maxContractWalk(instrument, timeframe);
        String prevMonth = currentMonth;

        for (int walk = 0; walk < maxWalk && merged.size() < targetCount; walk++) {
            prevMonth = ActiveContractRegistryInitializer.previousContractMonth(instrument, prevMonth);
            if (prevMonth == null) break;

            Optional<IbGatewayResolvedContract> prevResolved = contractResolver.resolveExpiredMonth(instrument, prevMonth);
            if (prevResolved.isEmpty()) {
                log.debug("IB Gateway backfill: no expired contract for {} {}, stopping walk", instrument, prevMonth);
                break;
            }

            // Fetch older contract into a separate map for volume comparison
            Map<Instant, Candle> olderData = new LinkedHashMap<>();
            fetchChunksFromContract(instrument, timeframe, targetCount, prevResolved.get().contract(), prevMonth, olderData);

            if (olderData.isEmpty()) {
                log.debug("IB Gateway backfill: {} {} returned no data, stopping walk", instrument, prevMonth);
                break;
            }

            // Gap-fill only: older contract data extends the chart backward without
            // overwriting any candles from the current (newer) contract.
            int beforeSize = merged.size();
            mergeGapFillOnly(merged, olderData);

            log.info("IB Gateway backfill: {} {} — {} new candles gap-filled from contract {}",
                instrument, timeframe, merged.size() - beforeSize, prevMonth);

            if (merged.size() == beforeSize) break;
            contractsUsed++;
        }

        List<Candle> result = merged.values().stream()
            .sorted(Comparator.comparing(Candle::getTimestamp))
            .toList();
        if (!result.isEmpty()) {
            log.info("IB Gateway historical backfill {} {} completed with {} candles across {} contracts ({} .. {})",
                instrument, timeframe, result.size(), contractsUsed,
                result.get(0).getTimestamp(), result.get(result.size() - 1).getTimestamp());
        }
        return result;
    }

    /**
     * Merges older contract data into the main dataset using a gap-fill-only strategy.
     *
     * The newer (current) contract's candles are NEVER overwritten. Older contract
     * data only fills timestamps where the newer contract has no data — extending the
     * chart backward in time without contaminating the active contract's price series.
     *
     * Previous approach used volume-based rollover detection to decide which contract's
     * data to use at each timestamp. This caused Frankenstein charts when the volume
     * boundary was miscalculated, overwriting current-contract candles with stale prices
     * from the previous contract.
     */
    private void mergeGapFillOnly(Map<Instant, Candle> merged, Map<Instant, Candle> olderData) {
        int filled = 0;
        for (Map.Entry<Instant, Candle> entry : olderData.entrySet()) {
            if (!merged.containsKey(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
                filled++;
            }
        }
        log.debug("Deep backfill gap-fill: {} candles added from older contract (no overwrites)", filled);
    }

    /**
     * Fetches chunked historical data from a single contract, walking backward in time.
     * Candles are tagged with the source contractMonth and merged into the shared map.
     */
    private void fetchChunksFromContract(Instrument instrument, String timeframe, int targetCount,
                                         com.ib.client.Contract contract, String contractMonth,
                                         Map<Instant, Candle> merged) {
        HistoricalQuery chunkQuery = chunkQueryFor(timeframe);
        long stepSeconds = timeframeSeconds(timeframe);
        Instant endDateTime = null;
        Instant previousOldest = null;

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

            if (bars.isEmpty()) break;

            List<Candle> candles = bars.stream()
                .map(bar -> toCandleWithMonth(instrument, timeframe, bar, contractMonth))
                .filter(c -> c.getTimestamp().getEpochSecond() > 0)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();

            if (candles.isEmpty()) break;

            Instant oldest = candles.get(0).getTimestamp();
            Instant newest = candles.get(candles.size() - 1).getTimestamp();

            for (Candle candle : candles) {
                merged.putIfAbsent(candle.getTimestamp(), candle);
            }

            log.debug("IB Gateway backfill {} {} [{}] chunk {}/{} -> {} candles ({} .. {}), total={}",
                instrument, timeframe, contractMonth, chunkIndex + 1, MAX_BACKFILL_CHUNKS,
                candles.size(), oldest, newest, merged.size());

            if (previousOldest != null && !oldest.isBefore(previousOldest)) break;
            previousOldest = oldest;
            endDateTime = oldest.minusSeconds(Math.max(stepSeconds, 1));
        }
    }

    /**
     * Max number of expired contracts to walk backward.
     * Monthly instruments (MCL/MGC) need more contracts to cover the same time span.
     */
    private int maxContractWalk(Instrument instrument, String timeframe) {
        int baseDays = switch (timeframe) {
            case "5m"  -> 30;
            case "10m" -> 90;
            case "30m" -> 180;
            case "1h"  -> 365;
            case "4h"  -> 730;
            default -> 30;
        };
        boolean quarterly = instrument == Instrument.E6 || instrument == Instrument.MNQ;
        int monthsPerContract = quarterly ? 3 : 1;
        return Math.min(MAX_CONTRACT_WALK, (baseDays / (monthsPerContract * 30)) + 2);
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

    private Candle toCandleWithMonth(Instrument instrument, String timeframe, Bar bar, String contractMonth) {
        Candle candle = toCandle(instrument, timeframe, bar);
        candle.setContractMonth(contractMonth);
        return candle;
    }

    /**
     * Parses the bar timestamp from IBKR.  The primary path uses {@code bar.time()}
     * which returns epoch seconds (timezone-safe).
     * <p>
     * The string fallback ({@code bar.timeStr()}) may include an explicit timezone
     * suffix (e.g. "20260327 07:00:00 US/Central").  When present, that zone is used
     * for conversion.  Otherwise the default is {@code US/Central} (America/Chicago)
     * because CME instruments report bar times in exchange-local (Central) time.
     */
    private static final ZoneId IBKR_BAR_ZONE_DEFAULT = ZoneId.of("America/Chicago");

    private long parseBarTime(Bar bar) {
        if (bar.time() != Long.MAX_VALUE) {
            return bar.time();
        }

        String raw = bar.timeStr();
        if (raw == null || raw.isBlank()) {
            log.warn("IBKR bar has no time() and no timeStr() — skipping bar to avoid timestamp corruption");
            return 0L;
        }

        String normalized = raw.trim().replaceAll("\\s+", " ");
        try {
            if (normalized.length() == 8) {
                // Daily bar — date only: treat as exchange-local date
                return LocalDate.parse(normalized, IB_DATE)
                    .atStartOfDay(IBKR_BAR_ZONE_DEFAULT)
                    .toEpochSecond();
            }

            // Extract embedded timezone if present (e.g. "20260327 07:00:00 US/Central")
            ZoneId zone = IBKR_BAR_ZONE_DEFAULT;
            String dateTimePart;
            if (normalized.length() > 17) {
                dateTimePart = normalized.substring(0, 17);
                String tzSuffix = normalized.substring(17).trim();
                if (!tzSuffix.isEmpty()) {
                    try {
                        zone = ZoneId.of(tzSuffix);
                    } catch (Exception tzEx) {
                        log.warn("Unrecognized IBKR timezone '{}' in bar timeStr, using default {}", tzSuffix, IBKR_BAR_ZONE_DEFAULT);
                    }
                }
            } else {
                dateTimePart = normalized;
            }

            long epochSec = LocalDateTime.parse(dateTimePart, IB_DATE_TIME)
                .atZone(zone)
                .toEpochSecond();
            log.trace("IBKR bar.time() unavailable, parsed timeStr '{}' with zone {} → epoch {}", raw, zone, epochSec);
            return epochSec;
        } catch (DateTimeParseException ex) {
            log.warn("Unable to parse IB historical bar time '{}' — skipping bar to avoid timestamp corruption", raw);
            return 0L;
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
        return switch (timeframe) {
            case "5m", "10m", "30m", "1h", "4h" -> true;
            default -> false;
        };
    }

    private HistoricalQuery chunkQueryFor(String timeframe) {
        return switch (timeframe) {
            case "5m"  -> new HistoricalQuery(5, DurationUnit.DAY, BarSize._5_mins);
            case "10m" -> new HistoricalQuery(10, DurationUnit.DAY, BarSize._10_mins);
            case "30m" -> new HistoricalQuery(20, DurationUnit.DAY, BarSize._30_mins);
            case "1h"  -> new HistoricalQuery(30, DurationUnit.DAY, BarSize._1_hour);
            case "4h"  -> new HistoricalQuery(60, DurationUnit.DAY, BarSize._4_hours);
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

    private static String normalizeMonth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
    }

    private record HistoricalQuery(int duration, DurationUnit durationUnit, BarSize barSize) {}
}

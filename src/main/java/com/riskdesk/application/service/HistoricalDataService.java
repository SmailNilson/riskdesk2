package com.riskdesk.application.service;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.event.ContractRolloverEvent;
import com.riskdesk.domain.marketdata.port.HistoricalDataProvider;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages historical OHLCV candle data from IBKR.
 *
 * <p><b>Simplified design</b>: startup performs a single warm-up gap-fill for the default
 * instrument. All other data loading is on-demand via {@link OnDemandCandleService}.
 * Eager multi-instrument backfill and scheduled deep backfill have been removed to
 * reduce IBKR pacing violations and startup latency.</p>
 *
 * <p><b>Rollover</b>: on contract rollover, historical candles are preserved (never deleted).
 * New contract data accumulates naturally via on-demand loading.</p>
 *
 * Enabled via: riskdesk.market-data.historical.enabled=true
 */
@Service
@Order(2)
public class HistoricalDataService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataService.class);

    private static final List<String> TIMEFRAMES       = List.of("5m", "10m", "1h", "4h", "1d");
    private static final int          DEFAULT_CANDLES_PER_PAIR = 500;
    private static final int          GAP_FILL_BUFFER  = 100;

    private final HistoricalDataProvider historicalProvider;
    private final CandleRepositoryPort   candlePort;
    private final ActiveContractRegistry contractRegistry;

    @Value("${riskdesk.market-data.historical.enabled:false}")
    private boolean enabled;

    @Value("${riskdesk.market-data.historical.backfill-days-5m:30}")
    private int backfillDays5m;

    @Value("${riskdesk.market-data.historical.backfill-days-10m:90}")
    private int backfillDays10m;

    @Value("${riskdesk.market-data.historical.backfill-days-30m:180}")
    private int backfillDays30m;

    @Value("${riskdesk.market-data.historical.backfill-days-1h:365}")
    private int backfillDays1h;

    @Value("${riskdesk.market-data.historical.backfill-days-4h:730}")
    private int backfillDays4h;

    @Value("${riskdesk.market-data.historical.mentor-refresh-timeout-ms:2500}")
    private long mentorRefreshTimeoutMs;

    @Value("${riskdesk.market-data.historical.mentor-refresh-cooldown-ms:60000}")
    private long mentorRefreshCooldownMs;

    /** Set to true once real candles have been successfully loaded (quick startup). */
    private final AtomicBoolean realDataLoaded = new AtomicBoolean(false);
    private final Map<RefreshKey, Long> mentorRefreshTimestamps = new ConcurrentHashMap<>();

    private record RefreshKey(Instrument instrument, String timeframe) {}

    public HistoricalDataService(HistoricalDataProvider historicalProvider,
                                 CandleRepositoryPort candlePort,
                                 ActiveContractRegistry contractRegistry) {
        this.historicalProvider = historicalProvider;
        this.candlePort         = candlePort;
        this.contractRegistry   = contractRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Historical fetch disabled. Set riskdesk.market-data.historical.enabled=true to enable.");
            return;
        }

        // Startup gap-fill: fill candle gaps for ALL instruments × ALL timeframes
        // since last shutdown. Ensures indicators have fresh data immediately.
        // On-demand loading (OnDemandCandleService) handles scroll-back for deeper history.
        boolean hasExistingData = Instrument.exchangeTradedFutures().stream()
            .anyMatch(i -> candlePort.findLatestTimestamp(i, "5m").isPresent());

        if (hasExistingData) {
            realDataLoaded.set(true);
            log.info("HistoricalDataService: existing candles found — delta gap-fill in background.");
            CompletableFuture.runAsync(() -> gapFillAll("startup-delta"))
                .exceptionally(ex -> { log.error("Startup delta gap-fill failed", ex); return null; });
        } else {
            log.info("HistoricalDataService: no existing candles — full gap-fill in background.");
            CompletableFuture.runAsync(() -> gapFillAll("startup-fresh"))
                .exceptionally(ex -> { log.error("Startup fresh gap-fill failed", ex); return null; });
        }
    }

    public Map<String, Integer> refreshInstrumentContext(Instrument instrument, List<String> requestedTimeframes) {
        if (!enabled) {
            return Collections.emptyMap();
        }

        Map<String, Integer> savedByTimeframe = new LinkedHashMap<>();
        for (String timeframe : requestedTimeframes) {
            if (timeframe == null || timeframe.isBlank() || savedByTimeframe.containsKey(timeframe)) {
                continue;
            }

            int saved = refreshSingleInstrumentTimeframeBounded(instrument, timeframe, "mentor");
            savedByTimeframe.put(timeframe, saved);
        }
        return savedByTimeframe;
    }

    /**
     * On contract rollover, preserve all existing candles. New contract data
     * accumulates naturally via on-demand loading.
     *
     * <p>Warm-up gap-fill covers ALL intraday timeframes so charts and indicators
     * have continuous history on the new contract immediately. Previously only
     * 10m was warmed, which left 5m / 1h / 4h charts starved until the next
     * mentor refresh or manual poll — the exact failure mode observed on MCL
     * after 2026-04-10.</p>
     */
    @EventListener
    public void onContractRollover(ContractRolloverEvent event) {
        Instrument instrument = event.instrument();
        log.info("Rollover for {} -> {}. Historical candles preserved; warming all intraday timeframes.",
                instrument, event.newContractMonth());
        CompletableFuture.runAsync(() -> {
            for (String timeframe : TIMEFRAMES) {
                if (!historicalProvider.supports(instrument, timeframe)) continue;
                try {
                    int saved = gapFillTimeframe(instrument, timeframe, "rollover-warmup");
                    if (saved > 0) {
                        log.info("Rollover warm-up: {} {} gap-filled {} new candles.",
                            instrument, timeframe, saved);
                    }
                } catch (Exception e) {
                    log.warn("Rollover warm-up failed for {} {}: {}", instrument, timeframe, e.getMessage());
                }
            }
        }).exceptionally(ex -> {
            log.warn("Rollover warm-up failed: {}", ex.getMessage());
            return null;
        });
    }

    /** Trigger a manual full refresh asynchronously. Returns immediately. */
    public Map<String, Object> refreshAll() {
        if (!enabled) {
            return Map.of("status", "disabled", "message", "Historical data fetch is disabled.");
        }
        CompletableFuture.runAsync(() -> gapFillAll("manual"))
            .exceptionally(ex -> {
                log.error("Async historical data refresh failed", ex);
                return null;
            });
        return Map.of("status", "ok", "message", "Database refresh started in background.");
    }

    // -------------------------------------------------------------------------
    // Gap-fill (incremental delta fetch)
    // -------------------------------------------------------------------------

    private void gapFillAll(String context) {
        log.info("HistoricalDataService [{}]: gap-fill -- fetching delta candles (parallel by instrument)...", context);
        AtomicInteger totalSaved = new AtomicInteger(0);
        AtomicInteger totalFailed = new AtomicInteger(0);
        List<Instrument> instruments = Instrument.exchangeTradedFutures();

        ExecutorService pool = newBackfillPool(instruments.size());
        List<CompletableFuture<Void>> futures = instruments.stream()
            .map(instrument -> CompletableFuture.runAsync(() ->
                gapFillInstrument(instrument, context, totalSaved, totalFailed), pool))
            .toList();

        awaitAll(futures, pool, context);

        if (totalSaved.get() > 0) {
            log.info("HistoricalDataService [{}]: gap-fill done -- {} new candles saved.", context, totalSaved.get());
        } else if (totalFailed.get() == 0) {
            log.info("HistoricalDataService [{}]: gap-fill done -- candles already up to date.", context);
        } else {
            log.warn("HistoricalDataService [{}]: gap-fill failed for {} timeframes.", context, totalFailed.get());
            return;
        }
        realDataLoaded.set(true);
    }

    private void gapFillInstrument(Instrument instrument, String context,
                                   AtomicInteger totalSaved, AtomicInteger totalFailed) {
        for (String timeframe : TIMEFRAMES) {
            if (!historicalProvider.supports(instrument, timeframe)) continue;
            try {
                int saved = gapFillTimeframe(instrument, timeframe, context);
                totalSaved.addAndGet(saved);
            } catch (Exception e) {
                totalFailed.incrementAndGet();
                log.debug("HistoricalDataService [{}]: {} {} gap-fill failed -- {}", context, instrument, timeframe, e.getMessage());
            }
        }
    }

    /**
     * Core gap-fill for a single instrument/timeframe pair.
     * Finds the high-water mark in DB, fetches only the delta from IBKR, appends new candles.
     */
    private int gapFillTimeframe(Instrument instrument, String timeframe, String context) {
        Optional<Instant> hwm = candlePort.findLatestTimestamp(instrument, timeframe);
        int limit;

        if (hwm.isPresent()) {
            long secondsSince = Duration.between(hwm.get(), Instant.now()).getSeconds();
            long candleSeconds = timeframeSeconds(timeframe);
            limit = (int) (secondsSince / candleSeconds) + GAP_FILL_BUFFER;
            limit = Math.max(limit, GAP_FILL_BUFFER);
        } else {
            limit = candlesTargetFor(timeframe);
        }

        List<Candle> candles = historicalProvider.fetchHistory(instrument, timeframe, limit);
        candles = tagWithContractMonth(candles, instrument);
        candles = deduplicate(candles);

        if (hwm.isPresent()) {
            Instant cutoff = hwm.get();
            candles = candles.stream()
                .filter(c -> c.getTimestamp().isAfter(cutoff))
                .toList();
        }

        if (candles.isEmpty()) {
            log.debug("HistoricalDataService [{}]: {} {} already up to date.", context, instrument, timeframe);
            return 0;
        }

        candlePort.saveAll(candles);
        log.debug("HistoricalDataService [{}]: {} {} gap-filled {} new candles.", context, instrument, timeframe, candles.size());
        return candles.size();
    }

    // -------------------------------------------------------------------------
    // Mentor refresh (bounded, gap-fill)
    // -------------------------------------------------------------------------

    private int refreshSingleInstrumentTimeframe(Instrument instrument, String timeframe, String context) {
        if (!instrument.isExchangeTradedFuture()) {
            return 0;
        }
        if (!historicalProvider.supports(instrument, timeframe)) {
            return 0;
        }
        return gapFillTimeframe(instrument, timeframe, context);
    }

    private int refreshSingleInstrumentTimeframeBounded(Instrument instrument, String timeframe, String context) {
        RefreshKey refreshKey = new RefreshKey(instrument, timeframe);
        long now = System.currentTimeMillis();
        Long lastRefreshAt = mentorRefreshTimestamps.get(refreshKey);
        if (lastRefreshAt != null && now - lastRefreshAt < mentorRefreshCooldownMs) {
            log.debug("HistoricalDataService [{}]: skipping {} {} refresh during cooldown window.", context, instrument, timeframe);
            return 0;
        }

        mentorRefreshTimestamps.put(refreshKey, now);

        try {
            return CompletableFuture
                .supplyAsync(() -> refreshSingleInstrumentTimeframe(instrument, timeframe, context))
                .completeOnTimeout(0, mentorRefreshTimeoutMs, TimeUnit.MILLISECONDS)
                .join();
        } catch (Exception e) {
            log.warn("HistoricalDataService [{}]: {} {} bounded refresh failed -- {}", context, instrument, timeframe, e.getMessage());
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ExecutorService newBackfillPool(int instrumentCount) {
        return Executors.newFixedThreadPool(
            Math.min(instrumentCount, 2),
            r -> { Thread t = new Thread(r, "hist-backfill"); t.setDaemon(true); return t; }
        );
    }

    private void awaitAll(List<CompletableFuture<Void>> futures, ExecutorService pool, String context) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("HistoricalDataService [{}]: parallel fetch interrupted -- {}", context, e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    private int candlesTargetFor(String timeframe) {
        return switch (timeframe) {
            case "5m"  -> minutesToCandles(backfillDays5m, 5);
            case "10m" -> minutesToCandles(backfillDays10m, 10);
            case "30m" -> minutesToCandles(backfillDays30m, 30);
            case "1h"  -> minutesToCandles(backfillDays1h, 60);
            case "4h"  -> minutesToCandles(backfillDays4h, 240);
            default -> DEFAULT_CANDLES_PER_PAIR;
        };
    }

    private int minutesToCandles(int days, int candleMinutes) {
        int safeDays = Math.max(days, 1);
        double candles = (safeDays * 24.0 * 60.0) / candleMinutes;
        return Math.max(DEFAULT_CANDLES_PER_PAIR, (int) Math.ceil(candles));
    }

    private long timeframeSeconds(String timeframe) {
        return switch (timeframe) {
            case "5m"  -> 5L * 60;
            case "10m" -> 10L * 60;
            case "30m" -> 30L * 60;
            case "1h"  -> 60L * 60;
            case "4h"  -> 4L * 60 * 60;
            case "1d"  -> 24L * 60 * 60;
            default    -> 60L;
        };
    }

    private List<Candle> tagWithContractMonth(List<Candle> candles, Instrument instrument) {
        String contractMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        if (contractMonth == null) return candles;
        for (Candle candle : candles) {
            if (candle.getContractMonth() == null) {
                candle.setContractMonth(contractMonth);
            }
        }
        return candles;
    }

    private List<Candle> deduplicate(List<Candle> candles) {
        Map<String, Candle> unique = new LinkedHashMap<>();
        for (Candle candle : candles) {
            String key = candle.getInstrument() + "|" + candle.getTimeframe() + "|" + candle.getTimestamp();
            unique.put(key, candle);
        }
        if (unique.size() != candles.size()) {
            log.debug("HistoricalDataService: removed {} duplicate candles before save.", candles.size() - unique.size());
        }
        return new ArrayList<>(unique.values());
    }
}

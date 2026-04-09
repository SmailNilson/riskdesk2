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
import org.springframework.scheduling.annotation.Scheduled;
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
 * Fetches real historical OHLCV candles on startup and persists them.
 * Runs after DataInitializer (@Order(1)), loading candles from the configured historical provider.
 *
 * <p><b>Gap-fill strategy</b>: on startup, if candles already exist in DB, only the delta
 * since the high-water mark (latest stored timestamp) is fetched from IBKR.
 * The full deep backfill only runs when the DB is empty (first run).
 * Startup is non-blocking — the app serves existing (stale) data immediately while
 * the delta backfill runs in the background.</p>
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
    /** Set to true once deep multi-contract backfill has completed. */
    private final AtomicBoolean deepBackfillDone = new AtomicBoolean(false);
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

        boolean hasExistingData = Instrument.exchangeTradedFutures().stream()
            .anyMatch(i -> candlePort.findLatestTimestamp(i, "5m").isPresent());

        if (hasExistingData) {
            realDataLoaded.set(true);
            log.info("HistoricalDataService: existing candles found — starting delta backfill in background.");
            CompletableFuture.runAsync(() -> gapFillAll("startup-delta"))
                .exceptionally(ex -> { log.error("Startup delta backfill failed", ex); return null; });
        } else {
            log.info("HistoricalDataService: no existing candles — full backfill in background.");
            CompletableFuture.runAsync(() -> tryFetchAndReplace("startup-full", DEFAULT_CANDLES_PER_PAIR, 1))
                .exceptionally(ex -> { log.error("Startup full backfill failed", ex); return null; });
        }
    }

    /**
     * Phase 2: deep multi-contract backfill — runs 5 minutes after startup.
     * Walks backward through expired contracts with full target counts.
     * Sequential (1 thread) to respect IBKR pacing limits (~60 requests/10 min).
     */
    @Scheduled(initialDelay = 5 * 60 * 1000, fixedDelay = 24 * 60 * 60 * 1000)
    public void deepBackfill() {
        if (!enabled || deepBackfillDone.get()) return;

        // Skip deep backfill if gap-fill already loaded recent data (common restart case).
        // Deep backfill is only needed for first-run or after prolonged downtime.
        if (realDataLoaded.get()) {
            boolean allFresh = Instrument.exchangeTradedFutures().stream()
                .allMatch(i -> {
                    Optional<Instant> hwm = candlePort.findLatestTimestamp(i, "5m");
                    return hwm.isPresent() && Duration.between(hwm.get(), Instant.now()).toMinutes() < 30;
                });
            if (allFresh) {
                log.info("HistoricalDataService: skipping deep backfill — gap-fill already covered all instruments.");
                deepBackfillDone.set(true);
                return;
            }
        }

        log.info("HistoricalDataService: starting deep multi-contract backfill...");
        tryFetchAndReplace("deep-backfill", -1, 1);
        deepBackfillDone.set(true);
    }

    /** Resets the deep backfill flag so the next scheduled run re-fetches all data. */
    public void resetDeepBackfill() {
        deepBackfillDone.set(false);
    }

    /**
     * Quick rollover refresh: fetches 7 days of 5m+1h data for one instrument.
     * Fixes the chart spike immediately after contract switch.
     * Runs synchronously — caller should wrap in CompletableFuture if async desired.
     */
    public Map<String, Integer> refreshInstrumentRollover(Instrument instrument) {
        if (!enabled) return Collections.emptyMap();

        Map<String, Integer> savedByTimeframe = new LinkedHashMap<>();
        Map<String, Integer> targets = Map.of(
            "5m",  7 * 24 * 60 / 5,   // ~2016 candles
            "1h",  7 * 24             // ~168 candles
        );
        for (var entry : targets.entrySet()) {
            String tf = entry.getKey();
            int limit = entry.getValue();
            try {
                if (!historicalProvider.supports(instrument, tf)) continue;
                List<Candle> candles = deduplicate(
                    tagWithContractMonth(historicalProvider.fetchHistory(instrument, tf, limit), instrument));
                if (!candles.isEmpty()) {
                    candlePort.deleteByInstrumentAndTimeframe(instrument, tf);
                    candlePort.saveAll(candles);
                    savedByTimeframe.put(tf, candles.size());
                    log.info("HistoricalDataService [rollover]: {} {} refreshed with {} candles.", instrument, tf, candles.size());
                }
            } catch (Exception e) {
                log.warn("HistoricalDataService [rollover]: {} {} failed — {}", instrument, tf, e.getMessage());
            }
        }
        return savedByTimeframe;
    }

    /** Retry every 30 minutes until initial data is loaded. */
    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 30 * 60 * 1000)
    public void scheduledRetry() {
        if (!enabled || realDataLoaded.get()) return;
        log.info("HistoricalDataService: retrying historical candle fetch...");
        tryFetchAndReplace("retry", DEFAULT_CANDLES_PER_PAIR, 1);
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
     * On contract rollover, delete all candles for the rolled instrument (all timeframes)
     * and trigger a full historical backfill from the new contract. This ensures indicators
     * recalculate on a clean price series without old-contract data contamination.
     *
     * Runs async via CompletableFuture (not @Async) because this class implements
     * ApplicationRunner — Spring's JDK proxy would not expose this method otherwise.
     */
    @EventListener
    public void onContractRollover(ContractRolloverEvent event) {
        Instrument instrument = event.instrument();
        CompletableFuture.runAsync(() -> {
            log.info("Rollover backfill: deleting old candles and backfilling {} (new contract {})",
                    instrument, event.newContractMonth());

            for (String timeframe : TIMEFRAMES) {
                try {
                    candlePort.deleteByInstrumentAndTimeframe(instrument, timeframe);
                } catch (Exception e) {
                    log.warn("Rollover backfill: failed to delete {} {} candles: {}", instrument, timeframe, e.getMessage());
                }
            }

            Map<String, Integer> result = refreshInstrumentContext(instrument, TIMEFRAMES);
            log.info("Rollover backfill complete for {}: {}", instrument, result);
        }).exceptionally(ex -> {
            log.error("Rollover backfill failed for {}: {}", instrument, ex.getMessage(), ex);
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
    // Full backfill (deep multi-contract, from main)
    // -------------------------------------------------------------------------

    /**
     * @param maxCandlesOverride  if > 0, caps the candle count per timeframe (used for quick startup).
     *                            If -1, uses full candlesTargetFor() (deep multi-contract backfill).
     * @param threadCount         number of parallel instrument threads (1 = sequential for IBKR pacing).
     */
    private void tryFetchAndReplace(String context, int maxCandlesOverride, int threadCount) {
        log.info("HistoricalDataService [{}]: fetching OHLCV candles (threads={}, maxCandles={})...",
            context, threadCount, maxCandlesOverride > 0 ? maxCandlesOverride : "full");
        AtomicInteger totalSaved = new AtomicInteger(0);
        List<Instrument> instruments = Instrument.exchangeTradedFutures();

        ExecutorService pool = Executors.newFixedThreadPool(
            Math.min(instruments.size(), threadCount),
            r -> { Thread t = new Thread(r, "hist-backfill"); t.setDaemon(true); return t; }
        );

        List<CompletableFuture<Void>> futures = instruments.stream()
            .map(instrument -> CompletableFuture.runAsync(() ->
                fetchAllTimeframesForInstrument(instrument, context, maxCandlesOverride, totalSaved), pool))
            .toList();

        try {
            // Deep backfill can take longer — give it 30 minutes
            int timeoutMinutes = maxCandlesOverride > 0 ? 10 : 30;
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(timeoutMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("HistoricalDataService [{}]: fetch interrupted — {}", context, e.getMessage());
        } finally {
            pool.shutdown();
        }

        if (totalSaved.get() == 0) {
            log.warn("HistoricalDataService [{}]: no candles retrieved — retaining existing data.", context);
            return;
        }

        realDataLoaded.set(true);
        log.info("HistoricalDataService [{}]: done — {} IBKR candles saved.", context, totalSaved.get());
    }

    private void fetchAllTimeframesForInstrument(Instrument instrument, String context,
                                                  int maxCandlesOverride, AtomicInteger totalSaved) {
        for (String timeframe : TIMEFRAMES) {
            if (!historicalProvider.supports(instrument, timeframe)) continue;
            try {
                int fullTarget = candlesTargetFor(timeframe);
                int limit = maxCandlesOverride > 0 ? Math.min(maxCandlesOverride, fullTarget) : fullTarget;
                List<Candle> candles = historicalProvider.fetchHistory(instrument, timeframe, limit);
                candles = tagWithContractMonth(candles, instrument);
                candles = deduplicate(candles);

                // Filter out candles already in DB to avoid unique-key collisions
                // (e.g. deep-backfill overlapping with startup-full's recent candles)
                Optional<Instant> hwm = candlePort.findLatestTimestamp(instrument, timeframe);
                if (hwm.isPresent()) {
                    Instant cutoff = hwm.get();
                    candles = candles.stream()
                        .filter(c -> c.getTimestamp().isAfter(cutoff))
                        .toList();
                }

                if (!candles.isEmpty()) {
                    candlePort.saveAll(candles);
                    totalSaved.addAndGet(candles.size());
                    log.debug("HistoricalDataService [{}]: {} {} fetched {} candles.", context, instrument, timeframe, candles.size());
                }
            } catch (Exception e) {
                log.debug("HistoricalDataService [{}]: {} {} fetch failed — {}", context, instrument, timeframe, e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gap-fill (incremental delta fetch)
    // -------------------------------------------------------------------------

    private void gapFillAll(String context) {
        log.info("HistoricalDataService [{}]: gap-fill — fetching delta candles (parallel by instrument)...", context);
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
            log.info("HistoricalDataService [{}]: gap-fill done — {} new candles saved.", context, totalSaved.get());
        } else if (totalFailed.get() == 0) {
            log.info("HistoricalDataService [{}]: gap-fill done — candles already up to date.", context);
        } else {
            log.warn("HistoricalDataService [{}]: gap-fill failed for {} timeframes — scheduledRetry will re-attempt.", context, totalFailed.get());
            // Do NOT set realDataLoaded — keep scheduledRetry active
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
                log.debug("HistoricalDataService [{}]: {} {} gap-fill failed — {}", context, instrument, timeframe, e.getMessage());
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
            log.warn("HistoricalDataService [{}]: {} {} bounded refresh failed — {}", context, instrument, timeframe, e.getMessage());
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ExecutorService newBackfillPool(int instrumentCount) {
        // Cap at 2 threads to respect IBKR pacing limits (~60 req/10 min).
        // 4 parallel instruments caused request storms and cascading timeouts.
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
            log.warn("HistoricalDataService [{}]: parallel fetch interrupted — {}", context, e.getMessage());
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
            // Preserve contract month already set by multi-contract backfill
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

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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

    private static final List<String> TIMEFRAMES       = List.of("1m", "5m", "10m", "1h", "4h", "1d");
    private static final int          DEFAULT_CANDLES_PER_PAIR = 500;
    private static final int          GAP_FILL_BUFFER  = 100;

    private final HistoricalDataProvider historicalProvider;
    private final CandleRepositoryPort   candlePort;
    private final ActiveContractRegistry contractRegistry;

    @Value("${riskdesk.market-data.historical.enabled:false}")
    private boolean enabled;

    @Value("${riskdesk.market-data.historical.backfill-days-1m:30}")
    private int backfillDays1m;

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

    /** Safety bound on the width of an on-demand range backfill window (protects IBKR pacing). */
    @Value("${riskdesk.market-data.historical.backfill-range-max-days:200}")
    private int backfillRangeMaxDays;

    /** Set to true once real candles have been successfully loaded (quick startup). */
    private final AtomicBoolean realDataLoaded = new AtomicBoolean(false);
    private final Map<RefreshKey, Long> mentorRefreshTimestamps = new ConcurrentHashMap<>();

    /** Last/active range-backfill job per instrument+timeframe (observability for the admin endpoint). */
    private final Map<RefreshKey, BackfillJob> backfillJobs = new ConcurrentHashMap<>();

    /**
     * Single-threaded so heavy range backfills never run concurrently — serialising them keeps the
     * app well under IBKR historical pacing limits and avoids starving the common ForkJoinPool.
     */
    private final ExecutorService backfillExecutor = Executors.newSingleThreadExecutor(
        r -> { Thread t = new Thread(r, "hist-range-backfill"); t.setDaemon(true); return t; });

    private record RefreshKey(Instrument instrument, String timeframe) {}

    /** Immutable snapshot of a range-backfill job, exposed to the admin REST endpoint. */
    public record BackfillJob(Instrument instrument, String timeframe, String state,
                              Instant from, Instant to, int fetched, int existing, int saved,
                              long startedAt, Long finishedAt, String message) {
        public boolean running() { return "RUNNING".equals(state); }
    }

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
    // Deep range backfill (on-demand, idempotent, bounded window)
    // -------------------------------------------------------------------------

    /**
     * Triggers a deep, idempotent backfill of a single instrument/timeframe over the closed
     * window {@code [from, to]}. Unlike {@link #gapFillTimeframe} (which only appends past the
     * high-water mark), this reconstructs the whole window and fills middle gaps — making it the
     * right tool to seed 1m history for faithful backtests.
     *
     * <p>Idempotent: timestamps already stored in the window are skipped, so the call is safe to
     * re-run (e.g. daily) to top up missing data. Heavy work runs on a single dedicated thread
     * to respect IBKR pacing; concurrent jobs for the same pair are coalesced.</p>
     *
     * @param async when {@code true}, returns immediately with a {@code RUNNING} snapshot; poll
     *              {@link #backfillStatus} for completion. When {@code false}, blocks until done.
     */
    public BackfillJob startBackfillRange(Instrument instrument, String timeframe,
                                          Instant from, Instant to, boolean async) {
        long now = System.currentTimeMillis();
        if (!enabled) {
            return new BackfillJob(instrument, timeframe, "DISABLED", from, to, 0, 0, 0, now, now,
                "Historical data fetch is disabled (riskdesk.market-data.historical.enabled=false).");
        }

        String rejection = validateBackfillRange(instrument, timeframe, from, to);
        if (rejection != null) {
            return new BackfillJob(instrument, timeframe, "REJECTED", from, to, 0, 0, 0, now, now, rejection);
        }

        RefreshKey key = new RefreshKey(instrument, timeframe);
        BackfillJob existingJob = backfillJobs.get(key);
        if (existingJob != null && existingJob.running()) {
            return existingJob; // coalesce — a job for this pair is already in flight
        }

        BackfillJob started = new BackfillJob(instrument, timeframe, "RUNNING", from, to,
            0, 0, 0, now, null, "Backfill started.");
        backfillJobs.put(key, started);

        if (async) {
            CompletableFuture
                .runAsync(() -> runBackfillRange(instrument, timeframe, from, to, now), backfillExecutor)
                .exceptionally(ex -> {
                    backfillJobs.put(key, new BackfillJob(instrument, timeframe, "FAILED", from, to,
                        0, 0, 0, now, System.currentTimeMillis(), "Backfill failed: " + ex.getMessage()));
                    log.error("HistoricalDataService [range-backfill]: {} {} async failed", instrument, timeframe, ex);
                    return null;
                });
            return started;
        }
        return runBackfillRange(instrument, timeframe, from, to, now);
    }

    /** Returns the last known backfill job for a pair, if any. */
    public Optional<BackfillJob> backfillStatus(Instrument instrument, String timeframe) {
        return Optional.ofNullable(backfillJobs.get(new RefreshKey(instrument, timeframe)));
    }

    private String validateBackfillRange(Instrument instrument, String timeframe, Instant from, Instant to) {
        if (!instrument.isExchangeTradedFuture()) {
            return "Instrument " + instrument + " is not an exchange-traded future.";
        }
        if (!historicalProvider.supports(instrument, timeframe)) {
            return "Timeframe '" + timeframe + "' is not supported for backfill.";
        }
        if (from == null || to == null) {
            return "Both 'from' and 'to' are required.";
        }
        if (!from.isBefore(to)) {
            return "'from' must be strictly before 'to'.";
        }
        long days = Duration.between(from, to).toDays();
        if (days > backfillRangeMaxDays) {
            return String.format("Requested window of %d days exceeds the maximum of %d days "
                + "(riskdesk.market-data.historical.backfill-range-max-days).", days, backfillRangeMaxDays);
        }
        return null;
    }

    private BackfillJob runBackfillRange(Instrument instrument, String timeframe,
                                         Instant from, Instant to, long startedAt) {
        RefreshKey key = new RefreshKey(instrument, timeframe);
        AtomicInteger fetched  = new AtomicInteger(0);
        AtomicInteger existing = new AtomicInteger(0);
        AtomicInteger saved    = new AtomicInteger(0);
        try {
            // Stream the window chunk-by-chunk straight into PostgreSQL. Heap stays bounded to a
            // single chunk (~one IBKR request) rather than holding the whole [from,to] window — a
            // deep 1m window spans months (~10^5 candles) and must never be buffered in memory.
            historicalProvider.fetchHistoryRange(instrument, timeframe, from, to, chunk ->
                persistBackfillChunk(instrument, timeframe, chunk, fetched, existing, saved));

            BackfillJob done = new BackfillJob(instrument, timeframe, "DONE", from, to,
                fetched.get(), existing.get(), saved.get(),
                startedAt, System.currentTimeMillis(),
                String.format("Backfill complete: %d fetched, %d already present, %d new saved.",
                    fetched.get(), existing.get(), saved.get()));
            backfillJobs.put(key, done);
            log.info("HistoricalDataService [range-backfill]: {} {} [{} .. {}] — {} fetched, {} already present, {} new saved (streamed).",
                instrument, timeframe, from, to, fetched.get(), existing.get(), saved.get());
            return done;
        } catch (Exception e) {
            BackfillJob failed = new BackfillJob(instrument, timeframe, "FAILED", from, to,
                0, 0, 0, startedAt, System.currentTimeMillis(), "Backfill failed: " + e.getMessage());
            backfillJobs.put(key, failed);
            log.error("HistoricalDataService [range-backfill]: {} {} failed", instrument, timeframe, e);
            return failed;
        }
    }

    /**
     * Persists one streamed backfill chunk idempotently and tallies counters. The existence probe
     * is bounded to this chunk's own min/max timestamp, so the lookup stays small and re-runs (or
     * older-contract chunks overlapping already-saved front-month bars) become no-op saves without
     * tripping the {@code (instrument, timeframe, ts)} unique key.
     */
    private void persistBackfillChunk(Instrument instrument, String timeframe, List<Candle> chunk,
                                      AtomicInteger fetched, AtomicInteger existing, AtomicInteger saved) {
        if (chunk == null || chunk.isEmpty()) return;
        fetched.addAndGet(chunk.size());

        List<Candle> deduped = deduplicate(tagWithContractMonth(chunk, instrument));

        Instant chunkFrom = deduped.get(0).getTimestamp();
        Instant chunkTo   = chunkFrom;
        for (Candle c : deduped) {
            Instant ts = c.getTimestamp();
            if (ts.isBefore(chunkFrom)) chunkFrom = ts;
            if (ts.isAfter(chunkTo))    chunkTo   = ts;
        }

        Set<Instant> existingTs = candlePort.findCandlesBetween(instrument, timeframe, chunkFrom, chunkTo).stream()
            .map(Candle::getTimestamp)
            .collect(Collectors.toCollection(HashSet::new));

        List<Candle> toSave = deduped.stream()
            .filter(c -> !existingTs.contains(c.getTimestamp()))
            .toList();

        existing.addAndGet(deduped.size() - toSave.size());
        if (!toSave.isEmpty()) {
            candlePort.saveAll(toSave);
            saved.addAndGet(toSave.size());
            realDataLoaded.set(true);
        }
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
            case "1m"  -> minutesToCandles(backfillDays1m, 1);
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
            case "1m"  -> 60L;
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

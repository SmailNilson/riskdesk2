package com.riskdesk.application.service;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.marketdata.port.HistoricalDataProvider;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * If the initial fetch fails, retries every 30 minutes.
 *
 * Enabled via: riskdesk.market-data.historical.enabled=true
 */
@Service
@Order(2)
public class HistoricalDataService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataService.class);

    private static final List<String> TIMEFRAMES       = List.of("5m", "10m", "1h", "4h", "1d");
    private static final int          DEFAULT_CANDLES_PER_PAIR = 500;

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
        // Phase 1: quick single-contract fetch (500 candles, 1 thread) — non-blocking.
        // Gives charts and indicators enough data to work within seconds.
        CompletableFuture.runAsync(() -> tryFetchAndReplace("startup", DEFAULT_CANDLES_PER_PAIR, 1))
            .exceptionally(ex -> {
                log.error("Startup historical data fetch failed", ex);
                return null;
            });
    }

    /**
     * Phase 2: deep multi-contract backfill — runs 5 minutes after startup.
     * Walks backward through expired contracts with full target counts.
     * Sequential (1 thread) to respect IBKR pacing limits (~60 requests/10 min).
     */
    @Scheduled(initialDelay = 5 * 60 * 1000, fixedDelay = 24 * 60 * 60 * 1000)
    public void deepBackfill() {
        if (!enabled || deepBackfillDone.get()) return;
        log.info("HistoricalDataService: starting deep multi-contract backfill...");
        tryFetchAndReplace("deep-backfill", -1, 1);
        deepBackfillDone.set(true);
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
     * Refreshes all timeframes for a single instrument without cooldown or timeout.
     * Used after contract rollover to replace old-contract candles with new-contract data.
     * Runs synchronously — caller should wrap in CompletableFuture if async is desired.
     */
    public Map<String, Integer> refreshInstrumentFull(Instrument instrument) {
        if (!enabled) return Collections.emptyMap();

        Map<String, Integer> savedByTimeframe = new LinkedHashMap<>();
        for (String timeframe : TIMEFRAMES) {
            int saved = refreshSingleInstrumentTimeframe(instrument, timeframe, "rollover");
            savedByTimeframe.put(timeframe, saved);
        }
        return savedByTimeframe;
    }

    /** Trigger a manual full refresh asynchronously. Returns immediately. */
    public Map<String, Object> refreshAll() {
        if (!enabled) {
            return Map.of("status", "disabled", "message", "Historical data fetch is disabled.");
        }
        CompletableFuture.runAsync(() -> tryFetchAndReplace("manual", -1, 4))
            .exceptionally(ex -> {
                log.error("Async historical data refresh failed", ex);
                return null;
            });
        return Map.of("status", "ok", "message", "Database refresh started in background.");
    }

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
                if (candles.isEmpty()) {
                    log.debug("HistoricalDataService [{}]: {} {} returned no candles.", context, instrument, timeframe);
                } else {
                    candlePort.deleteByInstrumentAndTimeframe(instrument, timeframe);
                    candlePort.saveAll(candles);
                    totalSaved.addAndGet(candles.size());
                    log.debug("HistoricalDataService [{}]: {} {} fetched {} candles.", context, instrument, timeframe, candles.size());
                }
            } catch (Exception e) {
                log.debug("HistoricalDataService [{}]: {} {} fetch failed — {}", context, instrument, timeframe, e.getMessage());
            }
        }
    }

    private int refreshSingleInstrumentTimeframe(Instrument instrument, String timeframe, String context) {
        if (!instrument.isExchangeTradedFuture()) {
            return 0;
        }
        if (!historicalProvider.supports(instrument, timeframe)) {
            return 0;
        }

        try {
            int limit = candlesTargetFor(timeframe);
            List<Candle> candles = deduplicate(
                tagWithContractMonth(historicalProvider.fetchHistory(instrument, timeframe, limit), instrument));
            if (candles.isEmpty()) {
                log.debug("HistoricalDataService [{}]: {} {} returned no candles.", context, instrument, timeframe);
                return 0;
            }

            candlePort.deleteByInstrumentAndTimeframe(instrument, timeframe);
            candlePort.saveAll(candles);
            log.info("HistoricalDataService [{}]: refreshed {} {} with {} candles.", context, instrument, timeframe, candles.size());
            return candles.size();
        } catch (Exception e) {
            log.warn("HistoricalDataService [{}]: {} {} refresh failed — {}", context, instrument, timeframe, e.getMessage());
            return 0;
        }
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

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Value("${riskdesk.market-data.historical.backfill-days-10m:90}")
    private int backfillDays10m;

    @Value("${riskdesk.market-data.historical.backfill-days-1h:365}")
    private int backfillDays1h;

    @Value("${riskdesk.market-data.historical.mentor-refresh-timeout-ms:2500}")
    private long mentorRefreshTimeoutMs;

    @Value("${riskdesk.market-data.historical.mentor-refresh-cooldown-ms:60000}")
    private long mentorRefreshCooldownMs;

    /** Set to true once real candles have been successfully loaded. */
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
        tryFetchAndReplace("startup");
    }

    /** Retry every 30 minutes until real data is loaded. */
    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 30 * 60 * 1000)
    public void scheduledRetry() {
        if (!enabled || realDataLoaded.get()) return;
        log.info("HistoricalDataService: retrying historical candle fetch...");
        tryFetchAndReplace("retry");
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

    /** Trigger a manual full refresh asynchronously. Returns immediately. */
    public Map<String, Object> refreshAll() {
        if (!enabled) {
            return Map.of("status", "disabled", "message", "Historical data fetch is disabled.");
        }
        CompletableFuture.runAsync(() -> tryFetchAndReplace("manual"));
        return Map.of("status", "ok", "message", "Database refresh started in background.");
    }

    // -------------------------------------------------------------------------

    private void tryFetchAndReplace(String context) {
        log.info("HistoricalDataService [{}]: fetching real OHLCV candles...", context);
        int totalSaved = 0;

        for (Instrument instrument : Instrument.values()) {
            if (instrument.isSynthetic()) continue; // DXY candles come from live accumulation, not IBKR history
            for (String timeframe : TIMEFRAMES) {
                if (!historicalProvider.supports(instrument, timeframe)) continue;
                try {
                    int limit = candlesTargetFor(timeframe);
                    List<Candle> candles = historicalProvider.fetchHistory(instrument, timeframe, limit);
                    candles = tagWithContractMonth(candles, instrument);
                    candles = deduplicate(candles);
                    if (candles.isEmpty()) {
                        log.debug("HistoricalDataService [{}]: {} {} returned no candles.", context, instrument, timeframe);
                    } else {
                        candlePort.deleteByInstrumentAndTimeframe(instrument, timeframe);
                        candlePort.saveAll(candles);
                        totalSaved += candles.size();
                        log.debug("HistoricalDataService [{}]: {} {} fetched {} candles.", context, instrument, timeframe, candles.size());
                    }
                } catch (Exception e) {
                    log.debug("HistoricalDataService [{}]: {} {} fetch failed — {}", context, instrument, timeframe, e.getMessage());
                }
            }
        }

        if (totalSaved == 0) {
            log.warn("HistoricalDataService [{}]: no candles retrieved — retaining existing data. Will retry in 30 min.", context);
            return;
        }

        realDataLoaded.set(true);
        log.info("HistoricalDataService [{}]: done — {} IBKR candles saved to chart.", context, totalSaved);
    }

    private int refreshSingleInstrumentTimeframe(Instrument instrument, String timeframe, String context) {
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
            case "10m" -> minutesToCandles(backfillDays10m, 10);
            case "1h" -> minutesToCandles(backfillDays1h, 60);
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
            candle.setContractMonth(contractMonth);
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

package com.riskdesk.application.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Value("${riskdesk.market-data.historical.enabled:false}")
    private boolean enabled;

    @Value("${riskdesk.market-data.historical.backfill-days-10m:90}")
    private int backfillDays10m;

    @Value("${riskdesk.market-data.historical.backfill-days-1h:365}")
    private int backfillDays1h;

    /** Set to true once real candles have been successfully loaded. */
    private final AtomicBoolean realDataLoaded = new AtomicBoolean(false);

    public HistoricalDataService(HistoricalDataProvider historicalProvider,
                                 CandleRepositoryPort candlePort) {
        this.historicalProvider = historicalProvider;
        this.candlePort         = candlePort;
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

    // -------------------------------------------------------------------------

    private void tryFetchAndReplace(String context) {
        log.info("HistoricalDataService [{}]: fetching real OHLCV candles...", context);
        int totalSaved = 0;

        for (Instrument instrument : Instrument.values()) {
            for (String timeframe : TIMEFRAMES) {
                if (!historicalProvider.supports(instrument, timeframe)) continue;
                try {
                    int limit = candlesTargetFor(timeframe);
                    List<Candle> candles = historicalProvider.fetchHistory(instrument, timeframe, limit);
                    candles = deduplicate(candles);
                    if (candles.isEmpty()) {
                        log.warn("  {} {} : no candles returned (rate-limited or unavailable)", instrument, timeframe);
                    } else {
                        candlePort.deleteByInstrumentAndTimeframe(instrument, timeframe);
                        candlePort.saveAll(candles);
                        totalSaved += candles.size();
                        log.info("  {} {} : {} candles fetched", instrument, timeframe, candles.size());
                    }
                } catch (Exception e) {
                    log.error("  {} {} : fetch failed — {}", instrument, timeframe, e.getMessage());
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

    private List<Candle> deduplicate(List<Candle> candles) {
        Map<String, Candle> unique = new LinkedHashMap<>();
        for (Candle candle : candles) {
            String key = candle.getInstrument() + "|" + candle.getTimeframe() + "|" + candle.getTimestamp();
            unique.put(key, candle);
        }
        if (unique.size() != candles.size()) {
            log.warn("HistoricalDataService: removed {} duplicate candles before save.", candles.size() - unique.size());
        }
        return new ArrayList<>(unique.values());
    }
}

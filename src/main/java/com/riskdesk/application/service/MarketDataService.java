package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.event.ContractRolloverEvent;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.marketdata.event.MarketPriceUpdated;
import com.riskdesk.domain.marketdata.port.MarketDataProvider;
import com.riskdesk.domain.marketdata.port.StreamingPriceListener;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.riskdesk.domain.model.AssetClass;
import com.riskdesk.domain.shared.TradingSessionResolver;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Polls the configured MarketDataProvider every N seconds.
 * – Updates open position P&L via PositionService.
 * – Pushes price ticks to WebSocket /topic/prices.
 * – Accumulates 10m and 1h OHLCV candles and persists them via CandleRepositoryPort.
 * – Publishes MarketPriceUpdated and CandleClosed domain events.
 * – Falls back to the latest stored IBKR-backed candle close from the database when IBKR is unavailable.
 */
@Service
@Profile("!test")
public class MarketDataService implements StreamingPriceListener {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** Minimum interval between push-based WebSocket updates per instrument. */
    private static final long PUSH_DEBOUNCE_MS = 100L;

    private static final Map<String, Long> TIMEFRAMES = Map.of(
        "5m",  5L,
        "10m", 10L,
        "30m", 30L,
        "1h",  60L,
        "4h",  240L
    );
    private static final String[] FALLBACK_TIMEFRAMES = {"5m", "10m", "30m", "1h", "4h", "1d"};
    private static final long FRESH_CACHE_SECONDS = 15L;
    private static final long INSTANT_FETCH_TIMEOUT_MS = 1200L;

    private record CandleKey(Instrument instrument, String timeframe) {}

    private final MarketDataProvider        marketDataProvider;
    private final PositionService           positionService;
    private final AlertService              alertService;
    private final BehaviourAlertService     behaviourAlertService;
    private final CandleRepositoryPort      candlePort;
    private final ActiveContractRegistry    contractRegistry;
    private final SimpMessagingTemplate     messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final DxyMarketService          dxyMarketService;

    private final Map<Instrument, BigDecimal>    lastPrice    = new ConcurrentHashMap<>();
    private final Map<Instrument, Instant>       lastTimestamp = new ConcurrentHashMap<>();
    private final Map<Instrument, String>        lastSource   = new ConcurrentHashMap<>();
    private final Map<CandleKey, CandleAccumulator> accumulators = new ConcurrentHashMap<>();
    private final Map<Instrument, Instant>       lastPushAt   = new ConcurrentHashMap<>();
    private volatile boolean databaseFallbackActive = false;

    // Dedicated thread pool for alert evaluation — isolated from ForkJoinPool.commonPool
    // (used by backfill) and the Spring @Async pool (used by signal scanners).
    private final ExecutorService alertEvalExecutor = Executors.newFixedThreadPool(4,
            r -> { Thread t = new Thread(r, "alert-eval"); t.setDaemon(true); return t; });

    public MarketDataService(MarketDataProvider marketDataProvider,
                             PositionService positionService,
                             AlertService alertService,
                             BehaviourAlertService behaviourAlertService,
                             CandleRepositoryPort candlePort,
                             ActiveContractRegistry contractRegistry,
                             SimpMessagingTemplate messagingTemplate,
                             ApplicationEventPublisher eventPublisher,
                             DxyMarketService dxyMarketService) {
        this.marketDataProvider    = marketDataProvider;
        this.positionService       = positionService;
        this.alertService          = alertService;
        this.behaviourAlertService = behaviourAlertService;
        this.candlePort            = candlePort;
        this.contractRegistry      = contractRegistry;
        this.messagingTemplate     = messagingTemplate;
        this.eventPublisher        = eventPublisher;
        this.dxyMarketService      = dxyMarketService;
    }

    @Scheduled(fixedDelayString = "${riskdesk.market-data.poll-interval:5000}",
               initialDelayString = "${riskdesk.market-data.poll-initial-delay:60000}")
    public void pollPrices() {
        Map<Instrument, BigDecimal> prices = marketDataProvider.fetchPrices();
        Instant now = Instant.now();
        boolean usedDatabaseFallback = false;

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            BigDecimal price = prices.get(instrument);
            Instant timestamp = now;
            boolean fallbackPrice = false;

            if (price == null) {
                StoredPrice storedPrice = loadStoredPrice(instrument);
                if (storedPrice == null) continue;
                price = storedPrice.price();
                timestamp = storedPrice.timestamp();
                fallbackPrice = true;
                usedDatabaseFallback = true;
            }

            if (fallbackPrice && samePrice(lastPrice.get(instrument), price)) {
                continue;
            }

            boolean marketOpen = TradingSessionResolver.isMarketOpen(now, instrument);
            String source;
            if (fallbackPrice) {
                source = "FALLBACK_DB";
            } else if (!marketOpen) {
                source = "STALE";
            } else {
                source = "LIVE_PROVIDER";
            }

            lastPrice.put(instrument, price);
            lastTimestamp.put(instrument, timestamp);
            lastSource.put(instrument, source);

            positionService.updateMarketPrice(instrument, price);
            sendPriceUpdate(instrument, price, timestamp, source);
            eventPublisher.publishEvent(new MarketPriceUpdated(instrument.name(), price, timestamp));

            if (!fallbackPrice && marketOpen) {
                for (String tf : TIMEFRAMES.keySet()) {
                    accumulate(instrument, tf, price, now);
                }

                // OPT-2: fire-and-forget on dedicated alert-eval pool (4 threads, isolated from backfill)
                final Instrument evalInstrument = instrument;
                CompletableFuture.runAsync(() -> {
                    try { alertService.evaluate(evalInstrument); }
                    catch (Exception e) { log.debug("Async alert eval error for {}: {}", evalInstrument, e.getMessage()); }
                }, alertEvalExecutor);
                CompletableFuture.runAsync(() -> {
                    try { behaviourAlertService.evaluate(evalInstrument); }
                    catch (Exception e) { log.debug("Async behaviour eval error for {}: {}", evalInstrument, e.getMessage()); }
                }, alertEvalExecutor);
            }
        }

        dxyMarketService.refreshSyntheticDxy();

        if (usedDatabaseFallback && !databaseFallbackActive) {
            log.warn("IBKR unavailable: serving last known prices from the database.");
            databaseFallbackActive = true;
        } else if (!usedDatabaseFallback && databaseFallbackActive) {
            log.info("IBKR recovered: live market data polling resumed.");
            databaseFallbackActive = false;
        }
    }

    // -------------------------------------------------------------------------
    // Push-based live price updates (called from IBKR EReader thread)
    // -------------------------------------------------------------------------

    @Override
    public void onLivePriceUpdate(Instrument instrument, BigDecimal price, Instant timestamp) {
        // Debounce: suppress updates faster than PUSH_DEBOUNCE_MS per instrument
        Instant previousPush = lastPushAt.get(instrument);
        if (previousPush != null && Duration.between(previousPush, timestamp).toMillis() < PUSH_DEBOUNCE_MS) {
            return;
        }
        lastPushAt.put(instrument, timestamp);

        // Skip if price hasn't changed
        if (samePrice(lastPrice.get(instrument), price)) {
            return;
        }

        lastPrice.put(instrument, price);
        lastTimestamp.put(instrument, timestamp);
        lastSource.put(instrument, "LIVE_PUSH");

        positionService.updateMarketPrice(instrument, price);
        sendPriceUpdate(instrument, price, timestamp, "LIVE_PUSH");
        eventPublisher.publishEvent(new MarketPriceUpdated(instrument.name(), price, timestamp));

        boolean marketOpen = TradingSessionResolver.isMarketOpen(timestamp, instrument);
        if (marketOpen) {
            for (String tf : TIMEFRAMES.keySet()) {
                accumulate(instrument, tf, price, timestamp);
            }

            CompletableFuture.runAsync(() -> {
                try { alertService.evaluate(instrument); }
                catch (Exception e) { log.debug("Push alert eval error for {}: {}", instrument, e.getMessage()); }
            }, alertEvalExecutor);
            CompletableFuture.runAsync(() -> {
                try { behaviourAlertService.evaluate(instrument); }
                catch (Exception e) { log.debug("Push behaviour eval error for {}: {}", instrument, e.getMessage()); }
            }, alertEvalExecutor);
        }
    }

    // -------------------------------------------------------------------------
    // WebSocket push
    // -------------------------------------------------------------------------

    private void sendPriceUpdate(Instrument instrument, BigDecimal price, Instant now, String source) {
        try {
            Map<String, Object> payload = Map.of(
                "instrument",  instrument.name(),
                "displayName", instrument.getDisplayName(),
                "price",       price,
                "timestamp",   now.toString(),
                "source",      source
            );
            messagingTemplate.convertAndSend("/topic/prices", payload);
        } catch (Exception e) {
            log.debug("WebSocket send failed for {}: {}", instrument, e.getMessage());
        }
    }

    private StoredPrice loadStoredPrice(Instrument instrument) {
        Candle latest = null;

        for (String timeframe : FALLBACK_TIMEFRAMES) {
            java.util.List<Candle> candles = candlePort.findRecentCandles(instrument, timeframe, 1);
            if (candles.isEmpty()) continue;

            Candle candidate = candles.get(0);
            if (latest == null || candidate.getTimestamp().isAfter(latest.getTimestamp())) {
                latest = candidate;
            }
        }

        if (latest == null) {
            return null;
        }

        return new StoredPrice(latest.getClose(), latest.getTimestamp(), "FALLBACK_DB");
    }

    public StoredPrice latestPrice(Instrument instrument) {
        if (instrument == Instrument.DXY) {
            return dxyStoredPrice();
        }
        BigDecimal live = lastPrice.get(instrument);
        Instant liveTimestamp = lastTimestamp.get(instrument);
        String source = lastSource.get(instrument);
        if (live != null && liveTimestamp != null) {
            return new StoredPrice(live, liveTimestamp, source != null ? source : "CACHE");
        }
        StoredPrice fallback = loadStoredPrice(instrument);
        if (fallback == null) {
            return null;
        }
        return new StoredPrice(fallback.price(), fallback.timestamp(), "FALLBACK_DB");
    }

    public StoredPrice currentPrice(Instrument instrument) {
        if (instrument == Instrument.DXY) {
            return dxyStoredPrice();
        }
        StoredPrice cached = latestPrice(instrument);
        if (cached != null
            && !"FALLBACK_DB".equals(cached.source())
            && cached.timestamp() != null
            && cached.timestamp().isAfter(Instant.now().minusSeconds(FRESH_CACHE_SECONDS))) {
            return cached;
        }

        try {
            BigDecimal instant = CompletableFuture
                .supplyAsync(() -> marketDataProvider.fetchPrice(instrument).orElse(null))
                .get(INSTANT_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (instant != null) {
                Instant now = Instant.now();
                lastPrice.put(instrument, instant);
                lastTimestamp.put(instrument, now);
                lastSource.put(instrument, "LIVE_PROVIDER");
                return new StoredPrice(instant, now, "LIVE_PROVIDER");
            }
        } catch (Exception e) {
            log.debug("Instant price fetch failed for {}: {}", instrument, e.getMessage());
        }
        return cached;
    }

    private StoredPrice dxyStoredPrice() {
        return dxyMarketService.latestResolvedSnapshot()
            .map(snapshot -> new StoredPrice(
                snapshot.snapshot().dxyValue(),
                snapshot.snapshot().timestamp(),
                snapshot.servedSource()
            ))
            .orElse(null);
    }

    private boolean samePrice(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    // -------------------------------------------------------------------------
    // Candle accumulation
    // -------------------------------------------------------------------------

    /**
     * Returns true when {@code now} falls inside the instrument's CME Globex daily
     * maintenance halt (DST-aware via ZoneId). Candle accumulation and alerts must be
     * suppressed during this window — IBKR may emit stale last-trade ticks even though
     * the exchange is closed.
     * <ul>
     *   <li>FX (E6): 16:00–17:00 ET</li>
     *   <li>Energy (MCL), Metals (MGC), Equity-Index (MNQ): 17:00–18:00 ET</li>
     * </ul>
     */
    private static boolean isDuringMaintenanceWindow(Instrument instrument, Instant now) {
        if (instrument.assetClass() == AssetClass.FOREX) {
            return TradingSessionResolver.isFxMaintenanceWindow(now);
        }
        return TradingSessionResolver.isStandardMaintenanceWindow(now);
    }

    private void accumulate(Instrument instrument, String timeframe, BigDecimal price, Instant now) {
        if (isDuringMaintenanceWindow(instrument, now)) {
            log.trace("Suppressing {} {} candle — CME maintenance window active", instrument, timeframe);
            return;
        }

        String  contractMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        CandleKey key         = new CandleKey(instrument, timeframe);
        long    periodMins    = TIMEFRAMES.get(timeframe);
        Instant periodStart   = truncateToPeriod(now, periodMins);

        // Phase 1: atomically swap accumulator; capture closed period for side-effects.
        // DB save and event publish happen in Phase 2, outside the map lock.
        CandleAccumulator[] closed = {null};
        accumulators.compute(key, (k, acc) -> {
            if (acc == null) {
                return new CandleAccumulator(instrument, timeframe, contractMonth, periodStart, price);
            }
            if (acc.periodStart.isBefore(periodStart)) {
                closed[0] = acc;
                return new CandleAccumulator(instrument, timeframe, contractMonth, periodStart, price);
            }
            acc.update(price);
            return acc;
        });

        // Phase 2: persist and publish outside the map lock.
        if (closed[0] != null) {
            Candle candle = closed[0].build();
            candlePort.save(candle);
            log.debug("Saved candle {} {} {} O={} H={} L={} C={}",
                instrument, timeframe, contractMonth, candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose());
            eventPublisher.publishEvent(new CandleClosed(instrument.name(), timeframe, periodStart));
        }
    }

    private static Instant truncateToPeriod(Instant ts, long periodMinutes) {
        if (periodMinutes >= 1440) {
            // Daily+ candles align to CME session close (17:00 ET), not midnight UTC.
            return TradingSessionResolver.dailySessionStart(ts);
        }
        long epochMin    = ts.getEpochSecond() / 60;
        long periodStart = (epochMin / periodMinutes) * periodMinutes;
        return Instant.ofEpochSecond(periodStart * 60);
    }

    // -------------------------------------------------------------------------
    // Rollover: flush stale candle state
    // -------------------------------------------------------------------------

    /**
     * On contract rollover, flush all in-memory candle accumulators and cached
     * price state for the rolled instrument. This prevents the old-contract's
     * partial candle from contaminating the new contract's first bar.
     */
    @EventListener
    public void onContractRollover(ContractRolloverEvent event) {
        Instrument instrument = event.instrument();
        int flushed = 0;
        var it = accumulators.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().instrument() == instrument) {
                it.remove();
                flushed++;
            }
        }
        lastPrice.remove(instrument);
        lastTimestamp.remove(instrument);
        lastSource.remove(instrument);
        lastPushAt.remove(instrument);
        log.info("Rollover: flushed {} candle accumulators and price cache for {}", flushed, instrument);
    }

    // -------------------------------------------------------------------------
    // Inner accumulator
    // -------------------------------------------------------------------------

    private static class CandleAccumulator {
        final Instrument instrument;
        final String     timeframe;
        final String     contractMonth;
        final Instant    periodStart;
        final BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        long volume = 1;

        CandleAccumulator(Instrument instrument, String timeframe, String contractMonth,
                          Instant periodStart, BigDecimal firstPrice) {
            this.instrument    = instrument;
            this.timeframe     = timeframe;
            this.contractMonth = contractMonth;
            this.periodStart   = periodStart;
            this.open  = firstPrice;
            this.high  = firstPrice;
            this.low   = firstPrice;
            this.close = firstPrice;
        }

        void update(BigDecimal price) {
            if (price.compareTo(high) > 0) high  = price;
            if (price.compareTo(low)  < 0) low   = price;
            close = price;
            volume++;
        }

        Candle build() {
            return new Candle(instrument, timeframe, contractMonth, periodStart, open, high, low, close, volume);
        }
    }

    public record StoredPrice(BigDecimal price, Instant timestamp, String source) {}
}

package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.event.ContractRolloverEvent;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.marketdata.event.MarketPriceUpdated;
import com.riskdesk.domain.marketdata.port.MarketDataProvider;
import com.riskdesk.domain.marketdata.port.StreamingPriceListener;
import com.riskdesk.domain.model.AssetClass;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.TradingSessionResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayNativeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
 * – Accumulates intraday OHLCV candles (1m..4h) and persists them via CandleRepositoryPort.
 *   OHLC comes from price updates; volume comes from {@link #onLiveVolumeUpdate} — true traded
 *   contracts derived from IBKR's session-cumulative VOLUME tick, the same scale as IBKR
 *   historical bars. Volume is never inferred from the number of price updates.
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
        "1m",  1L,
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
    /**
     * Traded volume that arrived for a period whose accumulator does not exist yet (volume
     * ticks can precede the first price tick of a bar). Claimed when the accumulator for
     * that exact period is created; replaced when a newer period starts.
     */
    private final Map<CandleKey, PendingVolume>  pendingVolume = new ConcurrentHashMap<>();
    private final Map<Instrument, Instant>       lastPushAt   = new ConcurrentHashMap<>();
    private volatile boolean databaseFallbackActive = false;

    /**
     * Wall-clock of the most recent genuine live tick from IBKR (any instrument), stamped at the
     * head of {@link #onLivePriceUpdate} before any debounce/same-price short-circuit so it reflects
     * tick arrival, not price change. {@code null} until the first tick. Drives
     * {@link #priceFeedFreshnessWatchdog()} and {@link #liveTickAge()}.
     */
    private volatile Instant lastLiveTickAt = null;

    // Price-feed freshness watchdog config (see application.properties riskdesk.market-data.price-watchdog.*)
    private final ObjectProvider<IbGatewayNativeClient> nativeClientProvider;
    private final boolean priceWatchdogEnabled;
    private final long    priceStalenessSeconds;

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
                             DxyMarketService dxyMarketService,
                             ObjectProvider<IbGatewayNativeClient> nativeClientProvider,
                             @Value("${riskdesk.market-data.price-watchdog.enabled:true}") boolean priceWatchdogEnabled,
                             @Value("${riskdesk.market-data.price-watchdog.staleness-seconds:120}") long priceStalenessSeconds) {
        this.marketDataProvider    = marketDataProvider;
        this.positionService       = positionService;
        this.alertService          = alertService;
        this.behaviourAlertService = behaviourAlertService;
        this.candlePort            = candlePort;
        this.contractRegistry      = contractRegistry;
        this.messagingTemplate     = messagingTemplate;
        this.eventPublisher        = eventPublisher;
        this.dxyMarketService      = dxyMarketService;
        this.nativeClientProvider  = nativeClientProvider;
        this.priceWatchdogEnabled  = priceWatchdogEnabled;
        this.priceStalenessSeconds = priceStalenessSeconds;
    }

    @Scheduled(fixedDelayString = "${riskdesk.market-data.poll-interval:5000}",
               initialDelayString = "${riskdesk.market-data.poll-initial-delay:60000}")
    public void pollPrices() {
        Map<Instrument, BigDecimal> prices;
        try {
            prices = marketDataProvider.fetchPrices();
        } catch (Exception e) {
            // Provider-level failure: treat as "no live prices this cycle" (empty map) rather than an
            // early return — every instrument then takes the DB-fallback path and the
            // databaseFallbackActive transition below stays accurate (an early return would strand it
            // stuck-true). The @Scheduled(fixedDelay) task is NOT cancelled regardless (Spring's
            // default error handler suppresses); it retries next tick.
            log.warn("pollPrices: provider fetchPrices() failed, falling back to DB this cycle: {}", e.toString(), e);
            prices = Map.of();
        }
        Instant now = Instant.now();
        boolean usedDatabaseFallback = false;

        // Per-instrument isolation: a DB write, a synchronous event listener, or a candle save that
        // throws for ONE instrument must not skip the remaining instruments or the DXY/VIX tail.
        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            try {
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
            } catch (Exception e) {
                log.warn("pollPrices: skipping {} this cycle: {}", instrument, e.toString(), e);
            }
        }

        try {
            dxyMarketService.refreshSyntheticDxy();
        } catch (Exception e) {
            log.warn("pollPrices: DXY refresh failed: {}", e.toString(), e);
        }

        // VIX continuous futures (CFE) — feeds CrossInstrumentAlertService regime filter.
        // Published as a string-keyed event ("VIX") to avoid adding VIX to the Instrument enum.
        try {
            marketDataProvider.fetchVixPrice().ifPresent(vix ->
                eventPublisher.publishEvent(new MarketPriceUpdated("VIX", vix, now)));
        } catch (Exception e) {
            log.warn("pollPrices: VIX fetch/publish failed: {}", e.toString(), e);
        }

        if (usedDatabaseFallback && !databaseFallbackActive) {
            log.warn("IBKR unavailable: serving last known prices from the database.");
            databaseFallbackActive = true;
        } else if (!usedDatabaseFallback && databaseFallbackActive) {
            log.info("IBKR recovered: live market data polling resumed.");
            databaseFallbackActive = false;
        }
    }

    /**
     * Fast detector for "silent price-feed death": IB Gateway still reports the socket connected
     * (so {@code pollPrices} keeps serving a frozen cached price and never flips to FALLBACK_DB),
     * but no genuine live tick has arrived for {@code priceStalenessSeconds}. Forces a single
     * rate-limited reconnect+resubscribe via {@link IbGatewayNativeClient#forceReconnect(String)},
     * which is the sole owner of teardown (the 300s {@code OrderFlowOrchestrator.checkConnectionHealth}
     * backstop funnels through the same method).
     *
     * <p>Gated on market-open AND outside both CME maintenance windows (16:00–18:00 ET spans the FX
     * and standard halts), with a warmup grace ({@code lastLiveTickAt == null}) so a fresh boot never
     * churns. {@code initialDelay} (180s) sits comfortably past the poll's own 60s warmup.</p>
     */
    @Scheduled(fixedDelayString = "${riskdesk.market-data.price-watchdog.check-interval-ms:30000}",
               initialDelayString = "${riskdesk.market-data.price-watchdog.initial-delay-ms:180000}")
    public void priceFeedFreshnessWatchdog() {
        if (!priceWatchdogEnabled) return;
        IbGatewayNativeClient nativeClient = nativeClientProvider.getIfAvailable();
        if (nativeClient == null) return;  // not in IB_GATEWAY mode — nothing to reconnect
        evaluatePriceFeedFreshness(Instant.now(), nativeClient);
    }

    /**
     * Core of {@link #priceFeedFreshnessWatchdog()}, parameterized on {@code now} and the client so
     * it is deterministically testable across session boundaries (open/closed/maintenance).
     */
    void evaluatePriceFeedFreshness(Instant now, IbGatewayNativeClient nativeClient) {
        if (!TradingSessionResolver.isMarketOpen(now)) return;                      // weekend / fully closed
        if (TradingSessionResolver.isStandardMaintenanceWindow(now)
            || TradingSessionResolver.isFxMaintenanceWindow(now)) return;          // 16:00–18:00 ET halts
        if (!nativeClient.isConnected()) return;                                    // socket down → pollPrices DB-fallback owns it

        Instant last = lastLiveTickAt;
        if (last == null) return;                                                   // never warmed up — don't churn
        long ageSec = Duration.between(last, now).getSeconds();
        if (ageSec <= priceStalenessSeconds) return;

        log.error("Price feed silent {}s while IB Gateway reports connected — forcing reconnect+resubscribe", ageSec);
        nativeClient.forceReconnect("silent price-feed death: no live tick for " + ageSec + "s");
    }

    /** Age of the last genuine live tick (any instrument), or {@code null} if none yet. For health/monitoring. */
    public Duration liveTickAge() {
        Instant last = lastLiveTickAt;
        return last == null ? null : Duration.between(last, Instant.now());
    }

    /** True when the poll loop is currently serving last-known prices from the DB (IBKR unavailable). */
    public boolean isDatabaseFallbackActive() {
        return databaseFallbackActive;
    }

    // -------------------------------------------------------------------------
    // Push-based live price updates (called from IBKR EReader thread)
    // -------------------------------------------------------------------------

    @Override
    public void onLivePriceUpdate(Instrument instrument, BigDecimal price, Instant timestamp) {
        // Liveness: a genuine tick arrived. Stamp BEFORE any debounce/same-price short-circuit so the
        // freshness watchdog sees tick ARRIVAL, not price CHANGE (a quiet-but-alive feed stays fresh).
        lastLiveTickAt = Instant.now();

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
                return newAccumulator(key, instrument, timeframe, contractMonth, periodStart, price);
            }
            if (acc.periodStart.isBefore(periodStart)) {
                closed[0] = acc;
                return newAccumulator(key, instrument, timeframe, contractMonth, periodStart, price);
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

    /**
     * Creates the accumulator for a fresh period, claiming any traded volume that arrived
     * before the period's first price tick. Must be called inside {@code accumulators.compute}
     * for the same key so the claim is serialized with {@link #onLiveVolumeUpdate}.
     */
    private CandleAccumulator newAccumulator(CandleKey key, Instrument instrument, String timeframe,
                                             String contractMonth, Instant periodStart, BigDecimal price) {
        CandleAccumulator acc = new CandleAccumulator(instrument, timeframe, contractMonth, periodStart, price);
        PendingVolume pending = pendingVolume.remove(key);
        if (pending != null && pending.periodStart().equals(periodStart)) {
            acc.addVolume(pending.volume());
        }
        return acc;
    }

    /**
     * Routes a traded-volume increment (contracts, from the exchange's cumulative session
     * counter) into the open bar of every timeframe. Volume for a period whose bar has not
     * opened yet (no price tick) is stashed and claimed at bar creation; volume lagging just
     * past a bar roll is attributed to the open bar so totals are preserved.
     */
    @Override
    public void onLiveVolumeUpdate(Instrument instrument, long volumeDelta, Instant timestamp) {
        if (volumeDelta <= 0) return;
        if (!TradingSessionResolver.isMarketOpen(timestamp, instrument)) return;
        if (isDuringMaintenanceWindow(instrument, timestamp)) return;

        for (String tf : TIMEFRAMES.keySet()) {
            CandleKey key       = new CandleKey(instrument, tf);
            Instant periodStart = truncateToPeriod(timestamp, TIMEFRAMES.get(tf));
            accumulators.compute(key, (k, acc) -> {
                if (acc != null && !acc.periodStart.isBefore(periodStart)) {
                    acc.addVolume(volumeDelta);
                } else {
                    pendingVolume.merge(key, new PendingVolume(periodStart, volumeDelta), (cur, fresh) ->
                        cur.periodStart().equals(periodStart)
                            ? new PendingVolume(periodStart, cur.volume() + fresh.volume())
                            : fresh);
                }
                return acc;
            });
        }
    }

    private record PendingVolume(Instant periodStart, long volume) {}

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
        pendingVolume.keySet().removeIf(key -> key.instrument() == instrument);
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
        /** True traded contracts via {@link #addVolume} — NEVER a count of price updates. */
        long volume = 0;

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
        }

        void addVolume(long delta) {
            volume += delta;
        }

        Candle build() {
            return new Candle(instrument, timeframe, contractMonth, periodStart, open, high, low, close, volume);
        }
    }

    public record StoredPrice(BigDecimal price, Instant timestamp, String source) {}
}

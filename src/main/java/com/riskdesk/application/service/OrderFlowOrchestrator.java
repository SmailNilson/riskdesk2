package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.event.ContractRolloverEvent;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.marketdata.model.SessionCvd;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.AbsorptionDetected;
import com.riskdesk.domain.orderflow.event.BigPrintDetected;
import com.riskdesk.domain.orderflow.event.CvdDivergenceDetected;
import com.riskdesk.domain.orderflow.event.DistributionSetupDetected;
import com.riskdesk.domain.orderflow.event.FlashCrashPhaseChanged;
import com.riskdesk.domain.orderflow.event.IcebergDetected;
import com.riskdesk.domain.orderflow.event.MomentumBurstDetected;
import com.riskdesk.domain.orderflow.event.SmartMoneyCycleDetected;
import com.riskdesk.domain.orderflow.event.SpoofingDetected;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.CvdDivergenceSignal;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.FlashCrashEvaluation;
import com.riskdesk.domain.orderflow.model.FlashCrashInput;
import com.riskdesk.domain.orderflow.model.FlashCrashThresholds;
import com.riskdesk.domain.orderflow.event.FootprintBarClosed;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.model.TickBar;
import com.riskdesk.domain.orderflow.port.TickBarPort;
import com.riskdesk.domain.orderflow.model.IcebergSignal;
import com.riskdesk.domain.orderflow.model.MomentumSignal;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;
import com.riskdesk.domain.orderflow.model.SpoofingSignal;
import com.riskdesk.domain.orderflow.model.WallEvent;
import com.riskdesk.domain.orderflow.port.BigPrintPort;
import com.riskdesk.domain.orderflow.port.FlashCrashConfigPort;
import com.riskdesk.domain.orderflow.port.FootprintPort;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import com.riskdesk.domain.orderflow.service.AbsorptionDetector;
import com.riskdesk.domain.orderflow.service.AggressiveMomentumDetector;
import com.riskdesk.domain.orderflow.service.CvdDivergenceDetector;
import com.riskdesk.domain.orderflow.service.DistributionCycleDetector;
import com.riskdesk.domain.orderflow.service.FlashCrashFSM;
import com.riskdesk.domain.orderflow.service.IcebergDetector;
import com.riskdesk.domain.orderflow.service.InstitutionalDistributionDetector;
import com.riskdesk.domain.orderflow.service.SpoofingDetector;
import com.riskdesk.domain.shared.TradingSessionResolver;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayNativeClient;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayResolvedContract;
import com.riskdesk.infrastructure.marketdata.ibkr.SubscriptionRegistry;
import com.riskdesk.infrastructure.marketdata.ibkr.TickByTickClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the Order Flow subsystem: subscribes tick-by-tick and depth feeds,
 * publishes WebSocket topics, and routes data to the TickLogService for persistence.
 *
 * <p>This service is the single entry point for order flow subscription lifecycle.
 * It waits for IBKR connection + contract resolution before subscribing.</p>
 */
@Service
@ConditionalOnExpression("'${riskdesk.ibkr.enabled:false}' == 'true' and '${riskdesk.ibkr.mode:IB_GATEWAY}' == 'IB_GATEWAY'")
public class OrderFlowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderFlowOrchestrator.class);

    private final IbGatewayNativeClient nativeClient;
    private final IbGatewayContractResolver contractResolver;
    private final OrderFlowProperties properties;
    private final ObjectProvider<TickDataPort> tickDataPortProvider;
    private final SimpMessagingTemplate messagingTemplate;
    private final TickLogService tickLogService;
    private final SubscriptionRegistry subscriptionRegistry;
    private final TickByTickClient tickByTickClient;
    private final ObjectProvider<MarketDepthPort> depthPortProvider;
    private final ObjectProvider<FootprintPort> footprintPortProvider;
    private final ObjectProvider<TickBarPort> tickBarPortProvider;
    private final ObjectProvider<BigPrintPort> bigPrintPortProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final CandleRepositoryPort candleRepository;
    private final FlashCrashConfigPort flashCrashConfig;
    private final AbsorptionDetector absorptionDetector = new AbsorptionDetector();

    /** Stateless wall-event detectors — safe to share a single instance across instruments. */
    private final IcebergDetector icebergDetector = new IcebergDetector();
    private final SpoofingDetector spoofingDetector = new SpoofingDetector();

    /** Transition-style de-dup gates so a re-scanned window doesn't re-emit the same signal. */
    private final RecentSignalGate icebergGate = new RecentSignalGate();
    private final RecentSignalGate spoofingGate = new RecentSignalGate();

    /**
     * Per-(instrument, side) emission gate for absorption events. The 10s
     * absorption window is re-evaluated every 5s, so one 10-15s burst used to
     * be seen by 2-3 overlapping windows, each publishing + persisting its own
     * AbsorptionDetected (no dedup — unlike spoofing/iceberg). See
     * {@link OrderFlowProperties.Absorption#getDedupSeconds()}.
     */
    private final RecentSignalGate absorptionGate = new RecentSignalGate();

    /** Per-instrument stateful flash-crash FSMs (NORMAL→INITIATING→…→REVERSING→NORMAL). */
    private final ConcurrentHashMap<Instrument, FlashCrashFSM> flashCrashFSMs = new ConcurrentHashMap<>();
    /** Previous-cycle velocity per instrument — needed for the FSM acceleration ratio. */
    private final ConcurrentHashMap<Instrument, Double> flashPrevVelocity = new ConcurrentHashMap<>();
    /** Rolling flash-crash window volumes per instrument for the volume-spike baseline. */
    private final ConcurrentHashMap<Instrument, Deque<Long>> flashVolumeHistory = new ConcurrentHashMap<>();
    /** Cached per-instrument thresholds (refreshed every 60s) to avoid a DB hit every 5s. */
    private final ConcurrentHashMap<Instrument, FlashCrashThresholds> flashThresholdsCache = new ConcurrentHashMap<>();

    /** Per-instrument stateful momentum detectors (one per instrument for correct ATR-debounce). */
    private final ConcurrentHashMap<Instrument, AggressiveMomentumDetector> momentumDetectors
        = new ConcurrentHashMap<>();

    /** Per-instrument stateful distribution detectors (streak of same-side absorptions). */
    private final ConcurrentHashMap<Instrument, InstitutionalDistributionDetector> distributionDetectors
        = new ConcurrentHashMap<>();

    /** Per-instrument stateful cycle detectors (chained phases). */
    private final ConcurrentHashMap<Instrument, DistributionCycleDetector> cycleDetectors
        = new ConcurrentHashMap<>();

    /** Cached ATR(14) per instrument from 5m candles — refreshed every 60s. */
    private final ConcurrentHashMap<Instrument, Double> atrCache = new ConcurrentHashMap<>();

    /** Per-instrument stateful CVD divergence detectors (1m bars built from 5s samples). */
    private final ConcurrentHashMap<Instrument, CvdDivergenceDetector> cvdDetectors
        = new ConcurrentHashMap<>();

    /** Rolling speed-of-tape baselines (mean/σ of the 5s and 30s measures over ~30 min). */
    private final ConcurrentHashMap<Instrument, TapeBaseline> tape5Baselines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Instrument, TapeBaseline> tape30Baselines = new ConcurrentHashMap<>();

    /**
     * Rolling history of recent absorption-window total volumes per instrument.
     * Used to compute a meaningful {@code avgVolume} baseline for the absorption score.
     * <p>
     * Previous bug: {@code avgVolume = totalVolume / 2} made the volume component
     * a constant 2.0, breaking the score's volume-spike sensitivity.
     */
    private final ConcurrentHashMap<Instrument, Deque<Long>> volumeHistory = new ConcurrentHashMap<>();

    /**
     * Per-instrument tick-by-tick subscription tracking.
     * <p>
     * Previously a global {@code tickByTickSubscribed} flag marked "all or nothing",
     * which meant a single-instrument drop (e.g. MCL during the 17:00 ET WTI session
     * break) could not be detected or re-subscribed without a full service restart.
     * Now tracked per-instrument so we can evict and re-subscribe individually.
     */
    private final Set<Instrument> subscribedTickByTick = ConcurrentHashMap.newKeySet();
    /** When each instrument was last successfully subscribed (for the 5 min grace period). */
    private final ConcurrentHashMap<Instrument, Long> tickSubscribedAt = new ConcurrentHashMap<>();
    /** Consecutive delta-stale evictions per instrument — escalates when re-subscribe isn't recovering. */
    private final ConcurrentHashMap<Instrument, Integer> deltaStaleStrikes = new ConcurrentHashMap<>();
    /** Last genuine (real-flow) aggregation per instrument — replayed in the staleness heartbeat (L4). */
    private final ConcurrentHashMap<Instrument, TickAggregation> lastGenuineFlow = new ConcurrentHashMap<>();

    /** Per-instrument market-depth subscription tracking. Same rationale as tick-by-tick. */
    private final Set<Instrument> subscribedDepth = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Instrument, Long> depthSubscribedAt = new ConcurrentHashMap<>();
    /** Consecutive stale-eviction count per instrument — escalates when re-subscribe isn't recovering. */
    private final ConcurrentHashMap<Instrument, Integer> depthStaleStrikes = new ConcurrentHashMap<>();

    public OrderFlowOrchestrator(IbGatewayNativeClient nativeClient,
                                  IbGatewayContractResolver contractResolver,
                                  OrderFlowProperties properties,
                                  ObjectProvider<TickDataPort> tickDataPortProvider,
                                  SimpMessagingTemplate messagingTemplate,
                                  TickLogService tickLogService,
                                  SubscriptionRegistry subscriptionRegistry,
                                  TickByTickClient tickByTickClient,
                                  ObjectProvider<MarketDepthPort> depthPortProvider,
                                  ObjectProvider<FootprintPort> footprintPortProvider,
                                  ObjectProvider<TickBarPort> tickBarPortProvider,
                                  ObjectProvider<BigPrintPort> bigPrintPortProvider,
                                  ApplicationEventPublisher eventPublisher,
                                  CandleRepositoryPort candleRepository,
                                  FlashCrashConfigPort flashCrashConfig) {
        this.nativeClient = nativeClient;
        this.contractResolver = contractResolver;
        this.properties = properties;
        this.tickDataPortProvider = tickDataPortProvider;
        this.messagingTemplate = messagingTemplate;
        this.tickLogService = tickLogService;
        this.subscriptionRegistry = subscriptionRegistry;
        this.tickByTickClient = tickByTickClient;
        this.depthPortProvider = depthPortProvider;
        this.footprintPortProvider = footprintPortProvider;
        this.tickBarPortProvider = tickBarPortProvider;
        this.bigPrintPortProvider = bigPrintPortProvider;
        this.eventPublisher = eventPublisher;
        this.candleRepository = candleRepository;
        this.flashCrashConfig = flashCrashConfig;
        // momentumDetectors are created per-instrument in momentumDetectorFor()
    }

    private FlashCrashFSM flashCrashFsmFor(Instrument instrument) {
        return flashCrashFSMs.computeIfAbsent(instrument, i -> new FlashCrashFSM());
    }

    private InstitutionalDistributionDetector distributionDetectorFor(Instrument instrument) {
        return distributionDetectors.computeIfAbsent(instrument, i ->
            new InstitutionalDistributionDetector(
                i,
                properties.getDistribution().getMinConsecutiveCount(),
                properties.getDistribution().getMinAvgScore(),
                Duration.ofMinutes(properties.getDistribution().getWindowTtlMinutes()),
                Duration.ofSeconds(properties.getDistribution().getMaxInterEventGapSeconds()),
                Duration.ofMinutes(properties.getDistribution().getCooldownMinutes())
            ));
    }

    private AggressiveMomentumDetector momentumDetectorFor(Instrument instrument) {
        return momentumDetectors.computeIfAbsent(instrument, i ->
            new AggressiveMomentumDetector(
                properties.getMomentum().getScoreThreshold(),
                properties.getMomentum().getMinPriceMoveFractionOfAtr(),
                properties.getMomentum().getAtrDistanceThreshold(),
                properties.getMomentum().getMaxFiresPerMinute()
            ));
    }

    private DistributionCycleDetector cycleDetectorFor(Instrument instrument) {
        return cycleDetectors.computeIfAbsent(instrument, i ->
            new DistributionCycleDetector(
                i,
                Duration.ofMinutes(properties.getCycle().getMomentumWindowMinutes()),
                Duration.ofMinutes(properties.getCycle().getMirrorWindowMinutes()),
                Duration.ofMinutes(properties.getCycle().getCooldownMinutes())
            ));
    }

    @PostConstruct
    void wireReconnectionSupport() {
        nativeClient.setSubscriptionRegistry(subscriptionRegistry);
        nativeClient.setContractResolver(contractResolver);
        nativeClient.setDepthNumRows(properties.getDepth().getNumRows());
        tickByTickClient.setDisconnectionCallback(() -> {
            log.warn("Order flow: tick-by-tick connection lost — clearing all subscriptions, will reconnect on next scheduled check");
            forgetAllTickState();
        });
        List<String> degraded = properties.getTickByTick().getDegradedInstruments();
        log.info("Order flow: tick-by-tick instruments={} depth instruments={}; degraded (no tick line, delta panels blank)={}",
                 properties.getTickByTick().getInstruments(), properties.getDepth().getInstruments(),
                 degraded == null || degraded.isEmpty() ? "none" : degraded);
    }

    // -------------------------------------------------------------------------
    // Tick-by-tick subscription bootstrap
    // -------------------------------------------------------------------------

    /**
     * Periodically subscribes tick-by-tick for any configured instrument not yet subscribed.
     * <p>
     * Runs every 30 seconds and only touches instruments <b>missing</b> from
     * {@link #subscribedTickByTick}. A single-instrument drop (e.g. MCL during the
     * 17:00 ET WTI session break) is therefore recovered on the next tick without
     * affecting other instruments.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void ensureTickByTickSubscriptions() {
        if (!properties.getTickByTick().isEnabled()) return;

        // Wait for main connection to be up (needed for contract resolution + bid/ask quotes)
        if (!nativeClient.isConnected()) return;

        // Early-out: nothing left to subscribe
        List<String> configured = properties.getTickByTick().getInstruments();
        List<Instrument> missing = missingInstruments(configured, subscribedTickByTick);
        if (missing.isEmpty()) return;

        // Connect the dedicated tick-by-tick EClientSocket (separate clientId)
        if (!tickByTickClient.isConnected()) {
            tickByTickClient.connect();
            if (!tickByTickClient.isConnected()) {
                log.debug("TickByTickClient not yet connected — deferring subscriptions");
                return;
            }
        }

        long now = System.currentTimeMillis();
        for (Instrument instrument : missing) {
            try {
                if (!instrument.isExchangeTradedFuture()) continue;

                Optional<IbGatewayResolvedContract> resolved = contractResolver.resolve(instrument);
                if (resolved.isEmpty()) {
                    log.debug("Contract not yet resolved for {} — tick-by-tick deferred", instrument);
                    continue;
                }

                tickByTickClient.subscribeTickByTick(resolved.get().contract(), instrument);
                subscribedTickByTick.add(instrument);
                tickSubscribedAt.put(instrument, now);
                log.info("Order flow: tick-by-tick subscribed for {} ({}/{})",
                         instrument, subscribedTickByTick.size(), configured.size());
            } catch (Exception e) {
                log.warn("Failed to subscribe tick-by-tick for {}: {}", instrument, e.getMessage());
            }
        }
    }

    /**
     * Helper: resolve configured instrument names, skip unknown ones, and return
     * those not yet present in the given subscribed set.
     */
    private List<Instrument> missingInstruments(List<String> configured, Set<Instrument> subscribed) {
        List<Instrument> missing = new ArrayList<>();
        for (String name : configured) {
            try {
                Instrument inst = Instrument.valueOf(name);
                if (!subscribed.contains(inst)) missing.add(inst);
            } catch (IllegalArgumentException e) {
                // Unknown instrument names are logged once by the subscribe loops above/below
            }
        }
        return missing;
    }

    // -------------------------------------------------------------------------
    // Market depth subscription bootstrap
    // -------------------------------------------------------------------------

    /**
     * Periodically subscribes market depth for any configured instrument not yet subscribed.
     * Runs every 30 seconds — same per-instrument logic as tick-by-tick.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 90_000)
    public void ensureDepthSubscriptions() {
        if (!properties.getDepth().isEnabled()) return;
        if (!nativeClient.isConnected()) return;

        List<String> configured = properties.getDepth().getInstruments();
        List<Instrument> missing = missingInstruments(configured, subscribedDepth);
        if (missing.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (Instrument instrument : missing) {
            try {
                if (!instrument.isExchangeTradedFuture()) continue;

                Optional<IbGatewayResolvedContract> resolved = contractResolver.resolve(instrument);
                if (resolved.isEmpty()) {
                    log.debug("Contract not yet resolved for {} — depth deferred", instrument);
                    continue;
                }

                nativeClient.subscribeDepth(resolved.get().contract(), instrument,
                                             properties.getDepth().getNumRows());
                subscribedDepth.add(instrument);
                depthSubscribedAt.put(instrument, now);
                log.info("Order flow: market depth subscribed for {} ({}/{})",
                         instrument, subscribedDepth.size(), configured.size());
            } catch (Exception e) {
                log.warn("Failed to subscribe depth for {}: {}", instrument, e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Contract rollover — re-point the order-flow feeds at the new contract
    // -------------------------------------------------------------------------

    /**
     * On a confirmed contract rollover, switch this instrument's tick-by-tick and market-depth
     * subscriptions to the new contract.
     * <p>
     * Why this is needed: {@code RolloverDetectionService.confirmRollover()} only refreshes the
     * live-price/quote streams (via {@code IbGatewayContractResolver.refreshToMonth} →
     * {@code IbGatewayNativeClient.cancelInstrumentSubscriptions}). The order-flow tick stream runs
     * on a <b>separate</b> {@link TickByTickClient} socket and market depth is a separate native
     * subscription — neither is touched by that path. The 30s {@link #ensureTickByTickSubscriptions}
     * / {@link #ensureDepthSubscriptions} loops only act on instruments <i>missing</i> from the
     * subscribed sets, so they never re-point an already-subscribed instrument. And the tick
     * watchdog can't rescue it either: the expired contract keeps trading right up to expiry, so the
     * old stream never goes silent. The net effect without this listener is that the TickChart,
     * delta panels, footprint and order book stay pinned to the <b>old</b> contract indefinitely
     * even though the headline price has rolled.
     * <p>
     * {@code RolloverDetectionService} refreshes the resolver cache <i>before</i> publishing this
     * event, so re-resolution here returns the new contract. We also purge all per-instrument flow
     * state so no aggregation window, delta, tick-bar or detector straddles the old→new price gap
     * (which would otherwise fabricate a move the size of the calendar spread).
     */
    @EventListener
    public void onContractRollover(ContractRolloverEvent event) {
        Instrument instrument = event.instrument();
        if (instrument == null || !instrument.isExchangeTradedFuture()) return;

        log.info("Order flow: contract rollover {} {} → {} — re-pointing tick-by-tick + depth feeds",
                 instrument, event.oldContractMonth(), event.newContractMonth());

        // --- Tick-by-tick: cancel the old-contract stream and clear subscription bookkeeping ---
        try {
            tickByTickClient.cancelTickByTick(instrument);
        } catch (Exception e) {
            log.warn("Rollover: failed to cancel tick-by-tick for {}: {}", instrument, e.getMessage());
        }
        evictTickState(instrument);
        // Clear the delta-staleness backoff so the re-subscribe is never locked out by strikes
        // accumulated on the now-cancelled old-contract stream.
        deltaStaleStrikes.remove(instrument);

        // --- Market depth: drop the native subscription and its bookkeeping ---
        try {
            nativeClient.unsubscribeDepth(instrument);
        } catch (Exception e) {
            log.warn("Rollover: failed to unsubscribe depth for {}: {}", instrument, e.getMessage());
        }
        subscribedDepth.remove(instrument);
        depthSubscribedAt.remove(instrument);
        depthStaleStrikes.remove(instrument);

        // --- Purge per-instrument flow state so nothing spans the old→new contract price gap ---
        TickDataPort tickDataPort = tickDataPortProvider.getIfAvailable();
        if (tickDataPort != null) {
            tickDataPort.purgeInstrument(instrument);
        }
        flashCrashFSMs.remove(instrument);
        flashPrevVelocity.remove(instrument);
        flashVolumeHistory.remove(instrument);
        flashThresholdsCache.remove(instrument);
        momentumDetectors.remove(instrument);
        distributionDetectors.remove(instrument);
        cycleDetectors.remove(instrument);
        cvdDetectors.remove(instrument);
        tape5Baselines.remove(instrument);
        tape30Baselines.remove(instrument);
        volumeHistory.remove(instrument);
        atrCache.remove(instrument);

        // --- Re-subscribe immediately on the new contract rather than waiting up to 30s ---
        ensureTickByTickSubscriptions();
        ensureDepthSubscriptions();
    }

    // -------------------------------------------------------------------------
    // Depth freshness watchdog — UC-OF-016
    // -------------------------------------------------------------------------

    /**
     * Detects a silently frozen L2 depth feed (socket alive but no updates flowing —
     * typical of an overloaded TWS) and forces recovery by cancelling and re-subscribing.
     * <p>
     * This is the depth equivalent of {@code TickByTickClient}'s internal watchdog, which
     * already self-heals the tick feed. Depth had no such path: {@link #ensureDepthSubscriptions}
     * only subscribes <i>missing</i> instruments, and {@code subscribeDepth} short-circuits on
     * the still-present native subscription — so a frozen book never recovered. Here we use the
     * book's real {@code timestamp} (see {@code MutableOrderBook}) to detect staleness, then
     * {@link IbGatewayNativeClient#unsubscribeDepth} clears the native subscription so the
     * re-subscribe actually takes effect.
     */
    @Scheduled(fixedDelayString = "${riskdesk.order-flow.freshness.check-interval-ms:15000}", initialDelay = 120_000)
    public void checkDepthFreshness() {
        if (!properties.getFreshness().isEnabled()) return;
        if (!properties.getDepth().isEnabled()) return;
        if (!nativeClient.isConnected()) return;

        MarketDepthPort depthPort = depthPortProvider.getIfAvailable();
        if (depthPort == null) return;

        long now = System.currentTimeMillis();
        long graceMs = properties.getFreshness().getGraceSeconds() * 1000L;
        long staleMs = properties.getFreshness().getDepthStalenessSeconds() * 1000L;
        int maxStrikes = properties.getFreshness().getMaxStrikes();

        boolean evictedAny = false;
        for (Instrument instrument : List.copyOf(subscribedDepth)) {
            Long subscribedAt = depthSubscribedAt.get(instrument);
            if (subscribedAt == null || (now - subscribedAt) < graceMs) continue;

            Optional<DepthMetrics> depth = depthPort.currentDepth(instrument);
            Instant lastData = depth.map(DepthMetrics::timestamp).orElse(null);
            boolean stale = (lastData == null) || (now - lastData.toEpochMilli() > staleMs);

            if (!stale) {
                // Data is flowing again — clear strikes so the watchdog re-arms.
                depthStaleStrikes.remove(instrument);
                continue;
            }

            // Backoff: once re-subscription has failed maxStrikes times in a row, stop churning
            // the subscription. Repeated cancel/re-subscribe won't revive a genuinely frozen TWS,
            // and during an expected no-quote period (e.g. MCL's 17:00 ET session break) it would
            // spam IBKR ~once per grace interval. We leave the existing subscription in place so
            // data resumes automatically when the feed wakes up (which resets strikes above), and
            // the error below has already flagged it for a possible manual restart.
            if (depthStaleStrikes.getOrDefault(instrument, 0) >= maxStrikes) {
                continue;
            }

            String age = lastData == null
                ? "no update since subscribe"
                : (now - lastData.toEpochMilli()) / 1000 + "s since last update";
            log.warn("Depth freshness watchdog: {} frozen ({}) — cancelling + re-subscribing",
                     instrument, age);
            nativeClient.unsubscribeDepth(instrument);
            subscribedDepth.remove(instrument);
            depthSubscribedAt.remove(instrument);
            evictedAny = true;

            int strikes = depthStaleStrikes.merge(instrument, 1, Integer::sum);
            if (strikes >= maxStrikes) {
                log.error("Depth freshness watchdog: {} stale {}x in a row — IB Gateway/TWS may be frozen; "
                        + "re-subscription is not recovering. Backing off; manual restart may be required.", instrument, strikes);
            }
        }

        // Re-subscribe now rather than waiting for the next ensureDepthSubscriptions cycle.
        if (evictedAny) {
            ensureDepthSubscriptions();
        }
    }

    // -------------------------------------------------------------------------
    // WebSocket publication — /topic/order-flow
    // -------------------------------------------------------------------------

    /**
     * Publishes current order flow metrics (delta, cumulative delta, buy ratio)
     * to WebSocket every 5 seconds for each instrument with real tick data.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    public void publishOrderFlowMetrics() {
        TickDataPort tickDataPort = tickDataPortProvider.getIfAvailable();
        if (tickDataPort == null) return;

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            Optional<TickAggregation> agg = tickDataPort.currentAggregation(instrument);
            // Real flow = at least one classified trade in the rolling window. An empty window
            // returns a synthetic snapshot (zero volume, windowEnd == now); every stored tick is
            // BUY/SELL-classified, so buyVolume + sellVolume == 0 uniquely identifies a quiet window.
            boolean hasRealFlow = agg.map(a -> (a.buyVolume() + a.sellVolume()) > 0).orElse(false);

            if (hasRealFlow) {
                TickAggregation a = agg.get();
                // Even with volume still in the 5-min window, a feed can be frozen — flag stale by
                // the last CLASSIFIED tick age, not just by "has flow", so a freeze isn't masked for
                // up to 300s (the window length) just because old ticks linger. When stale, the feed
                // health degrades to STARVED rather than showing a misleading green REAL chip next to
                // the red STALE badge.
                boolean stale = isDeltaStale(instrument, tickDataPort);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("instrument", instrument.name());
                payload.put("delta", a.delta());
                payload.put("cumulativeDelta", a.cumulativeDelta());
                payload.put("buyVolume", a.buyVolume());
                payload.put("sellVolume", a.sellVolume());
                payload.put("buyRatioPct", a.buyRatioPct());
                payload.put("deltaTrend", a.deltaTrend());
                payload.put("divergenceDetected", a.divergenceDetected());
                payload.put("divergenceType", a.divergenceType());

                // Session CVD anchor label — cumulativeDelta above is the session-anchored CVD
                // (RTH anchor inside 09:30-16:00 ET, else Globex-day), not the window delta.
                Optional<SessionCvd> cvd = tickDataPort.sessionCvd(instrument);
                cvd.ifPresent(c -> payload.put("cvdAnchor", c.anchor()));

                // Speed of tape (trade intensity): trailing 5s/30s prints-per-second,
                // z-scored against a rolling ~30-min baseline sampled by this 5s pass.
                if (properties.getTape().isEnabled()) {
                    tickDataPort.tapeSpeed(instrument, 5).ifPresent(t5 -> {
                        TapeBaseline.Stats stats = tape5Baselines
                            .computeIfAbsent(instrument, k -> new TapeBaseline())
                            .recordAndScore(t5.tradesPerSec());
                        payload.put("tapeSpeed5s", round2(t5.tradesPerSec()));
                        payload.put("tapeZ5s", round2(stats.z()));
                        payload.put("tapeRatio5s", round2(stats.ratio()));
                    });
                    tickDataPort.tapeSpeed(instrument, 30).ifPresent(t30 -> {
                        TapeBaseline.Stats stats = tape30Baselines
                            .computeIfAbsent(instrument, k -> new TapeBaseline())
                            .recordAndScore(t30.tradesPerSec());
                        payload.put("tapeSpeed30s", round2(t30.tradesPerSec()));
                        payload.put("tapeZ30s", round2(stats.z()));
                    });
                }

                // Net signed volume of flagged big prints over the last 5 minutes.
                BigPrintPort bigPrintPort = bigPrintPortProvider.getIfAvailable();
                if (bigPrintPort != null && properties.getBigPrint().isEnabled()) {
                    payload.put("bigPrintDelta5m",
                        bigPrintPort.bigPrintDelta5m(instrument, java.time.Instant.now()));
                }

                // CVD divergence detection: feed the per-instrument detector one
                // (price, session CVD) sample per pass; it builds 1m bars internally.
                // Skipped when the feed is stale — a frozen lastPrice would fabricate bars.
                if (!stale && properties.getCvd().isEnabled()
                        && cvd.isPresent() && !Double.isNaN(a.lastPrice())) {
                    evaluateCvdDivergence(instrument, a.lastPrice(), cvd.get(),
                        java.time.Instant.now());
                }

                String feedHealth = stale ? "STARVED" : a.source();
                payload.put("source", feedHealth);
                payload.put("feedHealth", feedHealth);
                payload.put("serverStale", stale);
                // windowEnd is the last real tick's timestamp, so it drives the frontend STALE
                // badge. Distinct from "timestamp" below, which is publish time and always fresh.
                payload.put("dataTimestamp", a.windowEnd() != null ? a.windowEnd().toString() : null);
                payload.put("timestamp", java.time.Instant.now().toString());

                messagingTemplate.convertAndSend("/topic/order-flow", payload);
                lastGenuineFlow.put(instrument, a);
            } else if (!isDegraded(instrument)) {
                // Quiet / empty / dead window for a SUBSCRIBED instrument: emit a server-authoritative
                // heartbeat (serverStale + last genuine window end, never `now`/fabricated) so the STALE
                // badge appears promptly. Degraded-by-design instruments (MGC/E6) are skipped — they
                // never change state, so a 5s OFF frame forever would be pure WS churn.
                publishOrderFlowHeartbeat(instrument, tickDataPort);
            }

            // Detectors below only make sense with an aggregator present; preserve the original
            // skip-when-no-aggregation behaviour (the heartbeat above already covered the empty case).
            if (agg.isEmpty()) continue;

            // Evaluate absorption on a SHORT window (transient detection), not the 5 min snapshot
            int absWindowSec = properties.getAbsorption().getWindowSeconds();
            Optional<TickAggregation> shortAgg = tickDataPort.recentAggregation(instrument, absWindowSec);
            if (shortAgg.isPresent()) {
                evaluateAbsorption(instrument, shortAgg.get(), java.time.Instant.now());
            }

            // Always tick cycle state machine (timeouts must run even on quiet windows).
            // Previously this was inside evaluateAbsorption() but skipped by the
            // {@code avgVolume <= 0} early return — cycles never timed out during quiet periods.
            if (properties.getCycle().isEnabled()) {
                try {
                    cycleDetectorFor(instrument).tick(java.time.Instant.now());
                } catch (Exception e) {
                    log.debug("cycle.tick() failed for {}: {}", instrument, e.toString());
                }
            }

            // Flash-crash FSM: evaluate every cycle (even on quiet windows) so the FSM
            // decays back to NORMAL. Reads its own short tick window + live depth imbalance.
            if (properties.getFlashCrash().isEnabled()) {
                try {
                    evaluateFlashCrash(instrument, tickDataPort, java.time.Instant.now());
                } catch (Exception e) {
                    log.debug("evaluateFlashCrash failed for {}: {}", instrument, e.toString());
                }
            }
        }
    }

    /**
     * Emits a server-authoritative staleness heartbeat on {@code /topic/order-flow} for a quiet,
     * empty or dead window. The server — not the client — is the staleness authority: it sets
     * {@code serverStale=true} and carries {@code feedHealth} plus the last genuine window end as
     * {@code dataTimestamp} (NEVER {@code now}, omitted when no classified tick was ever seen). The
     * last genuine delta is replayed for display but never fabricated; a never-seen feed reports
     * zeros. This preserves the PR-356 intent (surface a frozen feed) without masking it, and fixes
     * the frozen-value-before-badge / stray-tick-clears-badge bugs.
     */
    private void publishOrderFlowHeartbeat(Instrument instrument, TickDataPort tickDataPort) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", instrument.name());

        String feedHealth = feedHealthFor(instrument, tickDataPort);
        TickAggregation last = lastGenuineFlow.get(instrument);
        if (last != null) {
            payload.put("delta", last.delta());
            payload.put("cumulativeDelta", last.cumulativeDelta());
            payload.put("buyVolume", last.buyVolume());
            payload.put("sellVolume", last.sellVolume());
            payload.put("buyRatioPct", last.buyRatioPct());
            payload.put("deltaTrend", last.deltaTrend());
        } else {
            payload.put("delta", 0L);
            payload.put("cumulativeDelta", 0L);
            payload.put("buyVolume", 0L);
            payload.put("sellVolume", 0L);
            payload.put("buyRatioPct", 0.0);
            payload.put("deltaTrend", TickAggregation.TREND_FLAT);
        }
        // source mirrors feedHealth (STARVED / DEGRADED_NOT_SUBSCRIBED here) — never the replayed
        // genuine source, so a consumer keying on `source` can't read a stale window as full REAL,
        // and the WS payload matches the REST /status `source` field for the same state.
        payload.put("source", feedHealth);
        // A DEGRADED_NOT_SUBSCRIBED instrument (MGC/E6, off by design) is OFF, not "frozen" — don't
        // raise the alarming STALE badge for it; the OFF chip already conveys the state.
        boolean degraded = "DEGRADED_NOT_SUBSCRIBED".equals(feedHealth);
        payload.put("serverStale", !degraded);
        payload.put("feedHealth", feedHealth);
        // dataTimestamp = the last genuine classified tick's trade time (NEVER now); omit if none.
        tickDataPort.lastGenuineWindowEnd(instrument)
            .ifPresent(t -> payload.put("dataTimestamp", t.toString()));
        payload.put("timestamp", java.time.Instant.now().toString());

        messagingTemplate.convertAndSend("/topic/order-flow", payload);
    }

    /**
     * Single source of an instrument's feed-health label, shared by the WS heartbeat,
     * {@code /api/order-flow/status} and the live publish branch so the surfaces never diverge:
     * DEGRADED_NOT_SUBSCRIBED (off by design) → the live source when fresh data flows
     * (REAL_TICKS / REAL_TICKS_TICKRULE) → STARVED. A feed counts as STARVED the moment its last
     * <b>classified</b> tick is stale — NOT just when the deque drains — so a frozen feed whose old
     * ticks still linger in the 5-min window is never mislabelled REAL alongside a STALE badge.
     * Uses the non-mutating read so it is safe off the scheduler.
     */
    private String feedHealthFor(Instrument instrument, TickDataPort tickDataPort) {
        if (isDegraded(instrument)) {
            return "DEGRADED_NOT_SUBSCRIBED";
        }
        if (tickDataPort == null || isDeltaStale(instrument, tickDataPort)) {
            return "STARVED";
        }
        return tickDataPort.currentAggregationReadOnly(instrument)
            .map(TickAggregation::source)
            .orElse("STARVED");
    }

    /** Configured to NOT open a tick line (delta panels blank by design). */
    private boolean isDegraded(Instrument instrument) {
        List<String> degraded = properties.getTickByTick().getDegradedInstruments();
        return degraded != null && degraded.contains(instrument.name());
    }

    /** True when the last classified tick is older than the configured delta-staleness threshold. */
    private boolean isDeltaStale(Instrument instrument, TickDataPort tickDataPort) {
        long staleMs = properties.getFreshness().getDeltaStalenessSeconds() * 1000L;
        return tickDataPort.lastClassifiedAt(instrument)
            .map(t -> System.currentTimeMillis() - t.toEpochMilli() > staleMs)
            .orElse(true);
    }

    /**
     * Forget ALL per-instrument tick bookkeeping — used on a full tick-client disconnect/drop.
     * Critically clears {@code deltaStaleStrikes}: otherwise an instrument that reached the strike
     * cap during an outage would carry that cap across the reconnect and the delta watchdog's
     * {@code strikes >= maxStrikes} backoff would lock it out from ever resubscribing again.
     */
    private void forgetAllTickState() {
        subscribedTickByTick.clear();
        tickSubscribedAt.clear();
        deltaStaleStrikes.clear();
        lastGenuineFlow.clear();
    }

    /** Forget one instrument's tick bookkeeping on eviction (keeps its strike count for backoff). */
    private void evictTickState(Instrument instrument) {
        subscribedTickByTick.remove(instrument);
        tickSubscribedAt.remove(instrument);
        // Drop the replayed flow so the heartbeat shows zeros, not a stale (possibly wrong-contract)
        // delta, for an evicted/dead line.
        lastGenuineFlow.remove(instrument);
    }

    /**
     * Builds a live {@link FlashCrashInput} from the short tick window and current depth,
     * advances the per-instrument {@link FlashCrashFSM}, and publishes a
     * {@link FlashCrashPhaseChanged} event <b>only on a phase transition</b> (transition-based,
     * consistent with the alert system). Downstream listeners persist the row and push
     * {@code /topic/flash-crash}.
     */
    void evaluateFlashCrash(Instrument instrument, TickDataPort tickDataPort, Instant now) {
        int windowSec = Math.max(1, properties.getFlashCrash().getWindowSeconds());
        double tickSize = instrument.getTickSize().doubleValue();

        double velocity = 0.0;
        double delta5s = 0.0;
        double volumeSpikeRatio = 1.0;

        Optional<TickAggregation> aggOpt = tickDataPort.recentAggregation(instrument, windowSec);
        if (aggOpt.isPresent()) {
            TickAggregation a = aggOpt.get();
            // Velocity = signed first→last move magnitude in ticks per second (mirrors the
            // |close-open| convention used by the offline FlashCrashSimulationService).
            if (!Double.isNaN(a.firstPrice()) && !Double.isNaN(a.lastPrice()) && tickSize > 0) {
                velocity = Math.abs(a.lastPrice() - a.firstPrice()) / windowSec / tickSize;
            }
            delta5s = a.delta();
            long windowVolume = a.buyVolume() + a.sellVolume();
            double baseline = flashVolumeBaseline(instrument, windowVolume);
            volumeSpikeRatio = baseline > 0 ? windowVolume / baseline : 1.0;
        } else {
            // No tick data this window — record a 0 so old spikes age out of the baseline.
            flashVolumeBaseline(instrument, 0L);
        }

        // Live depth imbalance from the order book; neutral 0.5 when no book (won't meet the
        // "bids fleeing" condition, matching the simulation's neutral default).
        MarketDepthPort depthPort = depthPortProvider.getIfAvailable();
        double depthImbalance = 0.5;
        if (depthPort != null) {
            Optional<DepthMetrics> depth = depthPort.currentDepth(instrument);
            if (depth.isPresent()) {
                depthImbalance = depth.get().depthImbalance();
            }
        }

        double prevVelocity = flashPrevVelocity.getOrDefault(instrument, 0.0);
        double accelerationRatio = prevVelocity > 0 ? velocity / prevVelocity : 1.0;
        flashPrevVelocity.put(instrument, velocity);

        FlashCrashInput input = new FlashCrashInput(
            velocity, delta5s, accelerationRatio, depthImbalance, volumeSpikeRatio, now);

        FlashCrashThresholds thresholds = flashThresholdsCache
            .computeIfAbsent(instrument, this::loadFlashThresholds);

        FlashCrashEvaluation eval = flashCrashFsmFor(instrument).evaluate(input, thresholds);
        if (eval.phaseChanged()) {
            eventPublisher.publishEvent(new FlashCrashPhaseChanged(
                instrument,
                eval.previousPhase(),
                eval.currentPhase(),
                eval.conditionsMet(),
                eval.conditions(),
                eval.reversalScore(),
                now));
        }
    }

    private FlashCrashThresholds loadFlashThresholds(Instrument instrument) {
        try {
            return flashCrashConfig.loadThresholds(instrument).orElseGet(FlashCrashThresholds::defaults);
        } catch (Exception e) {
            return FlashCrashThresholds.defaults();
        }
    }

    /**
     * Returns the volume-spike baseline = mean of the <b>prior</b> windows (excluding the
     * current one), then appends {@code currentWindowVolume} to the rolling history.
     * <p>
     * The current window is excluded from its own baseline so a genuine spike isn't diluted
     * into the average (which would make the {@code > 4×} condition mathematically unreachable
     * with a short history). With no prior history the baseline is the current volume itself,
     * yielding a neutral ratio of 1.0 (a spike can't be judged without a baseline).
     */
    private double flashVolumeBaseline(Instrument instrument, long currentWindowVolume) {
        int maxSize = Math.max(1, properties.getFlashCrash().getVolumeHistorySize());
        Deque<Long> hist = flashVolumeHistory.computeIfAbsent(instrument, k -> new ArrayDeque<>());
        synchronized (hist) {
            double baseline;
            if (hist.isEmpty()) {
                baseline = currentWindowVolume;
            } else {
                long sum = 0;
                for (Long v : hist) sum += v;
                baseline = (double) sum / hist.size();
            }
            hist.addLast(currentWindowVolume);
            while (hist.size() > maxSize) hist.pollFirst();
            return baseline;
        }
    }

    /**
     * Evaluate absorption for an instrument based on current tick aggregation.
     * Publishes AbsorptionDetected domain event when absorption score > 2.0.
     */
    /**
     * Refreshes cached ATR(14) for each instrument from 5m candles.
     * Runs every 60s — absorption evaluation reads from cache to avoid DB hits every 5s.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void refreshAtrCache() {
        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            try {
                List<Candle> candles = candleRepository.findRecentCandles(instrument, "5m", 20);
                BigDecimal atrValue = AtrCalculator.compute(candles, 14);
                if (atrValue != null && atrValue.doubleValue() > 0) {
                    atrCache.put(instrument, atrValue.doubleValue());
                }
            } catch (Exception e) {
                // best-effort — keep stale value in cache
            }
            // Refresh persisted flash-crash thresholds so config edits take effect without restart.
            if (properties.getFlashCrash().isEnabled()) {
                flashThresholdsCache.put(instrument, loadFlashThresholds(instrument));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Book-manipulation detection — Iceberg (UC-OF-014) + Spoofing (UC-OF-005)
    // -------------------------------------------------------------------------

    /**
     * Scans recent wall events for iceberg and spoofing patterns and publishes the
     * corresponding domain events. Runs on a short cadence (default 2s); each pattern
     * is gated by {@link RecentSignalGate} so a still-in-window pattern is emitted once,
     * not on every scan. Downstream listeners persist the row and push the WebSocket topic.
     * <p>
     * This is the live counterpart to {@code evaluateAbsorption} for the L2 book: the
     * {@link IcebergDetector}/{@link SpoofingDetector} were previously never invoked outside
     * tests, so {@code /topic/iceberg} and {@code /topic/spoofing} never emitted.
     */
    @Scheduled(fixedDelayString = "${riskdesk.order-flow.iceberg.eval-interval-ms:2000}", initialDelay = 95_000)
    public void evaluateBookManipulation() {
        evaluateBookManipulation(Instant.now());
    }

    /**
     * Seam for deterministic testing: evaluate book manipulation as of {@code now}
     * instead of reading the wall clock. The public no-arg overload delegates with
     * {@link Instant#now()}. Mirrors {@code evaluateFlashCrash(..., Instant)}, which
     * already injects time — without this, tests that pin wall events to a fixed
     * instant rot the moment real time passes that instant + the lookback window.
     */
    void evaluateBookManipulation(Instant now) {
        boolean icebergEnabled = properties.getIceberg().isEnabled();
        boolean spoofingEnabled = properties.getSpoofing().isEnabled();
        if (!icebergEnabled && !spoofingEnabled) return;

        MarketDepthPort depthPort = depthPortProvider.getIfAvailable();
        if (depthPort == null) return;

        int lookbackSec = Math.max(
            properties.getIceberg().getLookbackSeconds(),
            properties.getSpoofing().getLookbackSeconds());

        for (String instrumentName : properties.getDepth().getInstruments()) {
            Instrument instrument;
            try {
                instrument = Instrument.valueOf(instrumentName);
            } catch (IllegalArgumentException e) {
                continue;
            }
            try {
                List<WallEvent> walls = depthPort.recentWallEvents(instrument, Duration.ofSeconds(lookbackSec));
                if (walls == null || walls.isEmpty()) continue;

                double tickSize = instrument.getTickSize().doubleValue();
                if (tickSize <= 0) continue;

                // One wide fetch (the larger lookback), then narrow per detector so each only
                // sees events within ITS configured window. Otherwise a spoof 30-60s old stays
                // in the list and re-fires once its (shorter) dedup gate expires.
                if (icebergEnabled) {
                    List<WallEvent> icebergWindow =
                        withinLookback(walls, properties.getIceberg().getLookbackSeconds(), now);
                    detectIcebergs(instrument, icebergWindow, tickSize, now);
                }
                if (spoofingEnabled) {
                    List<WallEvent> spoofingWindow =
                        withinLookback(walls, properties.getSpoofing().getLookbackSeconds(), now);
                    detectSpoofs(instrument, spoofingWindow, tickSize,
                                 depthPort.currentDepth(instrument).orElse(null), now);
                }
            } catch (Exception e) {
                log.debug("evaluateBookManipulation failed for {}: {}", instrument, e.toString());
            }
        }
    }

    private void detectIcebergs(Instrument instrument, List<WallEvent> walls, double tickSize, Instant now) {
        List<IcebergSignal> signals = icebergDetector.evaluate(instrument, walls, tickSize, now);
        double minScore = properties.getIceberg().getMinScore();
        int dedupSec = properties.getIceberg().getDedupSeconds();
        for (IcebergSignal s : signals) {
            if (s.icebergScore() < minScore) continue;
            // Dedup in price SPACE (N-tick buckets), not per exact tick — one physical
            // order flickering across adjacent levels must share a single dedup key.
            double bucket = tickSize * Math.max(1, properties.getIceberg().getDedupPriceTicks());
            String key = "ICE|" + s.side() + "|" + Math.round(s.priceLevel() / bucket);
            if (icebergGate.shouldEmit(instrument, key, now, dedupSec)) {
                eventPublisher.publishEvent(new IcebergDetected(instrument, s, now));
            }
        }
    }

    private void detectSpoofs(Instrument instrument, List<WallEvent> walls, double tickSize,
                              DepthMetrics depth, Instant now) {
        if (depth == null) return;
        double currentPrice = midPrice(depth);
        double avgLevelSize = avgLevelSize(depth);
        if (currentPrice <= 0 || avgLevelSize <= 0) return;

        List<SpoofingSignal> signals = spoofingDetector.evaluate(instrument, walls, currentPrice, avgLevelSize, now);
        double minScore = properties.getSpoofing().getMinScore();
        int dedupSec = properties.getSpoofing().getDedupSeconds();
        for (SpoofingSignal s : signals) {
            if (s.spoofScore() < minScore) continue;
            // Dedup in price SPACE (N-tick buckets) — see the iceberg twin above.
            double bucket = tickSize * Math.max(1, properties.getSpoofing().getDedupPriceTicks());
            String key = "SPF|" + s.side() + "|" + Math.round(s.priceLevel() / bucket);
            if (spoofingGate.shouldEmit(instrument, key, now, dedupSec)) {
                eventPublisher.publishEvent(new SpoofingDetected(instrument, s, now));
            }
        }
    }

    /** Sub-window of wall events no older than {@code lookbackSeconds} (the fetched window may be wider). */
    private static List<WallEvent> withinLookback(List<WallEvent> walls, int lookbackSeconds, Instant now) {
        Instant cutoff = now.minusSeconds(lookbackSeconds);
        List<WallEvent> out = new ArrayList<>(walls.size());
        for (WallEvent w : walls) {
            if (!w.timestamp().isBefore(cutoff)) out.add(w);
        }
        return out;
    }

    /** Mid price from the book, falling back to whichever side is available. */
    private static double midPrice(DepthMetrics depth) {
        double bid = depth.bestBid();
        double ask = depth.bestAsk();
        if (bid > 0 && ask > 0) return (bid + ask) / 2.0;
        return bid > 0 ? bid : ask;
    }

    /** Average resting size per book level: total depth / number of populated sides×levels. */
    private double avgLevelSize(DepthMetrics depth) {
        int rows = Math.max(1, properties.getDepth().getNumRows());
        long total = depth.totalBidSize() + depth.totalAskSize();
        return total / (2.0 * rows);
    }

    /**
     * Seam for deterministic testing: {@code now} is injected by the caller
     * (the scheduled loop passes {@link Instant#now()}) so the absorption
     * dedup gate and the RTH/ETH threshold resolution can be pinned to fixed
     * instants in tests — mirrors {@code evaluateBookManipulation(Instant)}.
     */
    void evaluateAbsorption(Instrument instrument, TickAggregation agg, java.time.Instant now) {
        try {
            boolean absorptionEnabled = properties.getAbsorption().isEnabled();
            boolean momentumEnabled = properties.getMomentum().isEnabled();
            if (!absorptionEnabled && !momentumEnabled) return;  // nothing to do

            long totalVolume = agg.buyVolume() + agg.sellVolume();
            if (totalVolume <= 0) {
                // No trades in the short window — record a 0 in history (only relevant if
                // absorption is on) so old spikes age out, then return.
                if (absorptionEnabled) recordWindowVolume(instrument, 0L);
                return;
            }

            double deltaThreshold = resolvedDeltaThreshold(now);
            // Maintain the rolling avgVolume baseline only when absorption is enabled.
            // Momentum doesn't depend on a normalised baseline the same way, so we feed it
            // the current totalVolume as a neutral fallback (its detector handles edge cases).
            double avgVolume = absorptionEnabled
                ? recordAndGetAvgVolume(instrument, totalVolume)
                : (double) totalVolume;
            if (avgVolume <= 0) return;

            // Real price move from tick window high/low — guard against missing data
            if (Double.isNaN(agg.highPrice()) || Double.isNaN(agg.lowPrice())) {
                // Cannot compute either absorption or momentum without price data
                return;
            }
            double priceMovePoints = agg.highPrice() - agg.lowPrice();
            double midPrice = (agg.highPrice() + agg.lowPrice()) / 2.0;

            // True signed price move from first→last trade in the window. Necessary for absorption's
            // 4-quadrant (delta sign × price sign) classification. NaN-safe: fall back to 0 (which
            // makes the absorption detector return NEUTRAL while leaving momentum on its own).
            double signedPriceMovePoints = (Double.isNaN(agg.firstPrice()) || Double.isNaN(agg.lastPrice()))
                ? 0.0
                : agg.lastPrice() - agg.firstPrice();

            // Real ATR from cache (falls back to 1.0 only before first cache refresh)
            double atr = atrCache.getOrDefault(instrument, 1.0);

            // Absorption is only evaluated when its own toggle is on. Momentum is independent
            // and runs in the else branch below regardless of the absorption toggle.
            Optional<AbsorptionSignal> signal = absorptionEnabled
                ? absorptionDetector.evaluate(
                    instrument, agg.delta(), signedPriceMovePoints,
                    totalVolume, atr,
                    deltaThreshold, avgVolume, now)
                : Optional.empty();

            if (signal.isPresent()) {
                AbsorptionSignal s = signal.get();

                // Emission gate (multiply-counting audit fix): the detection window
                // (10s) overlaps the 5s scheduling cadence, so the same physical
                // burst is re-detected by 2-3 successive windows. Suppress a new
                // AbsorptionDetected when the previous one for this (instrument,
                // side) is younger than dedup-seconds (default = window-seconds ⇒
                // only NON-overlapping windows emit). Suppression covers the whole
                // pipeline: persistence, n8 counts, WS display AND the distribution
                // detector feed. A sustained 30s burst still yields 3 events 10s
                // apart (≤ the detector's 20s max gap), so genuine sustained
                // absorption streaks keep firing.
                int dedupSec = properties.getAbsorption().effectiveDedupSeconds();
                String dedupKey = "ABS|" + s.side().name();
                if (!absorptionGate.shouldEmit(instrument, dedupKey, now, dedupSec)) {
                    return;
                }

                eventPublisher.publishEvent(new AbsorptionDetected(instrument, s, now));

                // Display calibration: only events above the per-instrument display score
                // reach the UI topic. Internal consumers (distribution chaining, quant
                // gates, persistence) keep seeing every AbsorptionDetected event.
                if (s.absorptionScore() >= properties.getAbsorption().minDisplayScoreFor(instrument.name())) {
                    Map<String, Object> eventPayload = new LinkedHashMap<>();
                    eventPayload.put("instrument", instrument.name());
                    eventPayload.put("side", s.side().name());
                    eventPayload.put("absorptionType", s.absorptionType() != null ? s.absorptionType().name() : null);
                    eventPayload.put("explanation", s.explanation());
                    eventPayload.put("score", s.absorptionScore());
                    eventPayload.put("delta", s.aggressiveDelta());
                    eventPayload.put("priceMove", priceMovePoints);
                    eventPayload.put("signedPriceMove", signedPriceMovePoints);
                    eventPayload.put("atr", atr);
                    eventPayload.put("timestamp", now.toString());
                    messagingTemplate.convertAndSend("/topic/absorption", eventPayload);
                }

                // Feed the distribution detector (Detector 1)
                if (properties.getDistribution().isEnabled()) {
                    InstitutionalDistributionDetector distDetector = distributionDetectorFor(instrument);
                    Optional<DistributionSignal> dist = distDetector.onAbsorption(s, midPrice, null, now);
                    if (dist.isPresent()) {
                        publishDistributionSignal(instrument, dist.get(), now);

                        // Feed the cycle detector (Detector 3)
                        if (properties.getCycle().isEnabled()) {
                            Optional<SmartMoneyCycleSignal> cycle =
                                cycleDetectorFor(instrument).onDistribution(dist.get(), now);
                            cycle.ifPresent(c -> publishCycleSignal(instrument, c, now));
                        }
                    }
                }
            } else if (momentumEnabled) {
                // Complementary path: absorption silent (or disabled) → check momentum burst (Detector 2)
                Optional<MomentumSignal> momentum = momentumDetectorFor(instrument).evaluate(
                    instrument, agg.delta(), signedPriceMovePoints, priceMovePoints,
                    totalVolume, atr, deltaThreshold, avgVolume, midPrice, now);
                if (momentum.isPresent()) {
                    publishMomentumSignal(instrument, momentum.get(), atr, now);

                    // Feed the cycle detector (Detector 3)
                    if (properties.getCycle().isEnabled()) {
                        Optional<SmartMoneyCycleSignal> cycle =
                            cycleDetectorFor(instrument).onMomentum(momentum.get(), now);
                        cycle.ifPresent(c -> publishCycleSignal(instrument, c, now));
                    }
                }
            }
        } catch (Exception e) {
            // Order flow evaluation is best-effort but log so we can debug stalls
            log.debug("evaluateAbsorption failed for {}: {}", instrument, e.toString());
        }
    }

    /**
     * Session-aware delta threshold for the absorption + momentum detectors.
     * MNQ volume runs 10-20× higher during RTH (09:30-16:00 ET) than overnight,
     * so the single configured baseline is multiplied by
     * {@code eth-threshold-multiplier} (default 0.4) outside RTH. The SAME
     * resolved value feeds both {@code AbsorptionDetector} and
     * {@code AggressiveMomentumDetector} (whose Javadoc baseline already reads
     * "e.g. 100 RTH, 40 ETH"). Session resolution happens here, in the
     * application layer, via {@link TradingSessionResolver} — the domain
     * detectors stay session-unaware and no UTC hour is ever hardcoded.
     */
    private double resolvedDeltaThreshold(java.time.Instant now) {
        double base = properties.getAbsorption().getDeltaThreshold();
        if (!TradingSessionResolver.isRegularTradingHours(now)) {
            base *= properties.getAbsorption().getEthThresholdMultiplier();
        }
        return base;
    }

    /**
     * Append the current-window total volume to the per-instrument history and return
     * the rolling mean. This is the baseline {@code avgVolume} for the absorption score.
     * <p>
     * Replaces the old {@code avgVolume = totalVolume / 2} which made {@code volume / avgVolume}
     * a constant 2.0 regardless of market conditions.
     */
    private double recordAndGetAvgVolume(Instrument instrument, long currentWindowVolume) {
        recordWindowVolume(instrument, currentWindowVolume);
        Deque<Long> hist = volumeHistory.get(instrument);
        synchronized (hist) {
            long sum = 0;
            for (Long v : hist) sum += v;
            return hist.isEmpty() ? currentWindowVolume : (double) sum / hist.size();
        }
    }

    private void recordWindowVolume(Instrument instrument, long vol) {
        int maxSize = Math.max(1, properties.getAbsorption().getVolumeHistorySize());
        Deque<Long> hist = volumeHistory.computeIfAbsent(instrument, k -> new ArrayDeque<>());
        synchronized (hist) {
            hist.addLast(vol);
            while (hist.size() > maxSize) hist.pollFirst();
        }
    }

    private void publishDistributionSignal(Instrument instrument, DistributionSignal ds, java.time.Instant now) {
        eventPublisher.publishEvent(new DistributionSetupDetected(instrument, ds, now));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", instrument.name());
        payload.put("type", ds.type().name());
        payload.put("consecutiveCount", ds.consecutiveCount());
        payload.put("avgScore", ds.avgScore());
        payload.put("totalDurationSeconds", ds.totalDurationSeconds());
        payload.put("priceAtDetection", ds.priceAtDetection());
        payload.put("resistanceLevel", ds.resistanceLevel());
        payload.put("confidenceScore", ds.confidenceScore());
        payload.put("timestamp", now.toString());
        messagingTemplate.convertAndSend("/topic/distribution", payload);
    }

    private void publishMomentumSignal(Instrument instrument, MomentumSignal m, double atr, java.time.Instant now) {
        eventPublisher.publishEvent(new MomentumBurstDetected(instrument, m, now));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", instrument.name());
        payload.put("side", m.side().name());
        payload.put("score", m.momentumScore());
        payload.put("delta", m.aggressiveDelta());
        payload.put("priceMoveTicks", m.priceMoveTicks());
        payload.put("priceMovePoints", m.priceMovePoints());
        payload.put("volume", m.totalVolume());
        payload.put("atr", atr);
        payload.put("timestamp", now.toString());
        messagingTemplate.convertAndSend("/topic/momentum", payload);
    }

    private void publishCycleSignal(Instrument instrument, SmartMoneyCycleSignal c, java.time.Instant now) {
        // Domain event always fires — keeps state machine + DB persistence consistent regardless of confidence.
        eventPublisher.publishEvent(new SmartMoneyCycleDetected(instrument, c, now));

        // /topic/cycle (live UI) is gated by min-confidence. Partial cycles still land in the DB
        // for analytical back-testing but never pollute the panel.
        int minConfidence = properties.getCycle().getMinConfidence();
        if (c.confidence() < minConfidence) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", instrument.name());
        payload.put("cycleType", c.cycleType() != null ? c.cycleType().name() : null);
        payload.put("currentPhase", c.currentPhase().name());
        payload.put("priceAtPhase1", c.priceAtPhase1());
        payload.put("priceAtPhase2", c.priceAtPhase2());
        payload.put("priceAtPhase3", c.priceAtPhase3());
        payload.put("totalPriceMove", c.totalPriceMove());
        payload.put("totalDurationMinutes", c.totalDurationMinutes());
        payload.put("confidence", c.confidence());
        payload.put("startedAt", c.startedAt() != null ? c.startedAt().toString() : null);
        payload.put("completedAt", c.completedAt() != null ? c.completedAt().toString() : null);
        payload.put("timestamp", now.toString());
        messagingTemplate.convertAndSend("/topic/cycle", payload);
    }

    // -------------------------------------------------------------------------
    // CVD divergence (A) + speed of tape (B) + big prints (C)
    // -------------------------------------------------------------------------

    /**
     * Feeds one (price, session CVD) sample into the per-instrument
     * {@link CvdDivergenceDetector} and publishes any confirmed divergence as a
     * {@link CvdDivergenceDetected} domain event + {@code /topic/cvd-divergence}
     * WebSocket payload. Persistence and paper trading hang off the domain event.
     */
    void evaluateCvdDivergence(Instrument instrument, double lastPrice, SessionCvd cvd, Instant now) {
        try {
            CvdDivergenceDetector detector = cvdDetectors.computeIfAbsent(instrument, i ->
                new CvdDivergenceDetector(properties.getCvd().getPivotBars(),
                                          properties.getCvd().getMinCvdSwing()));
            detector.onSample(now, lastPrice, cvd.value(), cvd.anchorStart())
                .ifPresent(s -> publishCvdDivergence(instrument, s, lastPrice, now));
        } catch (Exception e) {
            log.debug("evaluateCvdDivergence failed for {}: {}", instrument, e.toString());
        }
    }

    private void publishCvdDivergence(Instrument instrument, CvdDivergenceSignal s,
                                      double lastPrice, Instant now) {
        eventPublisher.publishEvent(new CvdDivergenceDetected(instrument, s, lastPrice, now));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", instrument.name());
        payload.put("type", s.type());
        payload.put("prevPivotPrice", s.prevPivotPrice());
        payload.put("newPivotPrice", s.newPivotPrice());
        payload.put("prevPivotCvd", s.prevPivotCvd());
        payload.put("newPivotCvd", s.newPivotCvd());
        payload.put("pivotTimestamp", s.pivotTimestamp().toString());
        payload.put("price", lastPrice);
        payload.put("timestamp", now.toString());
        messagingTemplate.convertAndSend("/topic/cvd-divergence", payload);
        log.info("CVD divergence: {} {} price {} -> {} cvd {} -> {}", instrument, s.type(),
                 s.prevPivotPrice(), s.newPivotPrice(), s.prevPivotCvd(), s.newPivotCvd());
    }

    /**
     * Pushes every flagged big print (already rate-limited to 1/sec/instrument by the
     * emitting adapter) to {@code /topic/big-prints}. No persistence.
     */
    @EventListener
    public void onBigPrintDetected(BigPrintDetected event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("instrument", event.instrument().name());
            payload.put("price", event.print().price());
            payload.put("size", event.print().size());
            payload.put("side", event.print().side());
            payload.put("percentile", event.print().percentile());
            payload.put("timestamp", event.print().timestamp().toString());
            messagingTemplate.convertAndSend("/topic/big-prints", payload);
        } catch (Exception e) {
            log.debug("Big print publish failed: {}", e.getMessage());
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Rolling speed-of-tape baseline: ~30 min of samples (one per 5s orchestrator pass).
     * The z-score and burst ratio of a new sample are computed against the PRIOR samples
     * (excluding itself, mirroring {@code flashVolumeBaseline}) so a genuine burst is not
     * diluted into its own baseline. Below {@code MIN_SAMPLES} (≈1 min) z reads 0 and the
     * ratio reads 1 — no burst can be claimed without a baseline. Only touched by the 5s
     * scheduler thread.
     */
    static final class TapeBaseline {
        private static final int MAX_SAMPLES = 360; // 30 min at one sample per 5s
        private static final int MIN_SAMPLES = 12;  // ≥1 min of history before scoring

        private final Deque<Double> samples = new ArrayDeque<>();

        record Stats(double z, double ratio) {}

        Stats recordAndScore(double value) {
            double z = 0.0;
            double ratio = 1.0;
            int n = samples.size();
            if (n >= MIN_SAMPLES) {
                double mean = 0.0;
                for (double s : samples) mean += s;
                mean /= n;
                double var = 0.0;
                for (double s : samples) var += (s - mean) * (s - mean);
                double sd = Math.sqrt(var / n);
                if (sd > 1e-9) {
                    z = (value - mean) / sd;
                }
                if (mean > 1e-9) {
                    ratio = value / mean;
                }
            }
            samples.addLast(value);
            while (samples.size() > MAX_SAMPLES) {
                samples.pollFirst();
            }
            return new Stats(z, ratio);
        }
    }

    // -------------------------------------------------------------------------
    // WebSocket publication — /topic/depth
    // -------------------------------------------------------------------------

    /**
     * Publishes current market depth metrics to WebSocket every 500ms
     * for each instrument with depth data (MNQ, MCL, MGC).
     */
    @Scheduled(fixedDelay = 500, initialDelay = 30_000)
    public void publishDepthMetrics() {
        MarketDepthPort depthPort = depthPortProvider.getIfAvailable();
        if (depthPort == null) return;

        for (String instrumentName : properties.getDepth().getInstruments()) {
            try {
                Instrument instrument = Instrument.valueOf(instrumentName);
                java.util.Optional<DepthMetrics> depth = depthPort.currentDepth(instrument);
                if (depth.isEmpty()) continue;

                DepthMetrics d = depth.get();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("instrument", instrument.name());
                payload.put("totalBidSize", d.totalBidSize());
                payload.put("totalAskSize", d.totalAskSize());
                payload.put("imbalance", d.depthImbalance());
                payload.put("bestBid", d.bestBid());
                payload.put("bestAsk", d.bestAsk());
                payload.put("spread", d.spread());
                payload.put("spreadTicks", d.spreadTicks());
                if (d.bidWall() != null) {
                    payload.put("bidWall", Map.of("price", d.bidWall().price(), "size", d.bidWall().size()));
                }
                payload.put("bids", depthLadderPayload(d.bids()));
                payload.put("asks", depthLadderPayload(d.asks()));
                if (d.askWall() != null) {
                    payload.put("askWall", Map.of("price", d.askWall().price(), "size", d.askWall().size()));
                }
                // d.timestamp() is now the real last-update time (see MutableOrderBook),
                // so it doubles as the staleness signal for the frontend STALE badge.
                String dataTs = d.timestamp() != null ? d.timestamp().toString() : null;
                payload.put("dataTimestamp", dataTs);
                payload.put("timestamp", dataTs != null ? dataTs : java.time.Instant.now().toString());
                // Server-authoritative staleness: the frontend must never render a frozen
                // ladder as live (the 3h "25-pt spread" incident). Threshold = the same
                // freshness config that drives the depth watchdog.
                long ageSec = d.timestamp() != null
                    ? Math.max(0, java.time.Duration.between(d.timestamp(), java.time.Instant.now()).getSeconds())
                    : Long.MAX_VALUE;
                payload.put("ageSeconds", ageSec == Long.MAX_VALUE ? null : ageSec);
                payload.put("serverStale", ageSec > properties.getFreshness().getDepthStalenessSeconds());

                messagingTemplate.convertAndSend("/topic/depth", payload);
            } catch (Exception e) {
                // ignore — instrument may not have depth
            }
        }
    }

    /** Serializes a depth ladder to a list of {price, size, wall} maps for WS/REST payloads. */
    private static List<Map<String, Object>> depthLadderPayload(List<com.riskdesk.domain.orderflow.model.DepthLevel> ladder) {
        if (ladder == null || ladder.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(ladder.size());
        for (var level : ladder) {
            out.add(Map.of("price", level.price(), "size", level.size(), "wall", level.wall()));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // WebSocket publication — /topic/tick-bars
    // -------------------------------------------------------------------------

    /**
     * Publishes the tail of each instrument's tick chart (last completed bar + the
     * in-progress bar) every 2 seconds. The frontend merges by {@code seq}, so a
     * bar that completed between two pushes is delivered by the next one.
     */
    @Scheduled(fixedDelay = 2_000, initialDelay = 25_000)
    public void publishTickBars() {
        TickBarPort tickBarPort = tickBarPortProvider.getIfAvailable();
        if (tickBarPort == null) return;

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            try {
                List<TickBar> tail = tickBarPort.recentBars(instrument, 3);
                if (tail.isEmpty()) continue;

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("instrument", instrument.name());
                payload.put("bars", tail);
                messagingTemplate.convertAndSend("/topic/tick-bars", payload);
            } catch (Exception e) {
                // ignore — instrument may not have tick data
            }
        }
    }

    // -------------------------------------------------------------------------
    // WebSocket publication — /topic/footprint
    // -------------------------------------------------------------------------

    /**
     * Publishes footprint bars to WebSocket every 5 seconds. First sweeps idle bars
     * whose clock window elapsed without a rolling tick — the adapter publishes each
     * as a {@code FootprintBarClosed} event (persistence) and the final bar state is
     * pushed here so the frontend shows the completed bar; then the current
     * in-progress bar of each instrument with tick data is pushed.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 20_000)
    public void publishFootprintData() {
        FootprintPort footprintPort = footprintPortProvider.getIfAvailable();
        if (footprintPort == null) return;

        try {
            // Closed bars reach /topic/footprint via the FootprintBarClosed listener below.
            footprintPort.closeElapsedBars(Instant.now());
        } catch (Exception e) {
            log.debug("Footprint idle-close sweep failed: {}", e.getMessage());
        }

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            try {
                Optional<FootprintBar> bar = footprintPort.currentBar(instrument);
                if (bar.isEmpty()) continue;

                messagingTemplate.convertAndSend("/topic/footprint", bar.get());
            } catch (Exception e) {
                // ignore — instrument may not have footprint data
            }
        }
    }

    /**
     * Pushes every closed footprint bar (tick-driven roll-over or idle sweep) to the
     * frontend so the completed bar's final state is rendered before the next
     * in-progress snapshot replaces it. Persistence is handled separately by
     * {@code OrderFlowEventPersistenceService}.
     */
    @EventListener
    public void onFootprintBarClosed(FootprintBarClosed event) {
        try {
            messagingTemplate.convertAndSend("/topic/footprint", event.bar());
        } catch (Exception e) {
            log.debug("Footprint closed-bar publish failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Connection health check — UC-OF-015
    // -------------------------------------------------------------------------

    /**
     * Periodically checks connection health and triggers resubscription if needed.
     * If the connection is down, logs a warning. If the connection is up but
     * subscriptions were previously active and may have been lost, triggers
     * resubscribeAll() to restore them.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void checkConnectionHealth() {
        if (!nativeClient.isConnected()) {
            log.warn("Connection health check: IBKR not connected — subscriptions may be stale");
            return;
        }

        // Safety net: tick client silently dropped without firing connectionClosed()
        if (!subscribedTickByTick.isEmpty() && !tickByTickClient.isConnected()) {
            log.warn("Connection health check: tick client disconnected without callback — clearing all subscriptions");
            forgetAllTickState();
            return;
        }

        // Per-instrument eviction: any instrument with no real tick data flowing for
        // more than the grace period gets evicted, and ensureTickByTickSubscriptions()
        // will re-subscribe it on the next scheduled tick.
        // This is what catches single-instrument drops like MCL after its 17:00 ET
        // session break, without disturbing instruments whose ticks are flowing normally.
        TickDataPort tickDataPort = tickDataPortProvider.getIfAvailable();
        if (tickDataPort == null) return;

        long now = System.currentTimeMillis();
        long gracePeriodMs = 300_000L;
        List<Instrument> toEvict = new ArrayList<>();
        for (Instrument instrument : subscribedTickByTick) {
            Long subscribedAt = tickSubscribedAt.get(instrument);
            if (subscribedAt == null || (now - subscribedAt) < gracePeriodMs) continue;
            if (!tickDataPort.isRealTickDataAvailable(instrument)) {
                toEvict.add(instrument);
            }
        }
        // This coarse backstop runs only every 300s, so it is its own rate limit — it does NOT
        // consult allowResubscribe (that is a side-effecting token consume whose token the direct
        // ensureTickByTickSubscriptions re-subscribe never spends, which would both waste a token
        // and double-count against the 15s delta watchdog).
        for (Instrument instrument : toEvict) {
            log.warn("Connection health check: no real tick data for {} after 5+ min — evicting for re-subscription",
                     instrument);
            evictTickState(instrument);
        }
    }

    // -------------------------------------------------------------------------
    // Delta freshness watchdog (L3) — the tick equivalent of checkDepthFreshness
    // -------------------------------------------------------------------------

    /**
     * Detects a tick stream that is alive but yields no <b>classified</b> ticks (e.g. a 100%
     * UNCLASSIFIED stream, or a silently dead feed) and resubscribes within ~15s. This closes the
     * 60–300s dead zone left by {@code TickByTickClient}'s raw-arrival watchdog and the 300s
     * connection-health check: both key on raw bytes / hasData, not on classification yield.
     * <p>
     * Single owner of resubscription: every eviction passes through the shared
     * {@link TickByTickClient#allowResubscribe} rate cap, and a per-instrument strike count backs
     * off (and escalates on a competing-session 10197) when re-subscription is not recovering.
     */
    @Scheduled(fixedDelayString = "${riskdesk.order-flow.freshness.check-interval-ms:15000}", initialDelay = 120_000)
    public void checkDeltaFreshness() {
        if (!properties.getFreshness().isEnabled()) return;
        if (!properties.getTickByTick().isEnabled()) return;
        if (!nativeClient.isConnected()) return;

        TickDataPort tickDataPort = tickDataPortProvider.getIfAvailable();
        if (tickDataPort == null) return;

        long now = System.currentTimeMillis();
        long graceMs = properties.getFreshness().getGraceSeconds() * 1000L;
        long staleMs = properties.getFreshness().getDeltaStalenessSeconds() * 1000L;
        int maxStrikes = properties.getFreshness().getMaxStrikes();

        boolean evictedAny = false;
        for (Instrument instrument : List.copyOf(subscribedTickByTick)) {
            Long subscribedAt = tickSubscribedAt.get(instrument);
            if (subscribedAt == null || (now - subscribedAt) < graceMs) continue;

            Instant lastClassified = tickDataPort.lastClassifiedAt(instrument).orElse(null);
            boolean stale = (lastClassified == null) || (now - lastClassified.toEpochMilli() > staleMs);
            if (!stale) {
                deltaStaleStrikes.remove(instrument);
                continue;
            }

            // Error code 200 ("No security definition") means the subscription contract itself is
            // invalid — typically the resolver's provisional synthetic (conId=0) seeded while the
            // gateway was unreachable. The maxStrikes backoff below targets a frozen TWS, where
            // re-subscribing is futile; a bad contract is the opposite case: each eviction goes
            // through resolve(), which retries the real resolution and eventually heals. So a bad
            // contract never backs off permanently (churn stays bounded by allowResubscribe).
            String lastErr = tickByTickClient.lastError(instrument);
            boolean badContract = lastErr != null && lastErr.contains("code 200");

            // Backoff: stop churning once re-subscription has repeatedly failed to revive the feed.
            if (!badContract && deltaStaleStrikes.getOrDefault(instrument, 0) >= maxStrikes) continue;
            // Shared rate cap — bounds reqId churn across all watchdog loops.
            if (!tickByTickClient.allowResubscribe(instrument)) continue;

            String age = lastClassified == null
                ? "never (no classified tick since subscribe)"
                : (now - lastClassified.toEpochMilli()) / 1000 + "s";
            log.warn("Delta freshness watchdog: {} no classified tick for {} — evicting for re-subscription",
                     instrument, age);
            tickByTickClient.cancelTickByTick(instrument);
            evictTickState(instrument);
            evictedAny = true;

            int strikes = deltaStaleStrikes.merge(instrument, 1, Integer::sum);
            if (strikes == maxStrikes) {
                boolean competing = lastErr != null && (lastErr.contains("10197") || lastErr.toLowerCase().contains("competing"));
                String hint;
                if (badContract) {
                    hint = " BAD CONTRACT (code 200): subscription contract not recognized by IBKR — "
                         + "waiting for contract re-resolution (no backoff).";
                } else if (competing) {
                    hint = " COMPETING SESSION (10197): another session holds the tick line — close it or change the tick clientId.";
                } else {
                    hint = " Manual IB Gateway restart may be required.";
                }
                log.error("Delta freshness watchdog: {} stale {}x in a row — re-subscription not recovering.{}",
                          instrument, strikes, hint);
            }
        }

        if (evictedAny) {
            ensureTickByTickSubscriptions();
        }
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    public boolean isTickByTickActive() {
        return !subscribedTickByTick.isEmpty();
    }

    public Map<String, Object> getStatus() {
        TickDataPort tickDataPort = tickDataPortProvider.getIfAvailable();
        Map<String, Object> status = new LinkedHashMap<>();
        int configuredTick = properties.getTickByTick().getInstruments().size();
        status.put("tickByTickSubscribed", subscribedTickByTick.size() == configuredTick && configuredTick > 0);
        status.put("tickByTickSubscribedCount", subscribedTickByTick.size());
        status.put("tickByTickConfiguredCount", configuredTick);
        status.put("depthSubscribedCount", subscribedDepth.size());
        status.put("tickLogBufferSize", tickLogService.getBufferSize());
        status.put("totalTicksLogged", tickLogService.getTotalTicksLogged());
        // Raw count straight off the tick socket — distinguishes "ticks arrive but aren't logged"
        // from "IBKR sends nothing". A 0 here with tickByTickSubscribed=true points at entitlement.
        status.put("totalTicksReceived", tickByTickClient.getTotalTicksReceived());
        // Classified (BUY/SELL) ticks — the gap vs totalTicksReceived = trades dropped as
        // UNCLASSIFIED (no quote/BBO + tick-rule off): the root signature of a dark delta.
        status.put("classifiedTicksReceived", tickDataPort != null ? tickDataPort.classifiedTicksReceived() : 0L);
        status.put("tickConnectionUp", tickByTickClient.isConnected());
        if (tickByTickClient.lastSystemError() != null) {
            status.put("tickSystemError", tickByTickClient.lastSystemError());
        }

        Map<String, Map<String, Object>> instrumentStatus = new LinkedHashMap<>();
        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            Map<String, Object> perInst = new LinkedHashMap<>();
            // Same vocabulary as the WS heartbeat (shared feedHealthFor, non-mutating read):
            // DEGRADED_NOT_SUBSCRIBED (off by design) / REAL_TICKS / REAL_TICKS_TICKRULE / STARVED.
            perInst.put("source", tickDataPort != null
                ? feedHealthFor(instrument, tickDataPort)
                : "UNAVAILABLE");
            perInst.put("tickSubscribed", subscribedTickByTick.contains(instrument));
            perInst.put("depthSubscribed", subscribedDepth.contains(instrument));
            if (tickDataPort != null) {
                tickDataPort.lastClassifiedAt(instrument).ifPresent(t ->
                    perInst.put("classifiedTickAgeSec", (System.currentTimeMillis() - t.toEpochMilli()) / 1000));
            }
            // Why a subscribed instrument is still on CLV: the last IBKR error for its tick stream.
            String tickError = tickByTickClient.lastError(instrument);
            if (tickError != null) {
                perInst.put("lastTickError", tickError);
            }
            instrumentStatus.put(instrument.name(), perInst);
        }
        status.put("instruments", instrumentStatus);
        return status;
    }
}

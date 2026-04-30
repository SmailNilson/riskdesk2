package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.AbsorptionDetected;
import com.riskdesk.domain.orderflow.event.DistributionSetupDetected;
import com.riskdesk.domain.orderflow.event.MomentumBurstDetected;
import com.riskdesk.domain.orderflow.event.SmartMoneyCycleDetected;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.model.MomentumSignal;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;
import com.riskdesk.domain.orderflow.port.FootprintPort;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import com.riskdesk.domain.orderflow.service.AbsorptionDetector;
import com.riskdesk.domain.orderflow.service.AggressiveMomentumDetector;
import com.riskdesk.domain.orderflow.service.DistributionCycleDetector;
import com.riskdesk.domain.orderflow.service.InstitutionalDistributionDetector;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final CandleRepositoryPort candleRepository;
    private final AbsorptionDetector absorptionDetector = new AbsorptionDetector();

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

    /** Per-instrument market-depth subscription tracking. Same rationale as tick-by-tick. */
    private final Set<Instrument> subscribedDepth = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Instrument, Long> depthSubscribedAt = new ConcurrentHashMap<>();

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
                                  ApplicationEventPublisher eventPublisher,
                                  CandleRepositoryPort candleRepository) {
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
        this.eventPublisher = eventPublisher;
        this.candleRepository = candleRepository;
        // momentumDetectors are created per-instrument in momentumDetectorFor()
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
            subscribedTickByTick.clear();
            tickSubscribedAt.clear();
        });
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
            if (agg.isEmpty()) continue;

            TickAggregation a = agg.get();
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
            payload.put("source", a.source());
            payload.put("timestamp", java.time.Instant.now().toString());

            messagingTemplate.convertAndSend("/topic/order-flow", payload);

            // Evaluate absorption on a SHORT window (transient detection), not the 5 min snapshot
            int absWindowSec = properties.getAbsorption().getWindowSeconds();
            Optional<TickAggregation> shortAgg = tickDataPort.recentAggregation(instrument, absWindowSec);
            if (shortAgg.isPresent()) {
                evaluateAbsorption(instrument, shortAgg.get());
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
        }
    }

    private void evaluateAbsorption(Instrument instrument, TickAggregation agg) {
        try {
            if (!properties.getAbsorption().isEnabled()) return;

            long totalVolume = agg.buyVolume() + agg.sellVolume();
            if (totalVolume <= 0) {
                // No trades in the short window — record a 0 in history so old spikes age out,
                // then return. Cycle.tick() is now called outside this method.
                recordWindowVolume(instrument, 0L);
                return;
            }

            double deltaThreshold = properties.getAbsorption().getDeltaThreshold();
            double avgVolume = recordAndGetAvgVolume(instrument, totalVolume);
            if (avgVolume <= 0) return;

            // Real price move from tick window high/low — guard against missing data
            double priceMovePoints;
            double midPrice;
            if (Double.isNaN(agg.highPrice()) || Double.isNaN(agg.lowPrice())) {
                // Cannot compute absorption without price-stability signal
                return;
            }
            priceMovePoints = agg.highPrice() - agg.lowPrice();
            midPrice = (agg.highPrice() + agg.lowPrice()) / 2.0;

            // Signed price move: prefer cumulativeDelta sign over instantaneous delta to avoid
            // signum(0) = 0 wiping out the signal when delta lands exactly at zero.
            long signSource = agg.delta() != 0 ? agg.delta() : agg.cumulativeDelta();
            double signedPriceMovePoints = priceMovePoints * Math.signum((double) signSource);

            // Real ATR from cache (falls back to 1.0 only before first cache refresh)
            double atr = atrCache.getOrDefault(instrument, 1.0);
            java.time.Instant now = java.time.Instant.now();

            Optional<AbsorptionSignal> signal = absorptionDetector.evaluate(
                instrument, agg.delta(), priceMovePoints,
                totalVolume, atr,
                deltaThreshold, avgVolume, now);

            if (signal.isPresent()) {
                AbsorptionSignal s = signal.get();
                eventPublisher.publishEvent(new AbsorptionDetected(instrument, s, now));

                Map<String, Object> eventPayload = new LinkedHashMap<>();
                eventPayload.put("instrument", instrument.name());
                eventPayload.put("side", s.side().name());
                eventPayload.put("score", s.absorptionScore());
                eventPayload.put("delta", s.aggressiveDelta());
                eventPayload.put("priceMove", priceMovePoints);
                eventPayload.put("atr", atr);
                eventPayload.put("timestamp", now.toString());
                messagingTemplate.convertAndSend("/topic/absorption", eventPayload);

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
            } else if (properties.getMomentum().isEnabled()) {
                // Complementary path: absorption silent → check momentum burst (Detector 2)
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
        eventPublisher.publishEvent(new SmartMoneyCycleDetected(instrument, c, now));
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
                if (d.askWall() != null) {
                    payload.put("askWall", Map.of("price", d.askWall().price(), "size", d.askWall().size()));
                }
                payload.put("timestamp", d.timestamp() != null ? d.timestamp().toString() : java.time.Instant.now().toString());

                messagingTemplate.convertAndSend("/topic/depth", payload);
            } catch (Exception e) {
                // ignore — instrument may not have depth
            }
        }
    }

    // -------------------------------------------------------------------------
    // WebSocket publication — /topic/footprint
    // -------------------------------------------------------------------------

    /**
     * Publishes current footprint bar snapshot to WebSocket every 5 seconds
     * for each instrument with real tick data flowing.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 20_000)
    public void publishFootprintData() {
        FootprintPort footprintPort = footprintPortProvider.getIfAvailable();
        if (footprintPort == null) return;

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            try {
                Optional<FootprintBar> bar = footprintPort.currentBar(instrument, "5m");
                if (bar.isEmpty()) continue;

                messagingTemplate.convertAndSend("/topic/footprint", bar.get());
            } catch (Exception e) {
                // ignore — instrument may not have footprint data
            }
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
            subscribedTickByTick.clear();
            tickSubscribedAt.clear();
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
        for (Instrument instrument : toEvict) {
            log.warn("Connection health check: no real tick data for {} after 5+ min — evicting for re-subscription",
                     instrument);
            subscribedTickByTick.remove(instrument);
            tickSubscribedAt.remove(instrument);
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

        Map<String, Map<String, Object>> instrumentStatus = new LinkedHashMap<>();
        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            Map<String, Object> perInst = new LinkedHashMap<>();
            String source = "UNAVAILABLE";
            if (tickDataPort != null && tickDataPort.isRealTickDataAvailable(instrument)) {
                source = "REAL_TICKS";
            } else if (tickDataPort != null) {
                source = "CLV_ESTIMATED";
            }
            perInst.put("source", source);
            perInst.put("tickSubscribed", subscribedTickByTick.contains(instrument));
            perInst.put("depthSubscribed", subscribedDepth.contains(instrument));
            instrumentStatus.put(instrument.name(), perInst);
        }
        status.put("instruments", instrumentStatus);
        return status;
    }
}

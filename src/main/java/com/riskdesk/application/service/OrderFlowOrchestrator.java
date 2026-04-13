package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.AbsorptionDetected;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.port.FootprintPort;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import com.riskdesk.domain.orderflow.service.AbsorptionDetector;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Cached ATR(14) per instrument from 5m candles — refreshed every 60s. */
    private final ConcurrentHashMap<Instrument, Double> atrCache = new ConcurrentHashMap<>();

    private volatile boolean tickByTickSubscribed = false;
    private volatile boolean depthSubscribed = false;
    private volatile long tickByTickSubscribedAt = 0;

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
    }

    @PostConstruct
    void wireReconnectionSupport() {
        nativeClient.setSubscriptionRegistry(subscriptionRegistry);
        nativeClient.setContractResolver(contractResolver);
        nativeClient.setDepthNumRows(properties.getDepth().getNumRows());
    }

    // -------------------------------------------------------------------------
    // Tick-by-tick subscription bootstrap
    // -------------------------------------------------------------------------

    /**
     * Periodically attempts to subscribe tick-by-tick for configured instruments.
     * Runs every 30 seconds until all instruments are subscribed.
     * Contracts must be resolved first (handled by ActiveContractRegistryInitializer).
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void ensureTickByTickSubscriptions() {
        if (!properties.getTickByTick().isEnabled() || tickByTickSubscribed) {
            return;
        }

        // Wait for main connection to be up (needed for contract resolution + bid/ask quotes)
        if (!nativeClient.isConnected()) {
            return;
        }

        // Connect the dedicated tick-by-tick EClientSocket (separate clientId)
        if (!tickByTickClient.isConnected()) {
            tickByTickClient.connect();
            if (!tickByTickClient.isConnected()) {
                log.debug("TickByTickClient not yet connected — deferring subscriptions");
                return;
            }
        }

        int subscribed = 0;
        for (String instrumentName : properties.getTickByTick().getInstruments()) {
            try {
                Instrument instrument = Instrument.valueOf(instrumentName);
                if (!instrument.isExchangeTradedFuture()) continue;

                Optional<IbGatewayResolvedContract> resolved = contractResolver.resolve(instrument);
                if (resolved.isEmpty()) {
                    log.debug("Contract not yet resolved for {} — tick-by-tick deferred", instrument);
                    continue;
                }

                tickByTickClient.subscribeTickByTick(resolved.get().contract(), instrument);
                subscribed++;
            } catch (IllegalArgumentException e) {
                log.warn("Unknown instrument in tick-by-tick config: {}", instrumentName);
            } catch (Exception e) {
                log.warn("Failed to subscribe tick-by-tick for {}: {}", instrumentName, e.getMessage());
            }
        }

        if (subscribed == properties.getTickByTick().getInstruments().size()) {
            tickByTickSubscribed = true;
            tickByTickSubscribedAt = System.currentTimeMillis();
            log.info("Order flow: tick-by-tick subscribed for all {} instruments (via dedicated EClientSocket)", subscribed);
        }
    }

    // -------------------------------------------------------------------------
    // Market depth subscription bootstrap
    // -------------------------------------------------------------------------

    /**
     * Periodically attempts to subscribe depth for configured instruments (MNQ, MCL, MGC).
     * Runs every 30 seconds until all instruments are subscribed.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 90_000)
    public void ensureDepthSubscriptions() {
        if (!properties.getDepth().isEnabled() || depthSubscribed) return;
        if (!nativeClient.isConnected()) return;

        int subscribed = 0;
        for (String instrumentName : properties.getDepth().getInstruments()) {
            try {
                Instrument instrument = Instrument.valueOf(instrumentName);
                if (!instrument.isExchangeTradedFuture()) continue;

                Optional<IbGatewayResolvedContract> resolved = contractResolver.resolve(instrument);
                if (resolved.isEmpty()) {
                    log.debug("Contract not yet resolved for {} — depth deferred", instrument);
                    continue;
                }

                nativeClient.subscribeDepth(resolved.get().contract(), instrument,
                                             properties.getDepth().getNumRows());
                subscribed++;
            } catch (IllegalArgumentException e) {
                log.warn("Unknown instrument in depth config: {}", instrumentName);
            } catch (Exception e) {
                log.warn("Failed to subscribe depth for {}: {}", instrumentName, e.getMessage());
            }
        }

        if (subscribed == properties.getDepth().getInstruments().size()) {
            depthSubscribed = true;
            log.info("Order flow: market depth subscribed for {} instruments", subscribed);
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

            // Evaluate absorption on each cycle (UC-OF-004)
            evaluateAbsorption(instrument, a);
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
            double deltaThreshold = 50;
            double avgVolume = (agg.buyVolume() + agg.sellVolume()) / 2.0;
            if (avgVolume <= 0) return;

            // Real price move from tick window high/low
            double priceMoveTicks = 0;
            if (!Double.isNaN(agg.highPrice()) && !Double.isNaN(agg.lowPrice())) {
                priceMoveTicks = agg.highPrice() - agg.lowPrice();
            }

            // Real ATR from cache (falls back to 1.0 only before first cache refresh)
            double atr = atrCache.getOrDefault(instrument, 1.0);

            java.util.Optional<AbsorptionSignal> signal = absorptionDetector.evaluate(
                instrument, agg.delta(), priceMoveTicks,
                agg.buyVolume() + agg.sellVolume(), atr,
                deltaThreshold, avgVolume, java.time.Instant.now());

            if (signal.isPresent()) {
                AbsorptionSignal s = signal.get();
                eventPublisher.publishEvent(new AbsorptionDetected(instrument, s, java.time.Instant.now()));

                Map<String, Object> eventPayload = new LinkedHashMap<>();
                eventPayload.put("instrument", instrument.name());
                eventPayload.put("side", s.side().name());
                eventPayload.put("score", s.absorptionScore());
                eventPayload.put("delta", s.aggressiveDelta());
                eventPayload.put("priceMove", priceMoveTicks);
                eventPayload.put("atr", atr);
                eventPayload.put("timestamp", java.time.Instant.now().toString());
                messagingTemplate.convertAndSend("/topic/absorption", eventPayload);
            }
        } catch (Exception e) {
            // swallow — absorption evaluation is best-effort
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

        // If tick-by-tick was subscribed but no real tick data is flowing,
        // trigger resubscription — but only if subscriptions are old enough.
        // During low-liquidity periods (Sunday evening, holidays), ticks can be
        // sparse. Aggressive resubscription kills active subscriptions.
        if (tickByTickSubscribed) {
            // Don't re-subscribe within 5 minutes of initial subscription
            long elapsed = System.currentTimeMillis() - tickByTickSubscribedAt;
            if (elapsed < 300_000) {
                return;
            }

            TickDataPort tickDataPort = tickDataPortProvider.getIfAvailable();
            if (tickDataPort == null) return;

            boolean anyDataFlowing = false;
            for (Instrument instrument : Instrument.exchangeTradedFutures()) {
                if (tickDataPort.isRealTickDataAvailable(instrument)) {
                    anyDataFlowing = true;
                    break;
                }
            }

            if (!anyDataFlowing) {
                log.warn("Connection health check: connected but no tick data flowing after 5+ min — triggering resubscribeAll");
                nativeClient.resubscribeAll();
                tickByTickSubscribedAt = System.currentTimeMillis(); // reset cooldown
            }
        }
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    public boolean isTickByTickActive() {
        return tickByTickSubscribed;
    }

    public Map<String, Object> getStatus() {
        TickDataPort tickDataPort = tickDataPortProvider.getIfAvailable();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("tickByTickSubscribed", tickByTickSubscribed);
        status.put("tickLogBufferSize", tickLogService.getBufferSize());
        status.put("totalTicksLogged", tickLogService.getTotalTicksLogged());

        Map<String, String> instrumentStatus = new LinkedHashMap<>();
        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            String source = "UNAVAILABLE";
            if (tickDataPort != null && tickDataPort.isRealTickDataAvailable(instrument)) {
                source = "REAL_TICKS";
            } else if (tickDataPort != null) {
                source = "CLV_ESTIMATED";
            }
            instrumentStatus.put(instrument.name(), source);
        }
        status.put("instruments", instrumentStatus);
        return status;
    }
}

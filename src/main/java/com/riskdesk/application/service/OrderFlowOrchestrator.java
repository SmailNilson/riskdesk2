package com.riskdesk.application.service;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayNativeClient;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayResolvedContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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

    private volatile boolean tickByTickSubscribed = false;
    private volatile boolean depthSubscribed = false;

    public OrderFlowOrchestrator(IbGatewayNativeClient nativeClient,
                                  IbGatewayContractResolver contractResolver,
                                  OrderFlowProperties properties,
                                  ObjectProvider<TickDataPort> tickDataPortProvider,
                                  SimpMessagingTemplate messagingTemplate,
                                  TickLogService tickLogService) {
        this.nativeClient = nativeClient;
        this.contractResolver = contractResolver;
        this.properties = properties;
        this.tickDataPortProvider = tickDataPortProvider;
        this.messagingTemplate = messagingTemplate;
        this.tickLogService = tickLogService;
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

        if (!nativeClient.isConnected()) {
            return;
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

                nativeClient.subscribeTickByTick(resolved.get().contract(), instrument);
                subscribed++;
            } catch (IllegalArgumentException e) {
                log.warn("Unknown instrument in tick-by-tick config: {}", instrumentName);
            } catch (Exception e) {
                log.warn("Failed to subscribe tick-by-tick for {}: {}", instrumentName, e.getMessage());
            }
        }

        if (subscribed == properties.getTickByTick().getInstruments().size()) {
            tickByTickSubscribed = true;
            log.info("Order flow: tick-by-tick subscribed for all {} instruments", subscribed);
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

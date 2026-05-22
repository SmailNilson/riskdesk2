package com.riskdesk.infrastructure.quant.notification;

import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * STOMP adapter for the Quant 7-Gates simulation harness.
 *
 * <p>Broadcasts every open / close / mark-to-market on
 * {@code /topic/quant/simulations} — the frontend
 * {@code Quant7GatesSimulationPanel} subscribes and renders the live trade
 * table. Builds its own JSON-friendly map so the adapter doesn't import the
 * presentation DTO (hexagonal layering: infrastructure must not depend on
 * presentation).
 */
@Component
public class Quant7GatesSimulationWebSocketAdapter implements Quant7GatesSimulationPublisher {

    private static final Logger log = LoggerFactory.getLogger(Quant7GatesSimulationWebSocketAdapter.class);

    private static final String TOPIC = "/topic/quant/simulations";

    private final SimpMessagingTemplate messagingTemplate;

    public Quant7GatesSimulationWebSocketAdapter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publish(Quant7GatesSimulation simulation) {
        try {
            messagingTemplate.convertAndSend(TOPIC, toPayload(simulation));
        } catch (Exception e) {
            log.debug("quant-sim WebSocket publish failed: {}", e.toString());
        }
    }

    private static Map<String, Object> toPayload(Quant7GatesSimulation s) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", s.id());
        root.put("instrument", s.instrument().name());
        root.put("direction", s.direction().name());
        root.put("entryPrice", s.entryPrice());
        root.put("stopLoss", s.stopLoss());
        root.put("takeProfit1", s.takeProfit1());
        root.put("takeProfit2", s.takeProfit2());
        root.put("openedAt", s.openedAt() != null ? s.openedAt().toString() : null);
        root.put("entryReason", s.entryReason());
        root.put("status", s.status().name());
        root.put("exitPrice", s.exitPrice());
        root.put("closedAt", s.closedAt() != null ? s.closedAt().toString() : null);
        root.put("exitReason", s.exitReason());
        root.put("pnlPoints", s.pnlPoints());
        root.put("pnlUsd", s.pnlUsd());
        return root;
    }
}

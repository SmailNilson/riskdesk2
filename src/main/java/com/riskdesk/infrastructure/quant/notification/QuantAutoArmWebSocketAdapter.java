package com.riskdesk.infrastructure.quant.notification;

import com.riskdesk.domain.quant.automation.AutoArmFiredEvent;
import com.riskdesk.domain.quant.automation.AutoArmStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Listens to auto-arm domain events and pushes them to the frontend.
 *
 * <ul>
 *   <li>{@code /topic/quant/auto-arm/{instrument}} — both ARMED and lifecycle
 *       transitions land here so the per-instrument dashboard sees a single
 *       consolidated stream.</li>
 *   <li>{@code /topic/quant/auto-arm} — fan-out broadcast for the
 *       global "armed setups" tray.</li>
 * </ul>
 */
@Component
public class QuantAutoArmWebSocketAdapter {

    private static final Logger log = LoggerFactory.getLogger(QuantAutoArmWebSocketAdapter.class);

    private final SimpMessagingTemplate messagingTemplate;

    public QuantAutoArmWebSocketAdapter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onArmFired(AutoArmFiredEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "ARMED");
        payload.put("instrument", event.instrument().name());
        payload.put("executionId", event.executionId());
        payload.put("direction", event.direction().name());
        payload.put("entry", asPlainString(event.entry()));
        payload.put("stopLoss", asPlainString(event.stopLoss()));
        payload.put("takeProfit1", asPlainString(event.takeProfit1()));
        payload.put("takeProfit2", asPlainString(event.takeProfit2()));
        payload.put("sizePercent", event.sizePercent());
        payload.put("armedAt", event.armedAt() == null ? null : event.armedAt().toString());
        payload.put("expiresAt", event.expiresAt() == null ? null : event.expiresAt().toString());
        payload.put("autoSubmitAt", event.autoSubmitAt() == null ? null : event.autoSubmitAt().toString());
        payload.put("reasoning", event.reasoning());
        sendSafely("/topic/quant/auto-arm/" + event.instrument().name(), payload);
        sendSafely("/topic/quant/auto-arm", payload);
    }

    @EventListener
    public void onStateChanged(AutoArmStateChangedEvent event) {
        // ARMED is already covered by onArmFired with the full plan payload.
        // The lifecycle event still fires for ARMED but we suppress the
        // duplicate publish here so the UI doesn't render two ARMED entries.
        if (event.state() == AutoArmStateChangedEvent.State.ARMED) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", event.state().name());
        payload.put("instrument", event.instrument().name());
        payload.put("executionId", event.executionId());
        payload.put("reason", event.reason());
        payload.put("changedAt", event.changedAt() == null ? null : event.changedAt().toString());
        sendSafely("/topic/quant/auto-arm/" + event.instrument().name(), payload);
        sendSafely("/topic/quant/auto-arm", payload);
    }

    private static String asPlainString(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private void sendSafely(String destination, Object payload) {
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.debug("WebSocket publish failed for {}: {}", destination, e.toString());
        }
    }
}

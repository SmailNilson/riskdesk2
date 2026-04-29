package com.riskdesk.infrastructure.quant.notification;

import com.riskdesk.domain.quant.port.QuantNotificationPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pushes Quant evaluator results onto STOMP topics consumed by the frontend.
 *
 * <ul>
 *   <li>{@code /topic/quant/snapshot/{instrument}} — every scan, full payload</li>
 *   <li>{@code /topic/quant/signals} — fires when a 7/7 setup is confirmed (audio alert)</li>
 *   <li>{@code /topic/quant/setups} — fires when score = 6/7 (early warning)</li>
 * </ul>
 */
@Component
public class QuantWebSocketAdapter implements QuantNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(QuantWebSocketAdapter.class);

    private final SimpMessagingTemplate messagingTemplate;

    public QuantWebSocketAdapter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publishSnapshot(Instrument instrument, QuantSnapshot snapshot) {
        sendSafely("/topic/quant/snapshot/" + instrument.name(), payload(instrument, snapshot, "SNAPSHOT"));
    }

    @Override
    public void publishShortSignal7_7(Instrument instrument, QuantSnapshot snapshot) {
        sendSafely("/topic/quant/signals", payload(instrument, snapshot, "SHORT_7_7"));
    }

    @Override
    public void publishSetupAlert6_7(Instrument instrument, QuantSnapshot snapshot) {
        sendSafely("/topic/quant/setups", payload(instrument, snapshot, "SETUP_6_7"));
    }

    private Map<String, Object> payload(Instrument instrument, QuantSnapshot snapshot, String kind) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", kind);
        root.put("instrument", instrument.name());
        root.put("score", snapshot.score());
        root.put("price", snapshot.price());
        root.put("priceSource", snapshot.priceSource());
        root.put("dayMove", snapshot.dayMove());
        root.put("scanTime", snapshot.scanTime() != null ? snapshot.scanTime().toString() : null);
        root.put("entry", snapshot.suggestedEntry());
        root.put("sl", snapshot.suggestedSL());
        root.put("tp1", snapshot.suggestedTP1());
        root.put("tp2", snapshot.suggestedTP2());
        Map<String, Map<String, Object>> gateMap = new LinkedHashMap<>();
        for (Gate g : Gate.values()) {
            GateResult r = snapshot.gates().get(g);
            if (r == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ok", r.ok());
            entry.put("reason", r.reason());
            gateMap.put(g.name(), entry);
        }
        root.put("gates", gateMap);
        return root;
    }

    private void sendSafely(String destination, Object payload) {
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.debug("WebSocket publish failed for {}: {}", destination, e.toString());
        }
    }
}

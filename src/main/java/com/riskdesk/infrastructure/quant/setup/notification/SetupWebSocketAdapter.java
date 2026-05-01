package com.riskdesk.infrastructure.quant.setup.notification;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupRecommendation;
import com.riskdesk.domain.quant.setup.port.SetupNotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket adapter implementing {@link SetupNotificationPort}.
 * Publishes to {@code /topic/quant/setup-recommendation/{instrument}}.
 *
 * <p>The payload is built inline as a {@link LinkedHashMap} rather than
 * delegating to {@code SetupView} so the adapter does not depend on the
 * presentation layer (ArchUnit hexagonal-architecture rule).</p>
 */
@Component
public class SetupWebSocketAdapter implements SetupNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(SetupWebSocketAdapter.class);
    private static final String TOPIC = "/topic/quant/setup-recommendation/";

    private final SimpMessagingTemplate messagingTemplate;

    public SetupWebSocketAdapter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publish(Instrument instrument, SetupRecommendation r) {
        try {
            Map<String, Object> payload = toPayload(r);
            messagingTemplate.convertAndSend(TOPIC + instrument.name().toLowerCase(), payload);
        } catch (RuntimeException e) {
            log.warn("setup ws publish failed instrument={}: {}", instrument, e.toString());
        }
    }

    private Map<String, Object> toPayload(SetupRecommendation r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", r.id().toString());
        p.put("instrument", r.instrument().name());
        p.put("template", r.template().name());
        p.put("style", r.style().name());
        p.put("phase", r.phase().name());
        p.put("regime", r.regime().name());
        p.put("direction", r.direction().name());
        p.put("finalScore", r.finalScore());
        p.put("entryPrice", r.entryPrice());
        p.put("slPrice", r.slPrice());
        p.put("tp1Price", r.tp1Price());
        p.put("tp2Price", r.tp2Price());
        p.put("rrRatio", r.rrRatio());
        p.put("playbookId", r.playbookId());
        p.put("gateResults", toGateList(r.gateResults()));
        p.put("detectedAt", r.detectedAt().toString());
        p.put("updatedAt", r.updatedAt().toString());
        return p;
    }

    private List<Map<String, Object>> toGateList(List<GateCheckResult> gates) {
        List<Map<String, Object>> out = new ArrayList<>(gates.size());
        for (GateCheckResult g : gates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gateName", g.gateName());
            m.put("passed", g.passed());
            m.put("reason", g.reason());
            out.add(m);
        }
        return out;
    }
}

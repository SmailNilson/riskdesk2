package com.riskdesk.application.service;

import com.riskdesk.domain.orderflow.event.AbsorptionDetected;
import com.riskdesk.domain.orderflow.event.FlashCrashPhaseChanged;
import com.riskdesk.domain.orderflow.event.IcebergDetected;
import com.riskdesk.domain.orderflow.event.SpoofingDetected;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.IcebergSignal;
import com.riskdesk.domain.orderflow.model.SpoofingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Listens for order flow domain events and publishes to WebSocket topics (Phase 5b).
 * Cross-references with SMC zones will be added when zone lookup is wired.
 */
@Service
public class OrderFlowCorrelationService {

    private static final Logger log = LoggerFactory.getLogger(OrderFlowCorrelationService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public OrderFlowCorrelationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onAbsorptionDetected(AbsorptionDetected event) {
        log.info("Absorption detected: {} {} score={} delta={} at {}",
                event.instrument(), event.signal().side(),
                event.signal().absorptionScore(), event.signal().aggressiveDelta(),
                event.timestamp());

        AbsorptionSignal signal = event.signal();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", event.instrument().name());
        payload.put("side", signal.side().name());
        payload.put("absorptionScore", signal.absorptionScore());
        payload.put("aggressiveDelta", signal.aggressiveDelta());
        payload.put("priceMoveTicks", signal.priceMoveTicks());
        payload.put("totalVolume", signal.totalVolume());
        payload.put("timestamp", event.timestamp().toString());

        messagingTemplate.convertAndSend("/topic/absorption", payload);
    }

    @EventListener
    public void onSpoofingDetected(SpoofingDetected event) {
        log.info("Spoofing detected: {} {} level={} size={} crossed={} at {}",
                event.instrument(), event.signal().side(),
                event.signal().priceLevel(), event.signal().wallSize(),
                event.signal().priceCrossed(), event.timestamp());

        SpoofingSignal signal = event.signal();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", event.instrument().name());
        payload.put("side", signal.side().name());
        payload.put("priceLevel", signal.priceLevel());
        payload.put("wallSize", signal.wallSize());
        payload.put("durationSeconds", signal.durationSeconds());
        payload.put("priceCrossed", signal.priceCrossed());
        payload.put("spoofScore", signal.spoofScore());
        payload.put("timestamp", event.timestamp().toString());

        messagingTemplate.convertAndSend("/topic/spoofing", payload);
    }

    @EventListener
    public void onIcebergDetected(IcebergDetected event) {
        log.info("Iceberg detected: {} {} level={} recharges={} score={} at {}",
                event.instrument(), event.signal().side(),
                event.signal().priceLevel(), event.signal().rechargeCount(),
                event.signal().icebergScore(), event.timestamp());

        IcebergSignal signal = event.signal();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", event.instrument().name());
        payload.put("side", signal.side().name());
        payload.put("priceLevel", signal.priceLevel());
        payload.put("rechargeCount", signal.rechargeCount());
        payload.put("avgRechargeSize", signal.avgRechargeSize());
        payload.put("durationSeconds", signal.durationSeconds());
        payload.put("icebergScore", signal.icebergScore());
        payload.put("timestamp", event.timestamp().toString());

        messagingTemplate.convertAndSend("/topic/iceberg", payload);
    }

    @EventListener
    public void onFlashCrashPhaseChanged(FlashCrashPhaseChanged event) {
        log.info("Flash crash phase change: {} {} -> {} conditions={}/5 at {}",
                event.instrument(), event.previousPhase(), event.currentPhase(),
                event.conditionsMet(), event.timestamp());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", event.instrument().name());
        payload.put("previousPhase", event.previousPhase().name());
        payload.put("currentPhase", event.currentPhase().name());
        payload.put("conditionsMet", event.conditionsMet());
        payload.put("conditions", Arrays.toString(event.conditions()));
        payload.put("reversalScore", event.reversalScore());
        payload.put("timestamp", event.timestamp().toString());

        messagingTemplate.convertAndSend("/topic/flash-crash", payload);
    }
}

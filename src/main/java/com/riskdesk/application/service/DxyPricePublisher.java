package com.riskdesk.application.service;

import com.riskdesk.domain.marketdata.model.DxySnapshot;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class DxyPricePublisher {

    private static final Logger log = LoggerFactory.getLogger(DxyPricePublisher.class);

    private final SimpMessagingTemplate messagingTemplate;
    private volatile DxySnapshot lastPublished;

    public DxyPricePublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publishIfChanged(DxySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (lastPublished != null
            && same(lastPublished.dxyValue(), snapshot.dxyValue())
            && safe(lastPublished.source()).equals(safe(snapshot.source()))) {
            return;
        }

        try {
            messagingTemplate.convertAndSend("/topic/prices", Map.of(
                "instrument", Instrument.DXY.name(),
                "displayName", Instrument.DXY.getDisplayName(),
                "price", snapshot.dxyValue(),
                "timestamp", snapshot.timestamp().toString()
            ));
            lastPublished = snapshot;
        } catch (Exception e) {
            log.debug("WebSocket send failed for synthetic DXY: {}", e.getMessage());
        }
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) return true;
        if (left == null || right == null) return false;
        return left.compareTo(right) == 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

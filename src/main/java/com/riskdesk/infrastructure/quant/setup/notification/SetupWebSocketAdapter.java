package com.riskdesk.infrastructure.quant.setup.notification;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.setup.SetupRecommendation;
import com.riskdesk.domain.quant.setup.port.SetupNotificationPort;
import com.riskdesk.presentation.quant.dto.SetupView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket adapter implementing {@link SetupNotificationPort}.
 * Publishes to {@code /topic/quant/setup-recommendation/{instrument}}.
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
    public void publish(Instrument instrument, SetupRecommendation recommendation) {
        try {
            SetupView view = SetupView.from(recommendation);
            messagingTemplate.convertAndSend(TOPIC + instrument.name().toLowerCase(), view);
        } catch (RuntimeException e) {
            log.warn("setup ws publish failed instrument={}: {}", instrument, e.toString());
        }
    }
}

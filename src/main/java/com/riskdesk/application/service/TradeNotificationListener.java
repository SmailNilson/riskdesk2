package com.riskdesk.application.service;

import com.riskdesk.domain.notification.event.TradeValidatedEvent;
import com.riskdesk.domain.notification.port.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link TradeValidatedEvent} and delegates to all registered
 * {@link NotificationPort} adapters (Telegram, future SMS/email…).
 * Runs asynchronously so the Mentor analysis thread is never blocked.
 */
@Component
public class TradeNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(TradeNotificationListener.class);

    private final NotificationPort notificationPort;

    public TradeNotificationListener(NotificationPort notificationPort) {
        this.notificationPort = notificationPort;
    }

    @Async
    @EventListener
    public void onTradeValidated(TradeValidatedEvent event) {
        try {
            notificationPort.sendTradeValidated(event);
        } catch (Exception e) {
            log.error("Notification failed for {} {} — {}", event.instrument(), event.action(), e.getMessage());
        }
    }
}

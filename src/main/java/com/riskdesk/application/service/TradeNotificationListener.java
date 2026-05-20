package com.riskdesk.application.service;

import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.domain.notification.event.TradeBlockedByStrategyGateEvent;
import com.riskdesk.domain.notification.event.TradeValidatedEvent;
import com.riskdesk.domain.notification.event.WtxSignalDetectedEvent;
import com.riskdesk.domain.notification.port.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listens for domain notification events ({@link TradeValidatedEvent} and
 * {@link TradeBlockedByStrategyGateEvent}) and delegates to all registered
 * {@link NotificationPort} adapters (Telegram, future SMS/email…).
 * Runs asynchronously so the Mentor analysis / execution threads are never
 * blocked by a slow notification endpoint.
 */
@Component
public class TradeNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(TradeNotificationListener.class);

    private final NotificationPort notificationPort;
    private final WtxStrategyStatePort wtxStatePort;

    public TradeNotificationListener(NotificationPort notificationPort,
                                     WtxStrategyStatePort wtxStatePort) {
        this.notificationPort = notificationPort;
        this.wtxStatePort = wtxStatePort;
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

    @Async
    @EventListener
    public void onTradeBlockedByGate(TradeBlockedByStrategyGateEvent event) {
        try {
            notificationPort.sendTradeBlockedByGate(event);
        } catch (Exception e) {
            log.error("Gate-block notification failed for {} {} review {} — {}",
                event.instrument(), event.action(), event.reviewId(), e.getMessage());
        }
    }

    @Async
    @EventListener
    public void onWtxSignal(WtxSignalDetectedEvent event) {
        // Per-(instrument, timeframe) Telegram toggle — load the persisted state
        // and skip when the operator has opted out for this panel. State may be
        // missing (very first signal ever for the pair), in which case the
        // default is to notify (matches WtxStrategyState.initial()).
        boolean enabled = wtxStatePort.load(event.instrument(), event.timeframe())
                .map(s -> s.telegramNotificationsEnabled())
                .orElse(true);
        if (!enabled) {
            log.debug("WTX telegram disabled for {} {} — skipping notification",
                event.instrument(), event.timeframe());
            return;
        }
        try {
            notificationPort.sendWtxSignal(event);
        } catch (Exception e) {
            log.error("WTX notification failed for {} {} {} — {}",
                event.instrument(), event.timeframe(), event.signalType(), e.getMessage());
        }
    }
}

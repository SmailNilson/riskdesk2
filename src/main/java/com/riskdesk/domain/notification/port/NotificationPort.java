package com.riskdesk.domain.notification.port;

import com.riskdesk.domain.notification.event.TradeValidatedEvent;

/**
 * Port for external push notifications.
 * Infrastructure adapters (Telegram, SMS, email…) implement this interface.
 */
public interface NotificationPort {

    void sendTradeValidated(TradeValidatedEvent event);
}

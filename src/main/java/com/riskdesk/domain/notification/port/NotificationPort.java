package com.riskdesk.domain.notification.port;

import com.riskdesk.domain.notification.event.TradeBlockedByStrategyGateEvent;
import com.riskdesk.domain.notification.event.TradeValidatedEvent;
import com.riskdesk.domain.notification.event.WtxSignalDetectedEvent;

/**
 * Port for external push notifications.
 * Infrastructure adapters (Telegram, SMS, email…) implement this interface.
 */
public interface NotificationPort {

    void sendTradeValidated(TradeValidatedEvent event);

    /**
     * Fire a notification when the S4 strategy execution gate blocked a trade
     * that the legacy Mentor review had approved. Default no-op so adapters
     * that predate S4 (e.g. hypothetical SMS / email sinks) don't have to
     * update. Telegram overrides with a dedicated red-formatted message.
     */
    default void sendTradeBlockedByGate(TradeBlockedByStrategyGateEvent event) {
        // no-op
    }

    /**
     * Fire a notification for every WTX signal landing in history
     * (COMPRA/VENTA/COMPRA_1/VENTA_1, close/halt). Default no-op so non-Telegram
     * adapters can ignore the channel.
     */
    default void sendWtxSignal(WtxSignalDetectedEvent event) {
        // no-op
    }
}

package com.riskdesk.domain.notification.port;

import com.riskdesk.domain.notification.event.DailyLossCapTrippedEvent;
import com.riskdesk.domain.notification.event.ExecutionReconciledEvent;
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

    /**
     * Fire the <b>divergence alarm</b> when the broker-truth reconciler corrected an execution row to
     * match a flat broker (a phantom position / stuck order was forced terminal). Default no-op so
     * non-Telegram adapters ignore the channel; Telegram overrides with a formatted message. This is the
     * R7 safety net — surfacing divergence within seconds instead of discovering it by losing money.
     */
    default void sendExecutionReconciled(ExecutionReconciledEvent event) {
        // no-op
    }

    /**
     * Fire the <b>daily loss-cap alarm</b> when IBKR realized P&L for the trading day breached the
     * configured threshold and the guard halted new auto-entries. Default no-op; Telegram overrides. This
     * is the P4 hard-stop net — it surfaces a cutoff that would otherwise be discovered by losing more.
     */
    default void sendDailyLossCapTripped(DailyLossCapTrippedEvent event) {
        // no-op
    }
}

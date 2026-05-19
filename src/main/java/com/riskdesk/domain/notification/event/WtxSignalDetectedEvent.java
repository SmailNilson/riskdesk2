package com.riskdesk.domain.notification.event;

import com.riskdesk.domain.engine.strategy.wtx.WtxAction;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignalType;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by {@code WtxStrategyService} every time a WTX signal lands in the
 * history (COMPRA / COMPRA_1 / VENTA / VENTA_1, plus close/halt signals).
 *
 * <p>Carries the raw {@link WtxSignal} so notification adapters can render the
 * full context (action, canTrade flag, WT1 value, enrichment) without re-loading
 * from the database.
 */
public record WtxSignalDetectedEvent(
        String instrument,
        String timeframe,
        WtxSignalType signalType,
        String direction,
        WtxAction action,
        boolean canTrade,
        BigDecimal wt1Value,
        BigDecimal wt2Value,
        BigDecimal price,
        Instant timestamp,
        WtxSignal signal
) implements DomainEvent {

    public static WtxSignalDetectedEvent from(WtxSignal signal, BigDecimal price) {
        return new WtxSignalDetectedEvent(
                signal.instrument(),
                signal.timeframe(),
                signal.signalType(),
                signal.direction(),
                signal.suggestedAction(),
                signal.canTrade(),
                signal.wt1Value(),
                signal.wt2Value(),
                price,
                signal.signalTs() != null ? signal.signalTs() : Instant.now(),
                signal
        );
    }
}

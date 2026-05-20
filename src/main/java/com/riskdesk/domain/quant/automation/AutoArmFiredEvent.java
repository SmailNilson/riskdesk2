package com.riskdesk.domain.quant.automation;

import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event published by the application layer whenever a snapshot
 * triggers an auto-arm. The infrastructure layer turns this into a STOMP
 * message on {@code /topic/quant/auto-arm/{instrument}} so the frontend can
 * render the yellow "ARMED" badge with countdown and Fire / Cancel buttons.
 *
 * <p>This event is fired regardless of whether {@code auto-submit.enabled} is
 * true — the UI must always know about an armed execution, even when the
 * scheduler will not auto-submit it.</p>
 *
 * @param instrument           the instrument being auto-armed
 * @param executionId          {@code TradeExecutionRecord.id} just created
 * @param direction            LONG or SHORT
 * @param entry                limit entry price
 * @param stopLoss             virtual SL
 * @param takeProfit1          primary TP
 * @param takeProfit2          extended TP (nullable)
 * @param sizePercent          position size as fraction of account
 * @param armedAt              when the execution was created
 * @param expiresAt            when the decision auto-expires (cancel + UI hint)
 * @param autoSubmitAt         when the scheduler will auto-submit, or {@code null}
 *                             if {@code auto-submit.enabled=false}
 * @param reasoning            human-readable explanation
 */
public record AutoArmFiredEvent(
    Instrument instrument,
    Long executionId,
    AutoArmDirection direction,
    BigDecimal entry,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    double sizePercent,
    Instant armedAt,
    Instant expiresAt,
    Instant autoSubmitAt,
    String reasoning
) {
}

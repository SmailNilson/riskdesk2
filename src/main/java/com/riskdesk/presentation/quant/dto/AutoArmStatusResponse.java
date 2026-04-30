package com.riskdesk.presentation.quant.dto;

import com.riskdesk.domain.model.TradeExecutionRecord;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-model returned by the auto-arm controller. Flattens the
 * {@link TradeExecutionRecord} fields the UI cares about plus the computed
 * {@code secondsUntilAutoSubmit} countdown.
 *
 * <p>{@code direction} is derived from {@link TradeExecutionRecord#getAction()}
 * ({@code BUY → LONG}, {@code SELL → SHORT}) so the frontend never has to
 * parse strings.</p>
 */
public record AutoArmStatusResponse(
    Long executionId,
    String instrument,
    String direction,
    String action,
    String status,
    String statusReason,
    BigDecimal entry,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    Integer quantity,
    Instant armedAt,
    Instant autoSubmitAt,
    Long secondsUntilAutoSubmit
) {
    public static AutoArmStatusResponse from(TradeExecutionRecord exec, Instant autoSubmitAt, Instant now) {
        Long secondsLeft = null;
        if (autoSubmitAt != null && now != null) {
            secondsLeft = Math.max(0L, java.time.Duration.between(now, autoSubmitAt).getSeconds());
        }
        return new AutoArmStatusResponse(
            exec.getId(),
            exec.getInstrument(),
            "BUY".equals(exec.getAction()) ? "LONG" : "SELL".equals(exec.getAction()) ? "SHORT" : exec.getAction(),
            exec.getAction(),
            exec.getStatus() == null ? null : exec.getStatus().name(),
            exec.getStatusReason(),
            exec.getNormalizedEntryPrice(),
            exec.getVirtualStopLoss(),
            exec.getVirtualTakeProfit(),
            exec.getQuantity(),
            exec.getCreatedAt(),
            autoSubmitAt,
            secondsLeft
        );
    }
}

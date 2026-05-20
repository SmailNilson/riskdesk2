package com.riskdesk.application.dto;

import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Read-model returned by the Active Positions Panel REST endpoint.
 *
 * <p>Flattens the {@link TradeExecutionRecord} fields the UI needs and adds
 * a server-side PnL snapshot computed from the live price reading at the
 * moment the response is built. The frontend recomputes PnL client-side on
 * every {@code /topic/prices} tick (via the existing live-price WS stream),
 * so the values exposed here are the cold-start fallback used when the panel
 * mounts before the first WS price arrives.</p>
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code direction} — derived from {@code action} ({@code BUY → LONG},
 *       {@code SELL → SHORT}). The frontend never parses raw broker strings.</li>
 *   <li>{@code currentPrice} — latest live price snapshot for the instrument
 *       at response-build time (nullable when the gateway has no recent push).</li>
 *   <li>{@code pnlPoints} / {@code pnlDollars} — computed via {@link
 *       Instrument#calculatePnL(BigDecimal, BigDecimal, int, Side)} so the
 *       same tick-value math used by the simulator drives the UI.</li>
 *   <li>{@code closable} — true when the row is in a state the close
 *       endpoint can actually act on (everything except already-terminal).</li>
 * </ul>
 * </p>
 */
public record ActivePositionView(
    Long executionId,
    String instrument,
    String direction,
    String action,
    ExecutionStatus status,
    String statusReason,
    BigDecimal entryPrice,
    BigDecimal currentPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    Integer quantity,
    Instant openedAt,
    BigDecimal pnlPoints,
    BigDecimal pnlDollars,
    BigDecimal pnlPercent,
    ExecutionTriggerSource triggerSource,
    boolean closable
) {

    public static ActivePositionView from(TradeExecutionRecord record, BigDecimal livePrice) {
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
        Instrument instrument = parseInstrument(record.getInstrument());
        String direction = "BUY".equals(record.getAction()) ? "LONG"
            : "SELL".equals(record.getAction()) ? "SHORT"
            : record.getAction();
        Side side = "LONG".equals(direction) ? Side.LONG : Side.SHORT;

        BigDecimal pnlPoints = null;
        BigDecimal pnlDollars = null;
        BigDecimal pnlPercent = null;
        if (livePrice != null && record.getNormalizedEntryPrice() != null && instrument != null && record.getQuantity() != null) {
            BigDecimal diff = livePrice.subtract(record.getNormalizedEntryPrice());
            if (side == Side.SHORT) {
                diff = diff.negate();
            }
            pnlPoints = diff.setScale(4, RoundingMode.HALF_UP);
            pnlDollars = instrument.calculatePnL(record.getNormalizedEntryPrice(), livePrice, record.getQuantity(), side);
            if (record.getNormalizedEntryPrice().signum() != 0) {
                pnlPercent = pnlPoints
                    .divide(record.getNormalizedEntryPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            }
        }

        return new ActivePositionView(
            record.getId(),
            record.getInstrument(),
            direction,
            record.getAction(),
            record.getStatus(),
            record.getStatusReason(),
            record.getNormalizedEntryPrice(),
            livePrice,
            record.getVirtualStopLoss(),
            record.getVirtualTakeProfit(),
            null,
            record.getQuantity(),
            record.getCreatedAt(),
            pnlPoints,
            pnlDollars,
            pnlPercent,
            record.getTriggerSource(),
            isClosable(record.getStatus())
        );
    }

    private static boolean isClosable(ExecutionStatus status) {
        if (status == null) return false;
        return switch (status) {
            case CLOSED, CANCELLED, REJECTED, FAILED -> false;
            default -> true;
        };
    }

    private static Instrument parseInstrument(String name) {
        try {
            return name == null ? null : Instrument.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

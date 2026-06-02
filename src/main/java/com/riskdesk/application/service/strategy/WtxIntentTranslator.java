package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxAction;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;

import java.math.BigDecimal;

/**
 * Pure translation between the WTX strategy vocabulary and the unified execution core — Slice A of the
 * WTX → {@code OrderRouter} migration. No I/O, no Spring.
 *
 * <ul>
 *   <li>{@link #toTradeIntent} maps a {@link WtxAction} to the matching {@link TradeIntent} (OPEN /
 *       REVERSE / CLOSE / FLATTEN with the right {@link Side}).</li>
 *   <li>{@link #toWtxOutcome} maps the router's neutral {@link RoutingOutcome} back to the
 *       strategy-facing {@link WtxRoutingOutcome}, so the bridge can route through the OrderRouter while
 *       still reporting the outcomes the rest of WTX (signal history, UI tooltips) already understands.</li>
 * </ul>
 *
 * <p>Both switches are exhaustive (no {@code default}) so adding a {@code WtxAction} or a
 * {@code RoutingOutcome} forces a deliberate mapping here rather than silently falling through.</p>
 */
public final class WtxIntentTranslator {

    private WtxIntentTranslator() {
    }

    /**
     * Build the {@link TradeIntent} for a WTX action. {@code quantity}/{@code limitPrice}/
     * {@code brokerAccountId} are the values the bridge already derives; the {@code source} lets the same
     * translator serve any WTX-family trigger (WTX_AUTO today).
     */
    public static TradeIntent toTradeIntent(WtxAction action,
                                            String idempotencyKey,
                                            ExecutionTriggerSource source,
                                            Instrument instrument,
                                            String timeframe,
                                            int quantity,
                                            BigDecimal limitPrice,
                                            String brokerAccountId) {
        return switch (action) {
            case OPEN_LONG -> TradeIntent.open(idempotencyKey, source, instrument, timeframe,
                Side.LONG, quantity, limitPrice, brokerAccountId);
            case OPEN_SHORT -> TradeIntent.open(idempotencyKey, source, instrument, timeframe,
                Side.SHORT, quantity, limitPrice, brokerAccountId);
            case REVERSE_TO_LONG -> TradeIntent.reverse(idempotencyKey, source, instrument, timeframe,
                Side.LONG, quantity, limitPrice, brokerAccountId);
            case REVERSE_TO_SHORT -> TradeIntent.reverse(idempotencyKey, source, instrument, timeframe,
                Side.SHORT, quantity, limitPrice, brokerAccountId);
            case CLOSE_LONG -> TradeIntent.close(idempotencyKey, source, instrument, timeframe,
                Side.LONG, quantity, limitPrice, brokerAccountId);
            case CLOSE_SHORT -> TradeIntent.close(idempotencyKey, source, instrument, timeframe,
                Side.SHORT, quantity, limitPrice, brokerAccountId);
            case CLOSE_ALL -> TradeIntent.flatten(idempotencyKey, source, instrument, timeframe,
                quantity, limitPrice, brokerAccountId);
            // NONE is the no-op signal — it never routes. The bridge filters it before translating; reaching
            // here is a caller bug.
            case NONE -> throw new IllegalArgumentException("WtxAction.NONE is not routable — filter it before translating");
        };
    }

    /**
     * Map a router {@link RoutingOutcome} to the strategy-facing {@link WtxRoutingOutcome}. Router-only
     * outcomes (no exact WTX equivalent) collapse to the closest WTX value, documented inline.
     */
    public static WtxRoutingOutcome toWtxOutcome(RoutingOutcome outcome) {
        return switch (outcome) {
            case ROUTED -> WtxRoutingOutcome.ROUTED;
            case ROUTED_FLATTEN_ONLY -> WtxRoutingOutcome.ROUTED_FLATTEN_ONLY;
            case ACK_PENDING -> WtxRoutingOutcome.ACK_PENDING;
            // No paper mode is wired for WTX; a paper "route" is reported as routed.
            case PAPER_ONLY -> WtxRoutingOutcome.ROUTED;
            case SKIPPED_AUTO_OFF -> WtxRoutingOutcome.SKIPPED_AUTO_OFF;
            case SKIPPED_IBKR_DISABLED -> WtxRoutingOutcome.SKIPPED_IBKR_DISABLED;
            // No-account / still-reconciling collapse to "bridge unavailable" — WTX's "can't route right now".
            case SKIPPED_BRIDGE_UNAVAILABLE, SKIPPED_RECONCILING, SKIPPED_NO_ACCOUNT ->
                WtxRoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE;
            case SKIPPED_DUPLICATE -> WtxRoutingOutcome.SKIPPED_DUPLICATE;
            case SKIPPED_NO_PRICE, SKIPPED_STALE_PRICE_SOURCE -> WtxRoutingOutcome.SKIPPED_NO_PRICE;
            case SKIPPED_NO_QTY -> WtxRoutingOutcome.SKIPPED_NO_QTY;
            case SKIPPED_NO_OPEN_ROW -> WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW;
            case SKIPPED_ENTRY_IN_FLIGHT -> WtxRoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT;
            // WTX treats any margin outcome as a skip (never a hard fail) — matches the bridge's existing UX.
            case SKIPPED_INSUFFICIENT_MARGIN, FAILED_INSUFFICIENT_MARGIN ->
                WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN;
            case FAILED_TIMEOUT -> WtxRoutingOutcome.FAILED_TIMEOUT;
            // WTX has no read-only outcome; the kill-switch / TWS Read-Only surfaces as a broker reject.
            case FAILED_BROKER_REJECT, FAILED_READ_ONLY -> WtxRoutingOutcome.FAILED_BROKER_REJECT;
            case FAILED -> WtxRoutingOutcome.FAILED;
        };
    }
}

package com.riskdesk.domain.execution.port;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Slice 3a — IBKR fill tracking sink.
 *
 * <p>Callback port implemented by the application layer so the IBKR infrastructure
 * adapter can push raw broker feedback (execDetails + orderStatus) without depending
 * on application-layer types. Keeps the hexagonal boundary intact.</p>
 *
 * <p>Implementations MUST be non-blocking — they are invoked on the IBKR EReader
 * thread. Heavy work should be offloaded.</p>
 */
public interface ExecutionFillListener {

    /**
     * Report an individual fill (IBKR {@code execDetails} callback).
     *
     * @param orderId       TWS orderId of the parent order
     * @param execId        per-fill idempotence key (IBKR {@code execId})
     * @param orderRef      the order reference we set at submission — equals our {@code executionKey}
     * @param cumQty        cumulative filled quantity after this fill
     * @param avgPrice      volume-weighted average fill price after this fill
     * @param lastFillPrice price of this specific fill
     * @param side          IBKR side code ({@code BOT} / {@code SLD})
     * @param time          timestamp of the fill (UTC)
     */
    void onExecDetails(int orderId,
                       String execId,
                       String orderRef,
                       BigDecimal cumQty,
                       BigDecimal avgPrice,
                       BigDecimal lastFillPrice,
                       String side,
                       Instant time);

    /**
     * Report an order status update (IBKR {@code orderStatus} callback).
     *
     * @param orderId       TWS orderId
     * @param status        IBKR status name (Submitted, PreSubmitted, PartiallyFilled, Filled, Cancelled, …)
     * @param filled        cumulative filled quantity
     * @param remaining     remaining unfilled quantity
     * @param avgFillPrice  volume-weighted average fill price
     * @param lastFillTime  best-effort timestamp for this status (UTC)
     */
    void onOrderStatus(int orderId,
                       String status,
                       BigDecimal filled,
                       BigDecimal remaining,
                       BigDecimal avgFillPrice,
                       Instant lastFillTime);
}

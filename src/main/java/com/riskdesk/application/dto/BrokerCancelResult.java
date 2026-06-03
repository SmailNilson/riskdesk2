package com.riskdesk.application.dto;

/**
 * Outcome of asking the broker to cancel a working order by its broker order id. Mirrors the
 * tri-state caution of {@link BrokerOrderLookup}: a caller MUST distinguish a confirmed cancel /
 * already-gone order (safe to discard the local tracking row and submit a replacement) from an
 * {@code UNAVAILABLE} / {@code FAILED} result (the order may still be live at the broker, so a new
 * order would risk a double fill).
 *
 * <ul>
 *   <li>{@code CANCELLED} — the broker confirmed the order is now cancelled.</li>
 *   <li>{@code NOT_FOUND} — the broker has no such order id (already filled, already cancelled, or
 *       aged out of the order book). Nothing is resting, so a replacement is safe.</li>
 *   <li>{@code ALREADY_INACTIVE} — the order was found but already in a terminal/inactive state
 *       (Cancelled / Inactive / Filled). Nothing is resting, so a replacement is safe.</li>
 *   <li>{@code UNAVAILABLE} — the gateway could not be queried (IBKR disabled / disconnected).
 *       NEVER treat as "gone".</li>
 *   <li>{@code FAILED} — a cancel was attempted but not confirmed (timeout / broker reject). The
 *       order may still be live; do not submit a replacement.</li>
 * </ul>
 */
public enum BrokerCancelResult {
    CANCELLED,
    NOT_FOUND,
    ALREADY_INACTIVE,
    UNAVAILABLE,
    FAILED;

    /** True when no order remains resting at the broker, so a replacement order is safe to submit. */
    public boolean clearedToReplace() {
        return this == CANCELLED || this == NOT_FOUND || this == ALREADY_INACTIVE;
    }
}

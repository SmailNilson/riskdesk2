package com.riskdesk.domain.execution;

/**
 * What a {@link TradeIntent} asks the execution core to do. Strategy-neutral and exit-agnostic:
 * there is no stop-loss / take-profit here — by design, a position is exited by an opposite-side
 * intent ({@link #REVERSE} / {@link #CLOSE}) or a {@link #FLATTEN}, never by a broker-resident stop.
 */
public enum IntentKind {
    /** Open a new position on {@link TradeIntent#side()} (no opposite position expected). */
    OPEN,
    /** Flip: close any opposite position and open on {@link TradeIntent#side()} — two 1:1 legs. */
    REVERSE,
    /** Reduce / close the position on {@link TradeIntent#side()}. */
    CLOSE,
    /** Flatten the instrument regardless of current side (force-close: daily-loss cap, session end). */
    FLATTEN,
    /**
     * PARTIAL close (scale-out): reduce the open position on {@link TradeIntent#side()} by
     * {@link TradeIntent#quantity()} contracts and KEEP the remainder live (the row stays ACTIVE with
     * its quantity decremented). Unlike {@link #CLOSE}, which exits the whole row, REDUCE leaves a
     * position behind when {@code quantity} is less than the current size.
     */
    REDUCE
}

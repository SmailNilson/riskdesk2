package com.riskdesk.domain.engine.strategy.wtxrsi;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the WTX+RSI finite-state machine decided to do on one closed bar.
 *
 * <p>This is the {@code Command} half of the Reducer + Command pattern: a
 * {@link WtxRsiDecision} is pure data — it <i>describes</i> an action without
 * performing any I/O. {@link WtxRsiTransition#reduce} emits these; the live
 * orchestrator (and the backtest engine) interpret them: persist a history
 * record, route to IBKR, publish WebSocket payloads.
 *
 * <p>The interface is {@code sealed} so every interpreter's {@code switch} is
 * exhaustive — adding a new decision type forces the compiler to flag every
 * site that must handle it. That guarantee is the whole point: there is exactly
 * one place that decides (this FSM) and the compiler proves nothing is missed
 * downstream.
 *
 * <p><b>Why the state snapshots?</b> Closing a position routes to the broker
 * with the state that <i>still holds</i> the position (qty, SL/TP), but the
 * reducer has already transitioned the running state to FLAT by the time it
 * returns. So {@link Close} and {@link Open} carry the exact state snapshot the
 * router needs, keeping {@code reduce} pure while letting the interpreter
 * reproduce the legacy routing semantics byte-for-byte.
 */
public sealed interface WtxRsiDecision {

    /** Why a {@link Close} fired — drives logging and the audit reason string. */
    enum CloseCause { STOP_LOSS, TAKE_PROFIT, REVERSAL, BIAS_FLIP }

    /**
     * Open a fresh position. {@code stateForRouting} is the state at open time
     * (FLAT, after any reverse-close on the same bar) — passed to the broker
     * bridge for instrument/timeframe/auto-execution context.
     */
    record Open(
            WtxRsiSignal signal,
            WtxRsiRiskPlan plan,
            WtxRsiStrategyState stateForRouting
    ) implements WtxRsiDecision {}

    /**
     * Flatten the open position. {@code stateBeforeClose} still carries the
     * position (qty, SL, TP) for routing + record building. {@code signal} is
     * non-null only for {@link CloseCause#REVERSAL} (its indicator fields are
     * copied onto the close record, mirroring the legacy behaviour).
     * {@code reasonOverride} replaces the routing message when set (used by
     * {@link CloseCause#BIAS_FLIP}); when null the interpreter uses the routing
     * outcome's own error message.
     */
    record Close(
            WtxRsiStrategyState stateBeforeClose,
            Instant timestamp,
            BigDecimal exitPrice,
            BigDecimal realizedPnl,
            CloseCause cause,
            WtxRsiSignal signal,
            String reasonOverride
    ) implements WtxRsiDecision {}

    /**
     * Signal observed but not traded — persisted as an {@code Action.NONE}
     * history row carrying the signal's indicator snapshot. Covers same-side
     * (position already open) and swing-bias contradiction.
     */
    record Suppress(
            WtxRsiSignal signal,
            String reason
    ) implements WtxRsiDecision {}

    /**
     * Entry refused by the chaikin-required gate — persisted as an
     * {@code Action.NONE} history row with the gate's reason.
     */
    record Block(
            WtxRsiSignal signal,
            String reason
    ) implements WtxRsiDecision {}

    /**
     * Signal could not produce a valid risk plan (no confirmed fractal in
     * range, or SL on the wrong side of entry). Mirrors the legacy live engine,
     * which <b>logs only</b> and persists nothing for this case — the
     * interpreter must not write a history row for a {@code Reject}.
     */
    record Reject(
            WtxRsiSignal signal,
            String reason
    ) implements WtxRsiDecision {}
}

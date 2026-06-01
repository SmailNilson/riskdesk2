package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The WTX+RSI finite-state machine as a <b>pure transition function</b>
 * (the {@code Reducer} half of Reducer + Command).
 *
 * <pre>{@code (state, bar, candles, signal?, bias, config) -> (newState, [decisions])}</pre>
 *
 * <p>No Spring, no JPA, no IBKR, no logging, no WebSocket — only inputs to
 * outputs. The live orchestrator ({@code WtxRsiStrategyService}) and the
 * {@code WtxRsiBacktestEngine} both call this single function, which is what
 * guarantees they can never silently diverge. The swing-bias is resolved
 * <i>upstream</i> (it may consult the application-layer SMC engine) and passed
 * in as a value, preserving the purity of this class.
 *
 * <p>The transition order mirrors the legacy {@code onCandleClosed} exactly:
 * <ol>
 *   <li>protective exit (SL/TP) on the just-closed bar — pessimistic: SL wins;</li>
 *   <li>snapshot the resolved bias onto the state;</li>
 *   <li>swing-bias force-close of an open position pointing against the bias;</li>
 *   <li>signal handling: bias-suppress → reverse / same-side-suppress →
 *       chaikin gate → risk plan → open.</li>
 * </ol>
 *
 * <p>Fill price is the close of the signal bar ({@code signal.close()}), the
 * single canonical model shared by live and backtest.
 */
public final class WtxRsiTransition {

    private WtxRsiTransition() {}

    /** Outcome of one bar: the next state plus the ordered decisions to interpret. */
    public record Result(WtxRsiStrategyState newState, List<WtxRsiDecision> decisions) {}

    public static Result reduce(
            WtxRsiStrategyState state,
            Candle bar,
            List<Candle> candles,
            Optional<WtxRsiSignal> signal,
            WtxRsiSwingBias bias,
            WtxRsiConfig config) {

        List<WtxRsiDecision> decisions = new ArrayList<>();

        // ── 1) Protective exit on the just-closed bar (SL/TP, pessimistic) ──
        if (state.currentPosition() != WtxRsiPosition.FLAT) {
            Optional<WtxRsiDecision.Close> exit = protectiveExit(state, bar, config);
            if (exit.isPresent()) {
                decisions.add(exit.get());
                state = state.withFlat(exit.get().realizedPnl());
            }
        }

        // ── 2) Snapshot the resolved bias (UI + filter) ──
        state = state.withLastSwingBias(bias);

        // ── 3) Swing-bias force-close of an open position against the bias ──
        if (state.swingBiasFilterEnabled() && state.currentPosition() != WtxRsiPosition.FLAT) {
            WtxRsiSwingBiasFilter.Decision openSide =
                    WtxRsiSwingBiasFilter.evaluate(null, state.currentPosition(), bias);
            if (openSide == WtxRsiSwingBiasFilter.Decision.FORCE_CLOSE_LONG
                    || openSide == WtxRsiSwingBiasFilter.Decision.FORCE_CLOSE_SHORT) {
                BigDecimal exitPrice = bar.getClose();
                BigDecimal realized = realizedPnl(state, exitPrice, config);
                decisions.add(new WtxRsiDecision.Close(
                        state, bar.getTimestamp(), exitPrice, realized,
                        WtxRsiDecision.CloseCause.BIAS_FLIP,
                        null, "swing-bias flip → " + bias.name()));
                state = state.withFlat(realized);
            }
        }

        // ── 4) Signal handling ──
        if (signal.isPresent()) {
            WtxRsiSignal sig = signal.get();

            // Bias filter on fresh signals — suppress when contradictory.
            if (state.swingBiasFilterEnabled()) {
                WtxRsiSwingBiasFilter.Decision decision =
                        WtxRsiSwingBiasFilter.evaluate(sig.side(), state.currentPosition(), bias);
                if (decision == WtxRsiSwingBiasFilter.Decision.SUPPRESS) {
                    decisions.add(new WtxRsiDecision.Suppress(
                            sig, "swing-bias filter: " + bias.name()));
                    return new Result(state, decisions);
                }
            }

            state = handleSignal(sig, candles, state, config, decisions);
        }

        return new Result(state, decisions);
    }

    /**
     * Reverse-on-opposite / same-side-suppress / chaikin gate / risk plan / open.
     * Faithful port of {@code WtxRsiStrategyService.handleSignal}.
     */
    private static WtxRsiStrategyState handleSignal(
            WtxRsiSignal signal, List<Candle> candles,
            WtxRsiStrategyState state, WtxRsiConfig config,
            List<WtxRsiDecision> decisions) {

        // Reversal-on-opposite-signal (REVERSAL mode) — close current side first.
        if (state.currentPosition() != WtxRsiPosition.FLAT) {
            boolean opposite =
                    (signal.side() == WtxRsiSignal.Side.SHORT && state.currentPosition() == WtxRsiPosition.LONG)
                 || (signal.side() == WtxRsiSignal.Side.LONG && state.currentPosition() == WtxRsiPosition.SHORT);
            if (opposite && config.tpMode() == WtxRsiTpMode.REVERSAL) {
                BigDecimal exitPrice = signal.close();
                BigDecimal realized = realizedPnl(state, exitPrice, config);
                decisions.add(new WtxRsiDecision.Close(
                        state, signal.timestamp(), exitPrice, realized,
                        WtxRsiDecision.CloseCause.REVERSAL,
                        signal, null));
                state = state.withFlat(realized);
                // fall through to the open block (reverse into the opposite side)
            } else {
                // Same-side signal while open — suppress (legacy: null reason).
                decisions.add(new WtxRsiDecision.Suppress(signal, null));
                return state;
            }
        }

        // Entry-only Chaikin gate.
        if (state.chaikinRequired() && config.chaikinEnabled() && !signal.confirmed()) {
            decisions.add(new WtxRsiDecision.Block(
                    signal, "chaikin-required: entry blocked (Chaikin not confirmed)"));
            return state;
        }

        // Build the risk plan; reject when no confirmed fractal in range.
        BigDecimal entryPrice = signal.close();
        Optional<WtxRsiRiskPlan> maybePlan =
                WtxRsiRiskCalculator.build(signal, candles, entryPrice, config);
        if (maybePlan.isEmpty()) {
            decisions.add(new WtxRsiDecision.Reject(signal, "no confirmed fractal in range"));
            return state;
        }
        WtxRsiRiskPlan plan = maybePlan.get();

        // Honour the user's configured order qty. Chaikin confirmation no longer
        // scales the size — the panel qty (or backtest base-contracts) is the
        // single source of position sizing.
        int contracts = state.configuredOrderQty() > 0
                ? state.configuredOrderQty()
                : plan.contracts();
        WtxRsiRiskPlan finalPlan = new WtxRsiRiskPlan(
                plan.side(), contracts, plan.entryPrice(), plan.stopLoss(),
                plan.takeProfit(), plan.initialRiskPerContract(), plan.swingReference());

        decisions.add(new WtxRsiDecision.Open(signal, finalPlan, state));

        WtxRsiPosition newPos = signal.side() == WtxRsiSignal.Side.LONG
                ? WtxRsiPosition.LONG : WtxRsiPosition.SHORT;
        return state.withPosition(newPos, finalPlan.entryPrice(),
                BigDecimal.valueOf(finalPlan.contracts()), finalPlan.stopLoss(), finalPlan.takeProfit());
    }

    /**
     * Pessimistic SL/TP check on the bar's intra-bar high/low. If both are
     * touched in the same bar, SL wins. Returns the {@link WtxRsiDecision.Close}
     * to emit, or empty when neither is touched.
     */
    private static Optional<WtxRsiDecision.Close> protectiveExit(
            WtxRsiStrategyState state, Candle bar, WtxRsiConfig config) {
        boolean isLong = state.currentPosition() == WtxRsiPosition.LONG;
        BigDecimal sl = state.stopLoss();
        BigDecimal tp = state.takeProfit();
        BigDecimal high = bar.getHigh();
        BigDecimal low = bar.getLow();

        boolean slHit = sl != null
                && (isLong ? low.compareTo(sl) <= 0 : high.compareTo(sl) >= 0);
        boolean tpHit = tp != null
                && (isLong ? high.compareTo(tp) >= 0 : low.compareTo(tp) <= 0);
        if (!slHit && !tpHit) return Optional.empty();

        BigDecimal exitPrice = slHit ? sl : tp;
        BigDecimal realized = realizedPnl(state, exitPrice, config);
        WtxRsiDecision.CloseCause cause = slHit
                ? WtxRsiDecision.CloseCause.STOP_LOSS
                : WtxRsiDecision.CloseCause.TAKE_PROFIT;
        return Optional.of(new WtxRsiDecision.Close(
                state, bar.getTimestamp(), exitPrice, realized, cause, null, null));
    }

    /** USD realised P&L of the open position closing at {@code exitPrice}. */
    public static BigDecimal realizedPnl(WtxRsiStrategyState state, BigDecimal exitPrice, WtxRsiConfig config) {
        if (state.entryPrice() == null || state.entryQty() == null) return BigDecimal.ZERO;
        BigDecimal direction = state.currentPosition() == WtxRsiPosition.LONG
                ? BigDecimal.ONE : BigDecimal.ONE.negate();
        BigDecimal points = exitPrice.subtract(state.entryPrice()).multiply(direction);
        BigDecimal ticks = points.divide(config.tickSize(), 4, RoundingMode.HALF_UP);
        return ticks.multiply(config.tickValueUsd()).multiply(state.entryQty());
    }
}

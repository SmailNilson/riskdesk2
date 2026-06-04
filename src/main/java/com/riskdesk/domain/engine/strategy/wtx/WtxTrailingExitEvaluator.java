package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;

/**
 * Pure domain function evaluating whether an open WTX position should exit on the current candle.
 *
 * Two-phase stop:
 *   1. Fixed initial stop at entry ± slAtrMult * entryAtr.
 *   2. After bestFavorablePrice has moved trailingActivationR * slAtrMult * entryAtr in favor of the position,
 *      switch to a trailing stop at bestFavorablePrice ± trailingAtrMult * entryAtr.
 *
 * Exit detection is intra-bar: the stop is considered touched if the candle's low (LONG) or high (SHORT)
 * crosses the stop level. Fill price is the stop level itself (paper-trading assumption — slippage not modeled).
 *
 * Cohabits with the trailing-stop pattern in TradeSimulationService but operates on per-instrument WTX state.
 */
public final class WtxTrailingExitEvaluator {

    private WtxTrailingExitEvaluator() {}

    public enum ExitReason {
        NONE,
        INITIAL_STOP,
        TRAILING_STOP
    }

    public record Decision(
            boolean shouldExit,
            WtxAction exitAction,
            BigDecimal exitPrice,
            BigDecimal updatedBestFavorablePrice,
            BigDecimal updatedTrailingStopPrice,
            ExitReason reason
    ) {
        public static Decision noExit(BigDecimal mfe, BigDecimal stop) {
            return new Decision(false, WtxAction.NONE, null, mfe, stop, ExitReason.NONE);
        }
    }

    /**
     * Effective protective stop for an open position, for display/risk surfaces.
     *
     * Returns the ratcheted {@code trailingStopPrice} once the trailing phase has armed,
     * otherwise derives the fixed initial ATR stop (entry ∓ slAtrMult * entryAtr) so the
     * active risk level is visible immediately on the bar a fresh position is opened —
     * before {@link #evaluate} runs on the next candle. Null when FLAT or ATR unavailable.
     */
    public static BigDecimal currentStop(WtxStrategyState state, WtxConfig config) {
        if (state == null || state.currentPosition() == WtxPosition.FLAT || state.entryPrice() == null) {
            return null;
        }
        if (state.trailingStopPrice() != null) {
            return state.trailingStopPrice();
        }
        BigDecimal slDistance = slDistance(config, state.entryAtr());
        if (slDistance == null) {
            return null;
        }
        return state.currentPosition() == WtxPosition.LONG
                ? state.entryPrice().subtract(slDistance)
                : state.entryPrice().add(slDistance);
    }

    /**
     * Initial protective-stop distance: a fixed {@code slPoints} when configured ({@code > 0}),
     * otherwise the dynamic {@code slAtrMult * ATR}. Null when neither is available (no ATR and
     * no fixed point stop) — the caller then has no usable initial stop yet.
     */
    private static BigDecimal slDistance(WtxConfig config, BigDecimal atr) {
        if (config.slPoints() != null && config.slPoints().signum() > 0) {
            return config.slPoints();
        }
        if (atr != null && atr.signum() > 0) {
            return atr.multiply(config.slAtrMult());
        }
        return null;
    }

    /**
     * Evaluate exit conditions for an open position.
     *
     * @param state    current strategy state — must have position != FLAT and entryPrice / entryAtr set
     * @param candle   current (just-closed) candle on the trading timeframe
     * @param config   strategy config (provides ATR multipliers and activation threshold)
     */
    public static Decision evaluate(WtxStrategyState state, Candle candle, WtxConfig config) {
        if (state == null || state.currentPosition() == WtxPosition.FLAT || state.entryPrice() == null) {
            return Decision.noExit(null, null);
        }
        BigDecimal atr = state.entryAtr();
        BigDecimal entry = state.entryPrice();

        // Initial stop: fixed points (slPoints>0) or dynamic ATR. Activation + trail follow the mode.
        BigDecimal slDistance = slDistance(config, atr);
        BigDecimal trailDistance;
        BigDecimal activationDistance;
        if (config.usesPointTrailing(state.instrument())) {
            trailDistance = config.trailingPoints();
            activationDistance = config.trailingActivationPoints();
        } else {
            // ATR mode needs a valid ATR for both the trail width and the activation gate.
            if (atr == null || atr.signum() <= 0) {
                return Decision.noExit(state.bestFavorablePrice(), state.trailingStopPrice());
            }
            trailDistance = atr.multiply(config.trailingAtrMult());
            activationDistance = atr.multiply(config.slAtrMult()).multiply(config.trailingActivationR());
        }
        // No usable distances yet (e.g. POINTS mode with a dynamic SL but ATR not available).
        if (slDistance == null || trailDistance == null || activationDistance == null
                || trailDistance.signum() < 0 || activationDistance.signum() < 0) {
            return Decision.noExit(state.bestFavorablePrice(), state.trailingStopPrice());
        }

        boolean isLong = state.currentPosition() == WtxPosition.LONG;

        // Track maximum favorable excursion using the current candle's extreme.
        BigDecimal mfe = state.bestFavorablePrice();
        BigDecimal candleExtreme = isLong ? candle.getHigh() : candle.getLow();
        if (mfe == null) {
            mfe = candleExtreme;
        } else {
            mfe = isLong ? mfe.max(candleExtreme) : mfe.min(candleExtreme);
        }

        BigDecimal favorableMove = isLong ? mfe.subtract(entry) : entry.subtract(mfe);
        boolean trailingArmed = favorableMove.compareTo(activationDistance) >= 0;

        BigDecimal stopPrice;
        if (trailingArmed) {
            stopPrice = isLong ? mfe.subtract(trailDistance) : mfe.add(trailDistance);
            // Trailing stop must only move in favor of the position — never relax it.
            if (state.trailingStopPrice() != null) {
                stopPrice = isLong
                        ? stopPrice.max(state.trailingStopPrice())
                        : stopPrice.min(state.trailingStopPrice());
            }
        } else {
            stopPrice = isLong ? entry.subtract(slDistance) : entry.add(slDistance);
        }

        boolean stopHit = isLong
                ? candle.getLow().compareTo(stopPrice) <= 0
                : candle.getHigh().compareTo(stopPrice) >= 0;

        if (stopHit) {
            return new Decision(
                    true,
                    isLong ? WtxAction.CLOSE_LONG : WtxAction.CLOSE_SHORT,
                    stopPrice,
                    mfe,
                    stopPrice,
                    trailingArmed ? ExitReason.TRAILING_STOP : ExitReason.INITIAL_STOP
            );
        }
        return Decision.noExit(mfe, stopPrice);
    }
}

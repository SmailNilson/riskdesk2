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
        if (atr == null || atr.signum() <= 0) {
            return Decision.noExit(state.bestFavorablePrice(), state.trailingStopPrice());
        }

        BigDecimal entry = state.entryPrice();
        BigDecimal slDistance = atr.multiply(config.slAtrMult());
        BigDecimal trailDistance = atr.multiply(config.trailingAtrMult());
        BigDecimal activationDistance = slDistance.multiply(config.trailingActivationR());

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

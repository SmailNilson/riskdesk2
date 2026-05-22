package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;

/**
 * Evaluates mechanical targets, stop loss, and ATR trailing stops for Playbook strategy.
 */
public final class PlaybookTrailingExitEvaluator {

    private PlaybookTrailingExitEvaluator() {}

    public enum ExitReason {
        NONE,
        STOP_LOSS,
        TAKE_PROFIT,
        TRAILING_STOP
    }

    public record Decision(
            boolean shouldExit,
            BigDecimal exitPrice,
            BigDecimal updatedBestFavorablePrice,
            BigDecimal updatedTrailingStopPrice,
            ExitReason reason
    ) {
        public static Decision noExit(BigDecimal mfe, BigDecimal stop) {
            return new Decision(false, null, mfe, stop, ExitReason.NONE);
        }
    }

    /**
     * Evaluate exit conditions.
     */
    public static Decision evaluate(PlaybookStrategyState state, Candle candle, BigDecimal stopLoss, BigDecimal takeProfit) {
        if (state == null || state.currentPosition() == WtxPosition.FLAT || stopLoss == null) {
            return Decision.noExit(null, null);
        }

        boolean isLong = state.currentPosition() == WtxPosition.LONG;
        BigDecimal candleExtreme = isLong ? candle.getHigh() : candle.getLow();

        // 1. Track MFE
        BigDecimal mfe = state.bestFavorablePrice();
        if (mfe == null) {
            mfe = candleExtreme;
        } else {
            mfe = isLong ? mfe.max(candleExtreme) : mfe.min(candleExtreme);
        }

        // 2. Trailing Stop evaluation (only for SESSION_ATR or STRICT profiles if ATR is present)
        BigDecimal atr = state.entryAtr();
        BigDecimal stopPrice = stopLoss;
        boolean trailingArmed = false;

        if (atr != null && atr.signum() > 0 && (state.activeProfile() == PlaybookProfile.SESSION_ATR || state.activeProfile() == PlaybookProfile.STRICT)) {
            BigDecimal entry = state.entryPrice() != null ? state.entryPrice() : BigDecimal.ZERO;
            BigDecimal activationDistance = atr.multiply(BigDecimal.valueOf(1.5));
            BigDecimal favorableMove = isLong ? mfe.subtract(entry) : entry.subtract(mfe);
            trailingArmed = favorableMove.compareTo(activationDistance) >= 0;

            if (trailingArmed) {
                BigDecimal trailDistance = atr.multiply(BigDecimal.valueOf(2.0));
                BigDecimal computedTrail = isLong ? mfe.subtract(trailDistance) : mfe.add(trailDistance);
                // Trailing stop must only move in favor and be tighter than the initial stop Loss
                if (isLong) {
                    BigDecimal maxStop = computedTrail.max(stopLoss);
                    if (state.trailingStopPrice() != null) {
                        maxStop = maxStop.max(state.trailingStopPrice());
                    }
                    stopPrice = maxStop;
                } else {
                    BigDecimal minStop = computedTrail.min(stopLoss);
                    if (state.trailingStopPrice() != null) {
                        minStop = minStop.min(state.trailingStopPrice());
                    }
                    stopPrice = minStop;
                }
            }
        }

        // 3. Check Stop Loss or Trailing Stop hit
        boolean stopHit = isLong
                ? candle.getLow().compareTo(stopPrice) <= 0
                : candle.getHigh().compareTo(stopPrice) >= 0;

        if (stopHit) {
            return new Decision(
                    true,
                    stopPrice,
                    mfe,
                    stopPrice,
                    trailingArmed ? ExitReason.TRAILING_STOP : ExitReason.STOP_LOSS
            );
        }

        // 4. Check Take Profit target hit
        if (takeProfit != null) {
            boolean tpHit = isLong
                    ? candle.getHigh().compareTo(takeProfit) >= 0
                    : candle.getLow().compareTo(takeProfit) <= 0;

            if (tpHit) {
                return new Decision(
                        true,
                        takeProfit,
                        mfe,
                        stopPrice,
                        ExitReason.TAKE_PROFIT
                );
            }
        }

        return Decision.noExit(mfe, stopPrice);
    }
}

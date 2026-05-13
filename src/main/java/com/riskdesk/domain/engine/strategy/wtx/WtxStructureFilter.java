package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pure domain function porting the Pine Script "Structure proxy" filter (Strict profile).
 *
 * Pine logic (per direction):
 *   priorLow   = lowest(low,  lookback) over the lookback closed candles preceding the current bar
 *   priorHigh  = highest(high, lookback) over the same window
 *   lowSweepReclaim  = low  &lt; priorLow  - atr * sweepBuffer && close &gt; priorLow
 *   highSweepReject  = high &gt; priorHigh + atr * sweepBuffer && close &lt; priorHigh
 *   midBodyPrev      = (open[1] + close[1]) / 2
 *   bullishReclaim   = close &gt; open && close &gt; midBodyPrev && low  &lt;= priorLow  + atr * 0.25
 *   bearishReject    = close &lt; open && close &lt; midBodyPrev && high &gt;= priorHigh - atr * 0.25
 *   structureAllowsLong  = lowSweepReclaim  || bullishReclaim
 *   structureAllowsShort = highSweepReject || bearishReject
 *
 * Fail-safe: insufficient candles or null ATR → permissive (allows = true, reason = UNAVAILABLE).
 */
public final class WtxStructureFilter {

    private static final BigDecimal RECLAIM_FRACTION = BigDecimal.valueOf(0.25);

    private WtxStructureFilter() {}

    public enum StructureReason {
        UNAVAILABLE,
        LOW_SWEEP_RECLAIM,
        BULLISH_RECLAIM,
        HIGH_SWEEP_REJECT,
        BEARISH_REJECT,
        BLOCKED
    }

    public record Decision(boolean allows, StructureReason reason) {}

    /**
     * @param direction      "LONG" or "SHORT" (anything else = unknown → permissive)
     * @param candles        chronological list of closed candles. The LAST element is the current bar.
     * @param atr            ATR at the current bar
     * @param lookback       Pine's structureLen — number of prior bars used to compute priorLow/priorHigh
     * @param sweepBufferAtr Pine's sweepBufferAtr — additional buffer beyond the prior extreme to count as sweep
     */
    public static Decision evaluate(String direction,
                                    List<Candle> candles,
                                    BigDecimal atr,
                                    int lookback,
                                    BigDecimal sweepBufferAtr) {
        if (candles == null || candles.size() < lookback + 2 || atr == null || atr.signum() <= 0) {
            return new Decision(true, StructureReason.UNAVAILABLE);
        }
        int currentIdx = candles.size() - 1;
        Candle current = candles.get(currentIdx);
        Candle prev = candles.get(currentIdx - 1);

        // Prior window — strictly excludes the current bar (Pine uses low[1..lookback]).
        BigDecimal priorLow = null;
        BigDecimal priorHigh = null;
        for (int i = currentIdx - 1; i >= currentIdx - lookback && i >= 0; i--) {
            BigDecimal l = candles.get(i).getLow();
            BigDecimal h = candles.get(i).getHigh();
            priorLow = priorLow == null ? l : priorLow.min(l);
            priorHigh = priorHigh == null ? h : priorHigh.max(h);
        }
        if (priorLow == null || priorHigh == null) {
            return new Decision(true, StructureReason.UNAVAILABLE);
        }

        BigDecimal sweepBuffer = atr.multiply(sweepBufferAtr);
        BigDecimal reclaimBuffer = atr.multiply(RECLAIM_FRACTION);
        BigDecimal midBodyPrev = prev.getOpen().add(prev.getClose())
                .divide(BigDecimal.valueOf(2), atr.scale(), java.math.RoundingMode.HALF_UP);

        boolean lowSweepReclaim = current.getLow().compareTo(priorLow.subtract(sweepBuffer)) < 0
                && current.getClose().compareTo(priorLow) > 0;
        boolean bullishReclaim = current.getClose().compareTo(current.getOpen()) > 0
                && current.getClose().compareTo(midBodyPrev) > 0
                && current.getLow().compareTo(priorLow.add(reclaimBuffer)) <= 0;

        boolean highSweepReject = current.getHigh().compareTo(priorHigh.add(sweepBuffer)) > 0
                && current.getClose().compareTo(priorHigh) < 0;
        boolean bearishReject = current.getClose().compareTo(current.getOpen()) < 0
                && current.getClose().compareTo(midBodyPrev) < 0
                && current.getHigh().compareTo(priorHigh.subtract(reclaimBuffer)) >= 0;

        if ("LONG".equals(direction)) {
            if (lowSweepReclaim) return new Decision(true, StructureReason.LOW_SWEEP_RECLAIM);
            if (bullishReclaim)  return new Decision(true, StructureReason.BULLISH_RECLAIM);
            return new Decision(false, StructureReason.BLOCKED);
        } else if ("SHORT".equals(direction)) {
            if (highSweepReject) return new Decision(true, StructureReason.HIGH_SWEEP_REJECT);
            if (bearishReject)   return new Decision(true, StructureReason.BEARISH_REJECT);
            return new Decision(false, StructureReason.BLOCKED);
        }
        return new Decision(true, StructureReason.UNAVAILABLE);
    }
}

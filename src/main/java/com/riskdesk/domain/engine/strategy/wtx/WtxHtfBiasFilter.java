package com.riskdesk.domain.engine.strategy.wtx;

import java.math.BigDecimal;

/**
 * Pure domain function porting the Pine Script HTF bias proxy.
 *
 * Pine:
 *   htfBullish = htfClose >= htfFast && htfFast >= htfSlow
 *   htfBearish = htfClose <= htfFast && htfFast <= htfSlow
 *   htfAllowsLong  = !htfBearish
 *   htfAllowsShort = !htfBullish
 *
 * Fail-safe: when any input is null (insufficient HTF history) the filter is permissive (allows = true).
 * The reason is reported back so the enrichment layer can surface the cause to the UI.
 */
public final class WtxHtfBiasFilter {

    private WtxHtfBiasFilter() {}

    public enum HtfBias {
        BULLISH, BEARISH, NEUTRAL, UNAVAILABLE
    }

    public record Decision(boolean allows, HtfBias bias) {}

    public record HtfBiasContext(BigDecimal htfClose, BigDecimal htfFastEma, BigDecimal htfSlowEma) {
        public boolean isComplete() {
            return htfClose != null && htfFastEma != null && htfSlowEma != null;
        }
    }

    public static Decision evaluate(String direction, HtfBiasContext ctx) {
        if (ctx == null || !ctx.isComplete()) {
            return new Decision(true, HtfBias.UNAVAILABLE);
        }
        boolean bullish = ctx.htfClose().compareTo(ctx.htfFastEma()) >= 0
                && ctx.htfFastEma().compareTo(ctx.htfSlowEma()) >= 0;
        boolean bearish = ctx.htfClose().compareTo(ctx.htfFastEma()) <= 0
                && ctx.htfFastEma().compareTo(ctx.htfSlowEma()) <= 0;

        HtfBias bias = bullish ? HtfBias.BULLISH
                : bearish ? HtfBias.BEARISH
                : HtfBias.NEUTRAL;

        boolean allows;
        if ("LONG".equals(direction)) {
            allows = !bearish;
        } else if ("SHORT".equals(direction)) {
            allows = !bullish;
        } else {
            allows = true;
        }
        return new Decision(allows, bias);
    }
}

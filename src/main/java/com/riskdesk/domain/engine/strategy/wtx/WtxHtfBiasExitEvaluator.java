package com.riskdesk.domain.engine.strategy.wtx;

/**
 * "A2" early-exit rule: close an open WTX position when the higher-timeframe (1h) bias no longer
 * SUPPORTS its direction — i.e. the bias turned NEUTRAL or flipped against the position. This runs
 * <em>on top of</em> the normal protective stop and opposite-WaveTrend reverse, giving the ride a way
 * out when the trend that justified it has faded, without waiting for a full opposite cross.
 *
 * <p>Validated on a real-1m MNQ 10m backtest (two windows, ~82 trading days, qty 1): +60% net P&L vs
 * the pure SL/ride exit, win-rate 32% → 46%, robust on both windows. The 1h bias only changes on 1h
 * closes, so evaluating it on each closed lower-timeframe candle needs no cross-timeframe coupling.
 *
 * <p>Behaviour by bias:
 * <ul>
 *   <li>{@code BULLISH} — supports LONG (keep), does not support SHORT (exit).</li>
 *   <li>{@code BEARISH} — supports SHORT (keep), does not support LONG (exit).</li>
 *   <li>{@code NEUTRAL} — supports neither (exit either side).</li>
 *   <li>{@code UNAVAILABLE} — insufficient 1h history; never forces an exit (keep — fail-safe).</li>
 * </ul>
 */
public final class WtxHtfBiasExitEvaluator {

    private WtxHtfBiasExitEvaluator() {}

    /**
     * @return {@code true} when the 1h {@code bias} no longer supports the held {@code position}
     *         (NEUTRAL or opposite) and the position should be closed. Always {@code false} for a
     *         FLAT position or an UNAVAILABLE bias.
     */
    public static boolean shouldExit(WtxPosition position, WtxHtfBiasFilter.HtfBias bias) {
        if (position == null || bias == null || position == WtxPosition.FLAT) {
            return false;
        }
        return switch (position) {
            case LONG -> bias == WtxHtfBiasFilter.HtfBias.BEARISH
                    || bias == WtxHtfBiasFilter.HtfBias.NEUTRAL;
            case SHORT -> bias == WtxHtfBiasFilter.HtfBias.BULLISH
                    || bias == WtxHtfBiasFilter.HtfBias.NEUTRAL;
            case FLAT -> false;
        };
    }
}

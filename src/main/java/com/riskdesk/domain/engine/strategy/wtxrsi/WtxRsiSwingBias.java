package com.riskdesk.domain.engine.strategy.wtxrsi;

/**
 * Directional bias derived from the two most-recent confirmed Williams fractals
 * on each side.
 *
 * <ul>
 *   <li>{@link #BULLISH} — Higher high <i>and</i> higher low vs the prior pair</li>
 *   <li>{@link #BEARISH} — Lower high <i>and</i> lower low vs the prior pair</li>
 *   <li>{@link #NEUTRAL} — Mixed signals (HH+LL or HL+LH), or not enough data yet</li>
 * </ul>
 *
 * {@code NEUTRAL} is a passthrough for the filter — same convention as the
 * existing WTX swing-bias path where a null bias never blocks trading.
 */
public enum WtxRsiSwingBias {
    BULLISH, BEARISH, NEUTRAL
}

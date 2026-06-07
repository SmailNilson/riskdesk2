package com.riskdesk.domain.engine.strategy.wtx;

/**
 * How the WTX trailing-exit distances (activation + trail) are measured.
 *
 * <ul>
 *   <li>{@link #ATR} — legacy behaviour: activation = {@code slAtrMult * ATR * trailingActivationR},
 *       trail = {@code trailingAtrMult * ATR}. Scales with volatility.</li>
 *   <li>{@link #POINTS} — fixed point distances: activation = {@code trailingActivationPoints},
 *       trail = {@code trailingPoints}. Backtest-tuned arm/trail that does not widen on
 *       high-volatility legs (where ATR-scaling surrenders too much open profit).</li>
 *   <li>{@link #SL_ONLY} — no trailing ratchet at all: the fixed initial stop is the ONLY stop,
 *       and the position rides until the opposite WaveTrend cross (reverse). Real-1m backtests
 *       showed the tight trailing ratchet was net-negative (it clipped winners / whipsawed on the
 *       true intrabar path); keeping only the wide fixed SL preserved the edge while bounding the
 *       tail. The fixed SL distance is still {@code slPoints} (when {@code > 0}) or {@code slAtrMult * ATR}.</li>
 * </ul>
 *
 * The initial stop-loss distance is chosen independently of this mode: a fixed
 * {@code slPoints} when set ({@code > 0}), otherwise the dynamic {@code slAtrMult * ATR}.
 */
public enum WtxTrailingMode {
    ATR,
    POINTS,
    SL_ONLY
}

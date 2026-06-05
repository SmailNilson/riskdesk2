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
 * </ul>
 *
 * The initial stop-loss distance is chosen independently of this mode: a fixed
 * {@code slPoints} when set ({@code > 0}), otherwise the dynamic {@code slAtrMult * ATR}.
 */
public enum WtxTrailingMode {
    ATR,
    POINTS
}

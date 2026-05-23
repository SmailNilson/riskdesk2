package com.riskdesk.domain.engine.strategy.wtxrsi;

/**
 * Which engine resolves the {@link WtxRsiSwingBias} on each closed bar.
 *
 * <p>Both modes feed the same downstream filter ({@link WtxRsiSwingBiasFilter})
 * and the same UI badge. Switchable per (instrument, timeframe) via config
 * (default driven by {@code riskdesk.wtxrsi.bias-source}).</p>
 *
 * <ul>
 *   <li>{@link #FRACTAL_HH_HL} — derived from the two most-recent confirmed
 *       Williams fractals on each side. Self-contained domain, no Spring
 *       dependency. Conservative: only flips when both highs and both lows
 *       agree on direction.</li>
 *   <li>{@link #SMC_ENGINE} — read straight from the production SMC structure
 *       engine via {@code IndicatorService.computeSnapshot().swingBias()}.
 *       Same source as the WTx swing-bias path. Richer (BOS / CHoCH / sweeps)
 *       and consistent cross-strategy, at the cost of an application-layer
 *       dependency the FRACTAL path doesn't need.</li>
 * </ul>
 */
public enum WtxRsiBiasSource {
    FRACTAL_HH_HL,
    SMC_ENGINE
}

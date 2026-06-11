package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Continuous order-book flow signals computed from successive {@link DepthMetrics}
 * snapshots by the {@code DepthFlowAnalyzer}. One immutable record per evaluation
 * tick (~500ms), published on {@code /topic/depth-flow} and served by
 * {@code GET /api/order-flow/depth-flow/{instrument}}.
 *
 * <p>Four signal families:</p>
 * <ul>
 *   <li><b>OFI</b> (Cont-Kukanov-Stoikov order flow imbalance) — best-level flow events
 *       accumulated over 1s / 10s windows plus a 60s exponentially-decayed running flow.
 *       {@code ofiZ10s} z-scores the 10s sum against a trailing 5-min distribution.
 *       <b>Honest caveat:</b> the canonical result (Cont, Kukanov &amp; Stoikov 2014) is
 *       <em>contemporaneous</em> — OFI explains ~65% of concurrent 10s price-change
 *       variance on S&amp;P stocks; its <em>forecast</em> value is much weaker. Treat this
 *       as an entry-TIMING gauge confirming a thesis, never a standalone signal.</li>
 *   <li><b>Queue imbalance + micro-price</b> (Gould-Bonart / Stoikov) — best-level
 *       imbalance I = (qb-qa)/(qb+qa), EMA-smoothed (~3s); only meaningful when the
 *       combined best-level mass clears {@code queueImbalanceValid}'s min-queue-mass
 *       gate. The micro-price (Pb·qa + Pa·qb)/(qb+qa) is exposed as a signed offset in
 *       ticks from the mid — where the "fair" price leans inside the spread.</li>
 *   <li><b>Liquidity vacuum</b> — each side's total visible depth vs its rolling 5-min
 *       baseline. {@code VACUUM_BID}/{@code VACUUM_ASK} when one side is depleted below
 *       the depletion ratio for the persistence window while the other holds;
 *       {@code THIN} when both sides sit below the thin ratio.</li>
 *   <li><b>Pull/stack net flow</b> — per-level resting-size changes (same tick-rounded
 *       price present in consecutive snapshots) beyond a noise floor, aggregated over
 *       10s. <b>Approximation:</b> without per-level trade attribution a size decrease
 *       cannot be split into "pulled" (cancelled) vs "consumed" (traded); raw size
 *       change is used as the proxy. {@code pullStackScore} is the bid-vs-ask net,
 *       normalized by the combined baseline depth.</li>
 * </ul>
 */
public record DepthFlowMetrics(
    Instrument instrument,
    /** OFI events summed over the trailing 1s. */
    double ofi1s,
    /** OFI events summed over the trailing 10s. */
    double ofi10s,
    /** Exponentially-decayed running OFI flow, 60s time constant. */
    double ofiEma60s,
    /** Z-score of {@code ofi10s} vs the trailing 5-min distribution of 10s sums (0 until warm). */
    double ofiZ10s,
    /** True when |ofiZ10s| clears the configured flag threshold (default 2.0). */
    boolean ofiExtreme,
    /** EMA-smoothed best-level queue imbalance, -1..+1 (positive = bid-heavy). */
    double queueImbalance,
    /** False when the combined best-level mass is below min-queue-mass — imbalance not meaningful. */
    boolean queueImbalanceValid,
    /** Micro-price minus mid, in ticks. Positive = fair price leans toward the ask (bullish). */
    double microPriceOffsetTicks,
    /** Liquidity vacuum state from the rolling depth baselines. */
    VacuumState vacuumState,
    /** Current total bid depth / rolling 5-min bid baseline (1.0 until the baseline is warm). */
    double bidDepthRatio,
    /** Current total ask depth / rolling 5-min ask baseline (1.0 until the baseline is warm). */
    double askDepthRatio,
    /** Contracts removed from resting bid levels over 10s (beyond the noise floor). */
    long bidPulled10s,
    /** Contracts added to resting bid levels over 10s (beyond the noise floor). */
    long bidStacked10s,
    /** Contracts removed from resting ask levels over 10s (beyond the noise floor). */
    long askPulled10s,
    /** Contracts added to resting ask levels over 10s (beyond the noise floor). */
    long askStacked10s,
    /** ((bidStacked-bidPulled) - (askStacked-askPulled)) / combined baseline depth. Positive = bid-side building. */
    double pullStackScore,
    Instant timestamp
) {

    /** Liquidity vacuum classification of the visible book vs its rolling baseline. */
    public enum VacuumState {
        /** Both sides near their baselines. */
        NORMAL,
        /** Both sides below the thin ratio — overall liquidity withdrawal. */
        THIN,
        /** Bid side depleted below the depletion ratio (persisted) while asks hold — downside vacuum. */
        VACUUM_BID,
        /** Ask side depleted below the depletion ratio (persisted) while bids hold — upside vacuum. */
        VACUUM_ASK
    }
}

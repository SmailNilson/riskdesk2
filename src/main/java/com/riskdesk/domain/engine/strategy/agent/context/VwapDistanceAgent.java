package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.IndicatorContext;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * CONTEXT agent — measures price distance from VWAP in σ-units derived from the
 * VWAP standard-deviation bands and votes for mean-reversion at the extremes.
 *
 * <p>Convention: <b>positive vote = supports LONG</b>, <b>negative = supports SHORT</b>.
 * Price stretched far <i>above</i> VWAP favors a SHORT (mean-revert down) → negative
 * vote. Price stretched far <i>below</i> VWAP favors a LONG (mean-revert up) →
 * positive vote.
 *
 * <p>σ-distance is computed against the upper/lower band on the appropriate side
 * (1σ ≈ band edge by VWAP-band convention). A floor of 0.01 prevents divide-by-zero
 * when bands collapse on illiquid bars.
 */
public final class VwapDistanceAgent implements StrategyAgent {

    public static final String ID = "vwap-distance";

    /** Beyond this many σ, the mean-reversion edge is strong enough to vote ±60. */
    private static final double EXTREME_SIGMA = 1.5;
    /** Within this many σ around VWAP, price is "aligned" — small neutral vote. */
    private static final double NEUTRAL_SIGMA = 0.5;
    private static final int EXTREME_VOTE = 60;
    /** Confidence saturates at 3σ. */
    private static final double CONFIDENCE_SCALE = 3.0;
    private static final BigDecimal BAND_FLOOR = new BigDecimal("0.01");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyLayer layer() {
        return StrategyLayer.CONTEXT;
    }

    @Override
    public AgentVote evaluate(StrategyInput input) {
        IndicatorContext ind = input.context().indicators();
        BigDecimal price = input.context().lastPrice();
        if (!ind.hasVwap() || price == null) {
            return AgentVote.abstain(ID, StrategyLayer.CONTEXT, "VWAP/last-price unavailable");
        }

        BigDecimal vwap = ind.vwap();
        BigDecimal diff = price.subtract(vwap);
        BigDecimal denom;
        if (diff.signum() >= 0) {
            denom = ind.vwapUpperBand().subtract(vwap);
        } else {
            denom = vwap.subtract(ind.vwapLowerBand());
        }
        if (denom.compareTo(BAND_FLOOR) < 0) {
            denom = BAND_FLOOR;
        }
        double distanceSigma = diff.divide(denom, 6, RoundingMode.HALF_UP).doubleValue();
        double absSigma = Math.abs(distanceSigma);

        int directionalVote;
        String evidence;
        if (distanceSigma > EXTREME_SIGMA) {
            // Stretched ABOVE VWAP → mean-revert favors SHORT.
            directionalVote = -EXTREME_VOTE;
            evidence = String.format(Locale.ROOT, "Far above VWAP %+.1fσ — mean-reversion risk for LONG",
                distanceSigma);
        } else if (distanceSigma < -EXTREME_SIGMA) {
            // Stretched BELOW VWAP → mean-revert favors LONG.
            directionalVote = EXTREME_VOTE;
            evidence = String.format(Locale.ROOT, "Far below VWAP %+.1fσ — mean-reversion risk for SHORT",
                distanceSigma);
        } else if (absSigma <= NEUTRAL_SIGMA) {
            directionalVote = 0;
            evidence = String.format(Locale.ROOT, "Price aligned with VWAP (%+.2fσ)", distanceSigma);
        } else {
            // Linear scale between the neutral band edge and the extreme threshold.
            double scaled = (absSigma - NEUTRAL_SIGMA) / (EXTREME_SIGMA - NEUTRAL_SIGMA);
            int magnitude = (int) Math.round(scaled * EXTREME_VOTE);
            int sign = distanceSigma > 0 ? -1 : +1; // mean-reversion direction
            directionalVote = sign * magnitude;
            evidence = String.format(Locale.ROOT, "Price %+.2fσ from VWAP — mean-reversion bias", distanceSigma);
        }

        double confidence = Math.min(1.0, absSigma / CONFIDENCE_SCALE);
        return AgentVote.of(ID, StrategyLayer.CONTEXT, directionalVote, confidence, List.of(evidence));
    }
}

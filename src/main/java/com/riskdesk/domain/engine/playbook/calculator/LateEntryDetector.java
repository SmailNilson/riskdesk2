package com.riskdesk.domain.engine.playbook.calculator;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;

import java.math.BigDecimal;

/**
 * PR-8 · Late-entry detector.
 *
 * <p>A setup is "late" when price has already moved in the trade direction
 * <em>beyond</em> the planned entry by more than a fixed fraction of ATR.
 * Taking the trade in this state means chasing a move that has already played
 * out — R:R is degraded, stop-loss risk is expanded, and the edge evaporates.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>LONG</b>: late iff {@code lastPrice - entryPrice &gt; threshold × atr}</li>
 *   <li><b>SHORT</b>: late iff {@code entryPrice - lastPrice &gt; threshold × atr}</li>
 * </ul>
 *
 * <p>Designed to fail-open: when any input is missing (null ATR, null entry,
 * null price), the detector returns {@code false} so a setup is never killed
 * by absent data. Production callers ensure inputs are present via the playbook
 * input builder; this safety keeps unit tests and degraded-path flows usable.
 */
public final class LateEntryDetector {

    /**
     * Default tolerance — price may move up to half an ATR past the entry
     * before the setup is considered late. Tuned to let noise through while
     * catching meaningful directional drift.
     */
    public static final double DEFAULT_ATR_MULTIPLIER = 0.5;

    private final double atrMultiplier;

    public LateEntryDetector() {
        this(DEFAULT_ATR_MULTIPLIER);
    }

    public LateEntryDetector(double atrMultiplier) {
        if (atrMultiplier <= 0) {
            throw new IllegalArgumentException("atrMultiplier must be > 0, got " + atrMultiplier);
        }
        this.atrMultiplier = atrMultiplier;
    }

    public double atrMultiplier() {
        return atrMultiplier;
    }

    /**
     * @return {@code true} if price has already moved past the planned entry
     *         in the trade direction by more than {@code atrMultiplier × atr}.
     */
    public boolean isLate(PlaybookPlan plan, BigDecimal lastPrice, BigDecimal atr, Direction direction) {
        if (plan == null || plan.entryPrice() == null
                || lastPrice == null || atr == null || direction == null) {
            return false;
        }
        if (atr.signum() <= 0) {
            return false;
        }
        BigDecimal tolerance = atr.multiply(BigDecimal.valueOf(atrMultiplier));
        BigDecimal advance = direction == Direction.LONG
            ? lastPrice.subtract(plan.entryPrice())
            : plan.entryPrice().subtract(lastPrice);
        return advance.compareTo(tolerance) > 0;
    }
}

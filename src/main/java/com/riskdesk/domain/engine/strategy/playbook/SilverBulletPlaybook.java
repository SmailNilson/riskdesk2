package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.FvgZone;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.SessionInfo;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * <b>SB — ICT Silver Bullet.</b>
 *
 * <p>NY AM kill-zone trend-continuation setup. The institutional pattern: during
 * the 10:00–11:00 ET window, price often retests a recent Fair Value Gap in
 * the direction of the daily / HTF bias, then continues. Targets are
 * 2–5R away, stop tight beyond the FVG.
 *
 * <p>Distinct from {@link SbdrPlaybook} in that it REQUIRES a matching-direction
 * FVG (not just an order block). When no FVG is present, SBDR remains the
 * fallback continuation setup.
 *
 * <p><b>Applicability</b>
 * <ul>
 *   <li>SessionInfo known AND phase=NY_AM AND killZone=true</li>
 *   <li>MacroBias non-NEUTRAL (we need a directional edge)</li>
 *   <li>At least one actionable FVG (filledPct &lt; 0.5) aligned with the bias</li>
 * </ul>
 *
 * <p>Plan: entry at the FVG midpoint, stop beyond the far edge plus a
 * 0.25 × ATR buffer, TP1 at 2.5R (tighter than SBDR because SB has a narrower
 * timing window), TP2 at 4R.
 */
public final class SilverBulletPlaybook implements Playbook {

    public static final String ID = "SB";

    private static final double SL_BUFFER_ATR = 0.25;
    private static final double TP1_R = 2.5;
    private static final double TP2_R = 4.0;
    private static final double MIN_EXECUTION_SCORE = 60.0;
    private static final String REQUIRED_PHASE = "NY_AM";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isApplicable(MarketContext context) {
        SessionInfo session = context.session();
        if (!session.isKnown()) return false;
        if (!session.killZone()) return false;
        if (session.phase() == null || !REQUIRED_PHASE.equalsIgnoreCase(session.phase())) return false;

        // Must have a directional HTF bias — Silver Bullet is trend-continuation,
        // not fade. Without bias, fall through to a reversal setup.
        return context.macroBias() != MacroBias.NEUTRAL;
    }

    @Override
    public Optional<MechanicalPlan> buildPlan(StrategyInput input) {
        MarketContext ctx = input.context();
        if (ctx.lastPrice() == null || ctx.atr() == null) return Optional.empty();
        if (ctx.macroBias() == MacroBias.NEUTRAL) return Optional.empty();

        boolean longSide = ctx.macroBias() == MacroBias.BULL;
        Direction direction = longSide ? Direction.LONG : Direction.SHORT;

        // Find a matching-direction, actionable FVG. The "actionable" filter
        // (filledPct < 0.5) is already encoded in FvgZone.isActionable().
        Optional<FvgZone> anchor = input.zones().activeFvgs().stream()
            .filter(fvg -> fvg.bullish() == longSide)
            .filter(FvgZone::isActionable)
            .findFirst();
        if (anchor.isEmpty()) return Optional.empty();

        FvgZone fvg = anchor.get();
        // Midpoint entry — "ICT 50% of FVG" convention
        BigDecimal entry = fvg.top().add(fvg.bottom())
            .divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
        BigDecimal buffer = ctx.atr().multiply(BigDecimal.valueOf(SL_BUFFER_ATR));
        // Stop beyond the far edge of the FVG
        BigDecimal stop = longSide ? fvg.bottom().subtract(buffer) : fvg.top().add(buffer);

        BigDecimal risk = entry.subtract(stop).abs();
        if (risk.signum() == 0) return Optional.empty();

        BigDecimal tp1 = longSide ? entry.add(risk.multiply(BigDecimal.valueOf(TP1_R)))
                                  : entry.subtract(risk.multiply(BigDecimal.valueOf(TP1_R)));
        BigDecimal tp2 = longSide ? entry.add(risk.multiply(BigDecimal.valueOf(TP2_R)))
                                  : entry.subtract(risk.multiply(BigDecimal.valueOf(TP2_R)));
        double rr = tp1.subtract(entry).abs().divide(risk, 4, RoundingMode.HALF_UP).doubleValue();

        return Optional.of(new MechanicalPlan(direction, entry, stop, tp1, tp2, rr));
    }

    @Override
    public double minimumScoreForExecution() {
        return MIN_EXECUTION_SCORE;
    }
}

package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.OrderBlockZone;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * <b>SBDR — Structural Break + Discount Retest</b>.
 *
 * <p>Trend-continuation setup. Applicable when:
 * <ul>
 *   <li>Regime = TRENDING</li>
 *   <li>MacroBias is BULL or BEAR (non-neutral)</li>
 *   <li>PD zone is opposite the bias (DISCOUNT for a bull retrace, PREMIUM for a bear
 *       retrace). This catches the pullback after a BOS, not a mid-trend chase.</li>
 * </ul>
 *
 * <p>Requires a matching-direction OB near price to form the anchor — no OB, no
 * plan. Entry at the OB midpoint (equilibrium), stop beyond the opposite OB edge.
 * SBDR demands a higher minimum score than LSAR because trend-continuation
 * demands more conviction.
 */
public final class SbdrPlaybook implements Playbook {

    public static final String ID = "SBDR";

    private static final double SL_BUFFER_ATR = 0.25;
    private static final double MIN_EXECUTION_SCORE = 65.0;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isApplicable(MarketContext context) {
        if (context.regime() != MarketRegime.TRENDING) return false;
        if (context.macroBias() == MacroBias.NEUTRAL) return false;
        // Pullback guard: bias BULL requires price in DISCOUNT (discount-long),
        // bias BEAR requires PREMIUM. UNKNOWN PD zone degrades gracefully — we still
        // allow applicability but rely on the score to demote.
        if (context.pdZone() == PdZone.UNKNOWN) return true;
        if (context.macroBias() == MacroBias.BULL && context.pdZone() == PdZone.DISCOUNT) return true;
        return context.macroBias() == MacroBias.BEAR && context.pdZone() == PdZone.PREMIUM;
    }

    @Override
    public Optional<MechanicalPlan> buildPlan(StrategyInput input) {
        MarketContext ctx = input.context();
        if (ctx.lastPrice() == null || ctx.atr() == null) return Optional.empty();

        boolean longSide = ctx.macroBias() == MacroBias.BULL;
        Direction direction = longSide ? Direction.LONG : Direction.SHORT;

        Optional<OrderBlockZone> anchor = findAnchorOb(input, longSide);
        if (anchor.isEmpty()) return Optional.empty();

        OrderBlockZone ob = anchor.get();
        BigDecimal entry = ob.mid();
        BigDecimal buffer = ctx.atr().multiply(BigDecimal.valueOf(SL_BUFFER_ATR));
        BigDecimal stop = longSide ? ob.bottom().subtract(buffer) : ob.top().add(buffer);

        BigDecimal risk = entry.subtract(stop).abs();
        if (risk.signum() == 0) return Optional.empty();

        // Trend-continuation targets are wider — minimum 3R on TP1.
        BigDecimal tp1 = longSide ? entry.add(risk.multiply(BigDecimal.valueOf(3.0)))
                                  : entry.subtract(risk.multiply(BigDecimal.valueOf(3.0)));
        BigDecimal tp2 = longSide ? entry.add(risk.multiply(BigDecimal.valueOf(5.0)))
                                  : entry.subtract(risk.multiply(BigDecimal.valueOf(5.0)));

        double rr = tp1.subtract(entry).abs()
            .divide(risk, 4, RoundingMode.HALF_UP).doubleValue();

        return Optional.of(new MechanicalPlan(direction, entry, stop, tp1, tp2, rr));
    }

    @Override
    public double minimumScoreForExecution() {
        return MIN_EXECUTION_SCORE;
    }

    private static Optional<OrderBlockZone> findAnchorOb(StrategyInput input, boolean longSide) {
        BigDecimal price = input.context().lastPrice();
        return input.zones().activeOrderBlocks().stream()
            .filter(ob -> ob.bullish() == longSide)
            .min((a, b) -> a.mid().subtract(price).abs()
                .compareTo(b.mid().subtract(price).abs()));
    }
}

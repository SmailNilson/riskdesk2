package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.OrderBlockZone;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.SessionInfo;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * <b>LS — London Sweep.</b>
 *
 * <p>Sister playbook to {@link NyOpenReversalPlaybook} but scoped to the London
 * kill zone (02:00–05:00 ET). During London open, liquidity pools at Asian
 * session highs/lows get swept and price reverses back into the Asian range —
 * a well-documented FX & metals pattern.
 *
 * <p>Structurally identical to LSAR + NOR (VA-extreme reversal), which is
 * deliberate: a dedicated id gives us instrument- and session-level telemetry
 * so we can measure London-specific edge independent of the generic LSAR.
 *
 * <p><b>Applicability</b>
 * <ul>
 *   <li>SessionInfo known AND phase=LONDON AND killZone=true</li>
 *   <li>PriceLocation is ABOVE_VAH or BELOW_VAL</li>
 *   <li>PdZone aligns with the extreme, or UNKNOWN (degrade gracefully)</li>
 * </ul>
 */
public final class LondonSweepPlaybook implements Playbook {

    public static final String ID = "LS";

    private static final double SL_BUFFER_ATR = 0.25;
    private static final double EXTREME_TOLERANCE_ATR = 0.3;
    private static final double MIN_EXECUTION_SCORE = 55.0;
    private static final String REQUIRED_PHASE = "LONDON";

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

        boolean atExtreme = context.priceLocation() == PriceLocation.ABOVE_VAH
            || context.priceLocation() == PriceLocation.BELOW_VAL;
        if (!atExtreme) return false;

        if (context.pdZone() == PdZone.UNKNOWN) return true;
        if (context.priceLocation() == PriceLocation.ABOVE_VAH
            && context.pdZone() == PdZone.PREMIUM) return true;
        return context.priceLocation() == PriceLocation.BELOW_VAL
            && context.pdZone() == PdZone.DISCOUNT;
    }

    @Override
    public Optional<MechanicalPlan> buildPlan(StrategyInput input) {
        MarketContext ctx = input.context();
        if (ctx.lastPrice() == null || ctx.atr() == null) return Optional.empty();

        boolean below = ctx.priceLocation() == PriceLocation.BELOW_VAL;
        Direction direction = below ? Direction.LONG : Direction.SHORT;

        BigDecimal price = ctx.lastPrice();
        BigDecimal atr = ctx.atr();
        BigDecimal buffer = atr.multiply(BigDecimal.valueOf(SL_BUFFER_ATR));

        Optional<OrderBlockZone> anchor = input.zones().activeOrderBlocks().stream()
            .filter(ob -> ob.bullish() == below)
            .findFirst();

        BigDecimal entry = anchor.map(OrderBlockZone::mid).orElse(price);
        BigDecimal sweptExtreme = anchor
            .map(ob -> below ? ob.bottom() : ob.top())
            .orElse(below
                ? price.subtract(atr.multiply(BigDecimal.valueOf(EXTREME_TOLERANCE_ATR)))
                : price.add(atr.multiply(BigDecimal.valueOf(EXTREME_TOLERANCE_ATR))));
        BigDecimal stop = below ? sweptExtreme.subtract(buffer) : sweptExtreme.add(buffer);

        BigDecimal risk = entry.subtract(stop).abs();
        if (risk.signum() == 0) return Optional.empty();

        BigDecimal tp1 = below ? entry.add(risk.multiply(BigDecimal.valueOf(2.0)))
                               : entry.subtract(risk.multiply(BigDecimal.valueOf(2.0)));
        BigDecimal tp2 = below ? entry.add(risk.multiply(BigDecimal.valueOf(3.5)))
                               : entry.subtract(risk.multiply(BigDecimal.valueOf(3.5)));
        double rr = tp1.subtract(entry).abs().divide(risk, 4, RoundingMode.HALF_UP).doubleValue();

        return Optional.of(new MechanicalPlan(direction, entry, stop, tp1, tp2, rr));
    }

    @Override
    public double minimumScoreForExecution() {
        return MIN_EXECUTION_SCORE;
    }
}

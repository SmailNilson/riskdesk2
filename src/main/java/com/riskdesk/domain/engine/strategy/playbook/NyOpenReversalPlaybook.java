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
 * <b>NOR — NY Open Reversal.</b>
 *
 * <p>Session-scoped reversal setup. Tightens {@link LsarPlaybook}'s applicability
 * window to the NY AM kill zone, which is where equity / index / metals
 * reversals cluster (liquidity run into Asian-session extremes → NY open fade).
 *
 * <p><b>Why a dedicated playbook instead of just parameterising LSAR?</b> The
 * scoring-policy threshold is the same, but giving this pattern its own id lets
 * the Mentor review identify "NOR" distinctly in telemetry — we can measure
 * edge on this specific pattern and tune it independently from generic range
 * reversals later.
 *
 * <p><b>Applicability</b>
 * <ul>
 *   <li>SessionInfo known AND phase=NY_AM AND killZone=true — outside the kill
 *       zone we fall through to the generic LSAR.</li>
 *   <li>PriceLocation is ABOVE_VAH or BELOW_VAL (Value Area extreme).</li>
 *   <li>PdZone aligns with the extreme (PREMIUM above, DISCOUNT below), or is
 *       UNKNOWN (gracefully degrade).</li>
 * </ul>
 *
 * <p>The plan mirrors LSAR: entry at a matching-direction OB midpoint (or
 * lastPrice when no anchor found), stop beyond the swept extreme plus a
 * 0.25 × ATR buffer, TP1 at 2R, TP2 at 3.5R.
 */
public final class NyOpenReversalPlaybook implements Playbook {

    public static final String ID = "NOR";

    private static final double SL_BUFFER_ATR = 0.25;
    private static final double EXTREME_TOLERANCE_ATR = 0.3;
    private static final double MIN_EXECUTION_SCORE = 55.0;
    private static final String REQUIRED_PHASE = "NY_AM";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isApplicable(MarketContext context) {
        SessionInfo session = context.session();
        // Session discipline: must be known + NY AM kill zone
        if (!session.isKnown()) return false;
        if (!session.killZone()) return false;
        if (session.phase() == null || !REQUIRED_PHASE.equalsIgnoreCase(session.phase())) return false;

        // Structural: price at a VA extreme (same as LSAR)
        boolean atExtreme = context.priceLocation() == PriceLocation.ABOVE_VAH
            || context.priceLocation() == PriceLocation.BELOW_VAL;
        if (!atExtreme) return false;

        // PD zone alignment — same degrade-gracefully rule as LSAR
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

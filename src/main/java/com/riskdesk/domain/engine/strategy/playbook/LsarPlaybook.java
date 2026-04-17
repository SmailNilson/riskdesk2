package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.OrderBlockZone;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * <b>LSAR — Liquidity Sweep + Absorption Reversal</b>.
 *
 * <p>Range reversal setup. Applicable when:
 * <ul>
 *   <li>Regime = RANGING</li>
 *   <li>Price sits outside the Value Area (ABOVE_VAH or BELOW_VAL), i.e. at an extreme</li>
 *   <li>PD zone aligned with the extreme (PREMIUM above VAH, DISCOUNT below VAL)</li>
 * </ul>
 *
 * <p>The plan targets a reversion toward POC first, then toward the opposite VA edge.
 * The stop sits beyond the swept extreme plus a quarter-ATR buffer to survive a
 * second stop-hunt attempt.
 *
 * <p>ATR-based stop distance keeps the plan self-consistent across instruments —
 * MNQ's 50-point range and MCL's 0.50-point range both produce a sensible SL
 * because ATR scales with each.
 */
public final class LsarPlaybook implements Playbook {

    public static final String ID = "LSAR";

    /** Price must be within 0.3 × ATR of the extreme to call it a "sweep candidate". */
    private static final double EXTREME_TOLERANCE_ATR = 0.3;
    /** Buffer beyond the swept extreme for the stop. */
    private static final double SL_BUFFER_ATR = 0.25;
    /** Minimum score (on [0, 100]) to exit PAPER_TRADE into live sizing. */
    private static final double MIN_EXECUTION_SCORE = 55.0;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isApplicable(MarketContext context) {
        if (context.regime() != MarketRegime.RANGING) return false;
        boolean atExtreme = context.priceLocation() == PriceLocation.ABOVE_VAH
            || context.priceLocation() == PriceLocation.BELOW_VAL;
        if (!atExtreme) return false;
        // PD zone must align with the extreme — optional if PD data unavailable
        if (context.pdZone() == PdZone.UNKNOWN) return true;
        if (context.priceLocation() == PriceLocation.ABOVE_VAH
            && context.pdZone() == PdZone.PREMIUM) return true;
        if (context.priceLocation() == PriceLocation.BELOW_VAL
            && context.pdZone() == PdZone.DISCOUNT) return true;
        return false;
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

        // Prefer an OB of the matching direction near price — use its midpoint as entry
        // so we trade the reversion from the zone, not from the last print.
        Optional<OrderBlockZone> anchor = findAnchorOb(input, below);

        BigDecimal entry = anchor
            .map(OrderBlockZone::mid)
            .orElse(price);

        // Stop: beyond the extreme of the OB if present, else beyond the swept level
        // proxied by lastPrice ± tolerance.
        BigDecimal sweptExtreme = anchor
            .map(ob -> below ? ob.bottom() : ob.top())
            .orElse(below
                ? price.subtract(atr.multiply(BigDecimal.valueOf(EXTREME_TOLERANCE_ATR)))
                : price.add(atr.multiply(BigDecimal.valueOf(EXTREME_TOLERANCE_ATR))));
        BigDecimal stop = below ? sweptExtreme.subtract(buffer) : sweptExtreme.add(buffer);

        // Targets: POC first, opposite VA edge second — proxied via ATR when we
        // don't have the POC/VA as BigDecimal here (MarketContext stores only price
        // and ATR). The builder will refine these when VA is available; this floor
        // keeps the R:R computable end-to-end.
        BigDecimal risk = entry.subtract(stop).abs();
        if (risk.signum() == 0) return Optional.empty();

        BigDecimal tp1 = below ? entry.add(risk.multiply(BigDecimal.valueOf(2.0)))
                               : entry.subtract(risk.multiply(BigDecimal.valueOf(2.0)));
        BigDecimal tp2 = below ? entry.add(risk.multiply(BigDecimal.valueOf(3.5)))
                               : entry.subtract(risk.multiply(BigDecimal.valueOf(3.5)));

        double rr = risk.signum() == 0 ? 0.0
            : tp1.subtract(entry).abs().divide(risk, 4, RoundingMode.HALF_UP).doubleValue();

        return Optional.of(new MechanicalPlan(direction, entry, stop, tp1, tp2, rr));
    }

    @Override
    public double minimumScoreForExecution() {
        return MIN_EXECUTION_SCORE;
    }

    private static Optional<OrderBlockZone> findAnchorOb(StrategyInput input, boolean longSide) {
        return input.zones().activeOrderBlocks().stream()
            .filter(ob -> ob.bullish() == longSide)
            .findFirst();
    }
}

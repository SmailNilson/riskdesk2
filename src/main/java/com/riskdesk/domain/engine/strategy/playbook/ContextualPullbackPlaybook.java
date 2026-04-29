package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.OrderBlockZone;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * <b>CTX — Contextual Pullback</b> (fallback playbook).
 *
 * <p>Catch-all that activates when no session-scoped or extreme-scoped playbook
 * matches but the market still has a coherent directional thesis. The other five
 * playbooks all require either a kill-zone phase ({@link SilverBulletPlaybook},
 * {@link NyOpenReversalPlaybook}, {@link LondonSweepPlaybook}) or a price extension
 * to the Value-Area extremes ({@link LsarPlaybook}). Empirically the market spends
 * the majority of its time inside the VA and outside kill zones, so without a
 * fallback the selector returns empty and the engine short-circuits to STANDBY —
 * the scoring layer never even runs.
 *
 * <p>Applicability (least restrictive of the lot — by design):
 * <ul>
 *   <li>regime != CHOPPY (we need some directional persistence)</li>
 *   <li>macroBias != NEUTRAL (we need a side to take)</li>
 * </ul>
 *
 * <p>Plan: trade the pullback into the nearest OB matching the macro-bias direction.
 * No OB → no plan (intentional — we don't fabricate entries from raw price).
 *
 * <p>Sized as the most restrictive playbook ({@code MIN_EXECUTION_SCORE = 65}) so
 * that the looser applicability gate is offset by a higher bar at the scoring stage.
 * This matches the doctrine: "permissive on entry, strict on confirmation".
 */
public final class ContextualPullbackPlaybook implements Playbook {

    public static final String ID = "CTX";

    private static final double SL_BUFFER_ATR = 0.25;
    private static final double TP1_R = 2.0;
    private static final double TP2_R = 3.5;
    private static final double MIN_EXECUTION_SCORE = 65.0;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isApplicable(MarketContext context) {
        if (context.regime() == MarketRegime.CHOPPY) return false;
        if (context.regime() == MarketRegime.UNKNOWN) return false;
        return context.macroBias() != MacroBias.NEUTRAL;
    }

    @Override
    public Optional<MechanicalPlan> buildPlan(StrategyInput input) {
        MarketContext ctx = input.context();
        if (ctx.lastPrice() == null || ctx.atr() == null) return Optional.empty();
        if (ctx.macroBias() == MacroBias.NEUTRAL) return Optional.empty();

        boolean longSide = ctx.macroBias() == MacroBias.BULL;
        Direction direction = longSide ? Direction.LONG : Direction.SHORT;

        // Pick the OB whose midpoint is closest to current price.
        // ZoneContextBuilder preserves snapshot iteration order, so findFirst()
        // would silently grab a farther block when several same-direction OBs sit
        // in the proximity band — distorting entry/SL/TP. Mirrors SbdrPlaybook.
        BigDecimal price = ctx.lastPrice();
        Optional<OrderBlockZone> anchor = input.zones().activeOrderBlocks().stream()
            .filter(o -> o.bullish() == longSide)
            .min((a, b) -> a.mid().subtract(price).abs()
                .compareTo(b.mid().subtract(price).abs()));
        if (anchor.isEmpty()) return Optional.empty();

        OrderBlockZone ob = anchor.get();
        BigDecimal entry = ob.mid();
        BigDecimal buffer = ctx.atr().multiply(BigDecimal.valueOf(SL_BUFFER_ATR));
        BigDecimal stop = longSide ? ob.bottom().subtract(buffer) : ob.top().add(buffer);

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

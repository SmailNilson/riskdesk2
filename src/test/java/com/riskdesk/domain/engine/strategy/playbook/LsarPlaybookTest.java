package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.OrderBlockZone;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LsarPlaybookTest {

    private final LsarPlaybook playbook = new LsarPlaybook();

    @Test
    void applicable_in_ranging_regime_at_value_area_low_with_discount_pd() {
        MarketContext ctx = context(MarketRegime.RANGING, MacroBias.NEUTRAL,
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT);
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void applicable_above_vah_with_premium_pd() {
        MarketContext ctx = context(MarketRegime.RANGING, MacroBias.NEUTRAL,
            PriceLocation.ABOVE_VAH, PdZone.PREMIUM);
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void not_applicable_in_trending_regime() {
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BULL,
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT);
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_inside_value_area() {
        MarketContext ctx = context(MarketRegime.RANGING, MacroBias.NEUTRAL,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM);
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_when_pd_zone_contradicts_extreme() {
        // Price below VAL but PD says PREMIUM — mismatch, reject.
        MarketContext ctx = context(MarketRegime.RANGING, MacroBias.NEUTRAL,
            PriceLocation.BELOW_VAL, PdZone.PREMIUM);
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void builds_long_plan_with_positive_rr_when_price_below_val() {
        MarketContext ctx = context(MarketRegime.RANGING, MacroBias.NEUTRAL,
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT);
        // Place a bullish OB around the current price so the anchor is found
        OrderBlockZone bullOb = new OrderBlockZone(true,
            new BigDecimal("100.50"), new BigDecimal("99.50"),
            new BigDecimal("100.00"), 75.0);
        ZoneContext zones = new ZoneContext(List.of(bullOb), List.of(), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, LsarPlaybook.ID);
        Optional<MechanicalPlan> plan = playbook.buildPlan(input);

        assertThat(plan).isPresent();
        MechanicalPlan p = plan.get();
        assertThat(p.direction()).isEqualTo(Direction.LONG);
        assertThat(p.entry()).isEqualByComparingTo("100.00");
        // Stop below OB bottom by 0.25 × ATR (1.0) = 0.25 → 99.25
        assertThat(p.stopLoss()).isEqualByComparingTo("99.25");
        // Risk = 0.75; TP1 = entry + 2R = 101.50
        assertThat(p.takeProfit1()).isEqualByComparingTo("101.50");
        assertThat(p.rrRatio()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void no_plan_when_risk_is_zero() {
        MarketContext ctx = context(MarketRegime.RANGING, MacroBias.NEUTRAL,
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT);
        // Degenerate OB where mid == bottom → risk collapses
        OrderBlockZone flat = new OrderBlockZone(true,
            new BigDecimal("100.00"), new BigDecimal("100.00"),
            new BigDecimal("100.00"), null);
        ZoneContext zones = new ZoneContext(List.of(flat), List.of(), List.of());

        // ATR zero too — no buffer, no risk
        MarketContext zeroAtr = new MarketContext(
            Instrument.MGC, "1h", MacroBias.NEUTRAL, MarketRegime.RANGING,
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new BigDecimal("100.00"), BigDecimal.ZERO, Instant.now()
        );
        StrategyInput input = new StrategyInput(zeroAtr, zones, null, null);
        assertThat(playbook.buildPlan(input)).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static MarketContext context(MarketRegime regime, MacroBias bias,
                                          PriceLocation loc, PdZone pd) {
        return new MarketContext(
            Instrument.MGC, "1h", bias, regime, loc, pd,
            new BigDecimal("100.00"), new BigDecimal("1.0"),
            Instant.parse("2026-04-17T10:00:00Z")
        );
    }
}

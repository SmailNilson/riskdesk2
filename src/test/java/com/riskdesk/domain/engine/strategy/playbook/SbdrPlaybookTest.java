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

class SbdrPlaybookTest {

    private final SbdrPlaybook playbook = new SbdrPlaybook();

    @Test
    void applicable_on_trending_bull_pullback_to_discount() {
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT);
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void applicable_on_trending_bear_pullback_to_premium() {
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BEAR,
            PriceLocation.INSIDE_VA, PdZone.PREMIUM);
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void not_applicable_when_ranging() {
        MarketContext ctx = context(MarketRegime.RANGING, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT);
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_when_pullback_in_wrong_half() {
        // BULL bias but price in PREMIUM = chasing, not pullback
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.PREMIUM);
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void no_plan_when_no_matching_direction_order_block() {
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT);
        // Only a bearish OB available → no LONG anchor
        OrderBlockZone bearOb = new OrderBlockZone(false,
            new BigDecimal("101.00"), new BigDecimal("100.50"),
            new BigDecimal("100.75"), 80.0);
        ZoneContext zones = new ZoneContext(List.of(bearOb), List.of(), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, null);
        assertThat(playbook.buildPlan(input)).isEmpty();
    }

    @Test
    void builds_plan_with_rr_at_least_3_for_trend_continuation() {
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT);
        OrderBlockZone bullOb = new OrderBlockZone(true,
            new BigDecimal("100.50"), new BigDecimal("99.50"),
            new BigDecimal("100.00"), 85.0);
        ZoneContext zones = new ZoneContext(List.of(bullOb), List.of(), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, null);
        Optional<MechanicalPlan> plan = playbook.buildPlan(input);

        assertThat(plan).isPresent();
        MechanicalPlan p = plan.get();
        assertThat(p.direction()).isEqualTo(Direction.LONG);
        assertThat(p.entry()).isEqualByComparingTo("100.00");
        assertThat(p.stopLoss()).isEqualByComparingTo("99.25");
        // risk = 0.75, TP1 = entry + 3R = 102.25
        assertThat(p.takeProfit1()).isEqualByComparingTo("102.25");
        assertThat(p.rrRatio()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(0.01));
    }

    private static MarketContext context(MarketRegime regime, MacroBias bias,
                                          PriceLocation loc, PdZone pd) {
        return new MarketContext(
            Instrument.MNQ, "1h", bias, regime, loc, pd,
            new BigDecimal("100.00"), new BigDecimal("1.0"),
            Instant.parse("2026-04-17T10:00:00Z")
        );
    }
}

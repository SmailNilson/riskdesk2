package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.MtfSnapshot;
import com.riskdesk.domain.engine.strategy.model.OrderBlockZone;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PortfolioState;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.SessionInfo;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualPullbackPlaybookTest {

    private final ContextualPullbackPlaybook playbook = new ContextualPullbackPlaybook();
    private static final Instant AT = Instant.parse("2026-04-17T15:00:00Z");

    @Test
    void applicable_when_trending_with_known_bias() {
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM);
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void applicable_when_ranging_with_known_bias() {
        // Catch-all: even RANGING + INSIDE_VA fires, which is the empirical state
        // that left the system on NO_TRADE permanently before this fallback.
        MarketContext ctx = context(MarketRegime.RANGING, MacroBias.BEAR,
            PriceLocation.INSIDE_VA, PdZone.UNKNOWN);
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void not_applicable_when_choppy() {
        MarketContext ctx = context(MarketRegime.CHOPPY, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM);
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_when_regime_unknown() {
        MarketContext ctx = context(MarketRegime.UNKNOWN, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM);
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_without_macro_bias() {
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.NEUTRAL,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM);
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void builds_long_plan_with_matching_bullish_ob() {
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT);
        OrderBlockZone bullOb = new OrderBlockZone(true,
            new BigDecimal("100.50"), new BigDecimal("99.50"),
            new BigDecimal("100.00"), 75.0);
        ZoneContext zones = new ZoneContext(List.of(bullOb), List.of(), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, ContextualPullbackPlaybook.ID);
        Optional<MechanicalPlan> plan = playbook.buildPlan(input);

        assertThat(plan).isPresent();
        MechanicalPlan p = plan.get();
        assertThat(p.direction()).isEqualTo(Direction.LONG);
        assertThat(p.entry()).isEqualByComparingTo("100.00");
        // OB bottom 99.50 minus 0.25 ATR (= 0.25 with ATR=1.0) → SL = 99.25
        assertThat(p.stopLoss()).isEqualByComparingTo("99.25");
        // risk = 0.75, TP1 at 2R = 101.50
        assertThat(p.takeProfit1()).isEqualByComparingTo("101.50");
        // TP2 at 3.5R = 102.625
        assertThat(p.takeProfit2()).isEqualByComparingTo("102.625");
    }

    @Test
    void builds_short_plan_with_matching_bearish_ob() {
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BEAR,
            PriceLocation.INSIDE_VA, PdZone.PREMIUM);
        OrderBlockZone bearOb = new OrderBlockZone(false,
            new BigDecimal("100.50"), new BigDecimal("99.50"),
            new BigDecimal("100.00"), 80.0);
        ZoneContext zones = new ZoneContext(List.of(bearOb), List.of(), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, ContextualPullbackPlaybook.ID);
        Optional<MechanicalPlan> plan = playbook.buildPlan(input);

        assertThat(plan).isPresent();
        MechanicalPlan p = plan.get();
        assertThat(p.direction()).isEqualTo(Direction.SHORT);
        assertThat(p.entry()).isEqualByComparingTo("100.00");
        assertThat(p.stopLoss()).isEqualByComparingTo("100.75");
        assertThat(p.takeProfit1()).isEqualByComparingTo("98.50");
    }

    @Test
    void no_plan_when_no_matching_direction_ob() {
        // BULL bias but only a bearish OB nearby → no plan
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM);
        OrderBlockZone bearOb = new OrderBlockZone(false,
            new BigDecimal("100.50"), new BigDecimal("99.50"),
            new BigDecimal("100.00"), 75.0);
        ZoneContext zones = new ZoneContext(List.of(bearOb), List.of(), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, ContextualPullbackPlaybook.ID);
        assertThat(playbook.buildPlan(input)).isEmpty();
    }

    @Test
    void picks_nearest_ob_when_multiple_same_direction_blocks_present() {
        // Three bullish OBs in the snapshot: the FIRST one in iteration order is
        // farthest from price. A naive findFirst() would anchor there; the
        // distance-based selection must pick the middle OB (closest to 100.00).
        MarketContext ctx = context(MarketRegime.TRENDING, MacroBias.BULL,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT);

        OrderBlockZone far    = new OrderBlockZone(true,
            new BigDecimal("96.50"), new BigDecimal("95.50"),
            new BigDecimal("96.00"), 80.0);
        OrderBlockZone nearest = new OrderBlockZone(true,
            new BigDecimal("99.80"), new BigDecimal("98.80"),
            new BigDecimal("99.30"), 85.0);
        OrderBlockZone medium = new OrderBlockZone(true,
            new BigDecimal("98.50"), new BigDecimal("97.50"),
            new BigDecimal("98.00"), 75.0);

        // Order matters: far first to expose the bug.
        ZoneContext zones = new ZoneContext(List.of(far, nearest, medium), List.of(), List.of());
        StrategyInput input = new StrategyInput(ctx, zones, null, ContextualPullbackPlaybook.ID);
        Optional<MechanicalPlan> plan = playbook.buildPlan(input);

        assertThat(plan).isPresent();
        // Entry must be the nearest OB's midpoint, not the first one's.
        assertThat(plan.get().entry()).isEqualByComparingTo("99.30");
    }

    @Test
    void minimum_score_higher_than_specific_playbooks() {
        // Permissive applicability is offset by stricter scoring threshold.
        assertThat(playbook.minimumScoreForExecution()).isEqualTo(65.0);
    }

    @Test
    void id_is_ctx() {
        assertThat(playbook.id()).isEqualTo("CTX");
    }

    private static MarketContext context(MarketRegime regime, MacroBias bias,
                                          PriceLocation loc, PdZone pd) {
        return new MarketContext(
            Instrument.MGC, "1h",
            bias, regime,
            loc, pd,
            new BigDecimal("100.00"), new BigDecimal("1.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(), SessionInfo.unknown(), AT
        );
    }
}

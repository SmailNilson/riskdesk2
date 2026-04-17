package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.FvgZone;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.MtfSnapshot;
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

class SilverBulletPlaybookTest {

    private final SilverBulletPlaybook playbook = new SilverBulletPlaybook();
    private static final Instant AT = Instant.parse("2026-04-17T14:00:00Z"); // 10:00 ET, SB window

    @Test
    void applicable_in_ny_am_kill_zone_with_bull_bias() {
        MarketContext ctx = context(MacroBias.BULL,
            new SessionInfo("NY_AM", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void applicable_in_ny_am_kill_zone_with_bear_bias() {
        MarketContext ctx = context(MacroBias.BEAR,
            new SessionInfo("NY_AM", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void not_applicable_with_neutral_bias() {
        MarketContext ctx = context(MacroBias.NEUTRAL,
            new SessionInfo("NY_AM", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_outside_ny_am() {
        MarketContext ctx = context(MacroBias.BULL,
            new SessionInfo("LONDON", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_outside_kill_zone() {
        MarketContext ctx = context(MacroBias.BULL,
            new SessionInfo("NY_AM", false, true, false));
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void no_plan_without_matching_direction_fvg() {
        MarketContext ctx = context(MacroBias.BULL,
            new SessionInfo("NY_AM", true, true, false));
        // Only bearish FVG available for a BULL bias → no anchor
        FvgZone bearFvg = new FvgZone(false,
            new BigDecimal("101.00"), new BigDecimal("100.50"), 0.0);
        ZoneContext zones = new ZoneContext(List.of(), List.of(bearFvg), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, null);
        assertThat(playbook.buildPlan(input)).isEmpty();
    }

    @Test
    void no_plan_when_matching_fvg_already_more_than_half_filled() {
        MarketContext ctx = context(MacroBias.BULL,
            new SessionInfo("NY_AM", true, true, false));
        // Bullish FVG exists but 70% filled — not actionable
        FvgZone filledFvg = new FvgZone(true,
            new BigDecimal("101.00"), new BigDecimal("100.00"), 0.70);
        ZoneContext zones = new ZoneContext(List.of(), List.of(filledFvg), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, null);
        assertThat(playbook.buildPlan(input)).isEmpty();
    }

    @Test
    void builds_long_plan_with_midpoint_entry_and_2p5r_tp() {
        MarketContext ctx = context(MacroBias.BULL,
            new SessionInfo("NY_AM", true, true, false));
        FvgZone bullFvg = new FvgZone(true,
            new BigDecimal("101.00"), new BigDecimal("100.00"), 0.0);
        ZoneContext zones = new ZoneContext(List.of(), List.of(bullFvg), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, SilverBulletPlaybook.ID);
        Optional<MechanicalPlan> plan = playbook.buildPlan(input);

        assertThat(plan).isPresent();
        MechanicalPlan p = plan.get();
        assertThat(p.direction()).isEqualTo(Direction.LONG);
        assertThat(p.entry()).isEqualByComparingTo("100.50");
        // FVG bottom = 100, buffer 0.25 (ATR=1.0), SL = 99.75
        assertThat(p.stopLoss()).isEqualByComparingTo("99.75");
        // risk = 0.75, TP1 = entry + 2.5R = 100.50 + 1.875 = 102.375
        assertThat(p.takeProfit1()).isEqualByComparingTo("102.375");
        assertThat(p.rrRatio()).isCloseTo(2.5, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void minimum_score_higher_than_reversal_setups() {
        // SB is trend-continuation — harder to catch, demands more conviction
        assertThat(playbook.minimumScoreForExecution()).isEqualTo(60.0);
    }

    private static MarketContext context(MacroBias bias, SessionInfo session) {
        return new MarketContext(
            Instrument.MNQ, "1h",
            bias, MarketRegime.TRENDING,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT,
            new BigDecimal("100.00"), new BigDecimal("1.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(), session, AT
        );
    }
}

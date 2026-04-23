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
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NyOpenReversalPlaybookTest {

    private final NyOpenReversalPlaybook playbook = new NyOpenReversalPlaybook();
    private static final Instant AT = Instant.parse("2026-04-17T13:30:00Z"); // 09:30 ET, NY AM

    @Test
    void applicable_in_ny_am_kill_zone_at_value_area_low() {
        MarketContext ctx = context(
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new SessionInfo("NY_AM", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void applicable_above_vah_with_premium_pd() {
        MarketContext ctx = context(
            PriceLocation.ABOVE_VAH, PdZone.PREMIUM,
            new SessionInfo("NY_AM", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void not_applicable_outside_kill_zone() {
        // NY_AM phase but kill zone ended (e.g. 11:30 ET)
        MarketContext ctx = context(
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new SessionInfo("NY_AM", false, true, false));
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_during_london_kill_zone() {
        // Kill zone active but wrong phase — LondonSweepPlaybook handles this
        MarketContext ctx = context(
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new SessionInfo("LONDON", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_when_session_info_unknown() {
        MarketContext ctx = context(
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            SessionInfo.unknown());
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_inside_value_area() {
        MarketContext ctx = context(
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM,
            new SessionInfo("NY_AM", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_when_pd_zone_contradicts_extreme() {
        // Price below VAL but PD says PREMIUM — mismatch
        MarketContext ctx = context(
            PriceLocation.BELOW_VAL, PdZone.PREMIUM,
            new SessionInfo("NY_AM", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void builds_long_plan_with_correct_rr_when_below_val() {
        MarketContext ctx = context(
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new SessionInfo("NY_AM", true, true, false));
        OrderBlockZone bullOb = new OrderBlockZone(true,
            new BigDecimal("100.50"), new BigDecimal("99.50"),
            new BigDecimal("100.00"), 80.0);
        ZoneContext zones = new ZoneContext(List.of(bullOb), List.of(), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, NyOpenReversalPlaybook.ID);
        Optional<MechanicalPlan> plan = playbook.buildPlan(input);

        assertThat(plan).isPresent();
        MechanicalPlan p = plan.get();
        assertThat(p.direction()).isEqualTo(Direction.LONG);
        assertThat(p.entry()).isEqualByComparingTo("100.00");
        assertThat(p.stopLoss()).isEqualByComparingTo("99.25");
        assertThat(p.takeProfit1()).isEqualByComparingTo("101.50");
        assertThat(p.rrRatio()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void builds_plan_without_ob_anchor_using_last_price() {
        // No OB available — plan should fall back to lastPrice + tolerance
        MarketContext ctx = context(
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new SessionInfo("NY_AM", true, true, false));
        StrategyInput input = new StrategyInput(ctx, ZoneContext.empty(), null, null);
        Optional<MechanicalPlan> plan = playbook.buildPlan(input);

        assertThat(plan).isPresent();
        assertThat(plan.get().direction()).isEqualTo(Direction.LONG);
    }

    @Test
    void minimum_score_matches_lsar_convention() {
        assertThat(playbook.minimumScoreForExecution()).isEqualTo(55.0);
    }

    private static MarketContext context(PriceLocation loc, PdZone pd, SessionInfo session) {
        return new MarketContext(
            Instrument.MGC, "1h",
            MacroBias.NEUTRAL, MarketRegime.RANGING,
            loc, pd,
            new BigDecimal("100.00"), new BigDecimal("1.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(), session, AT
        );
    }
}

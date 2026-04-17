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

class LondonSweepPlaybookTest {

    private final LondonSweepPlaybook playbook = new LondonSweepPlaybook();
    private static final Instant AT = Instant.parse("2026-04-17T07:00:00Z"); // 03:00 ET, London

    @Test
    void applicable_in_london_kill_zone_at_extreme() {
        MarketContext ctx = context(
            PriceLocation.ABOVE_VAH, PdZone.PREMIUM,
            new SessionInfo("LONDON", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isTrue();
    }

    @Test
    void not_applicable_during_ny_kill_zone() {
        // NOR handles NY_AM — LS should pass on it
        MarketContext ctx = context(
            PriceLocation.ABOVE_VAH, PdZone.PREMIUM,
            new SessionInfo("NY_AM", true, true, false));
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void not_applicable_outside_kill_zone() {
        MarketContext ctx = context(
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new SessionInfo("LONDON", false, true, false));
        assertThat(playbook.isApplicable(ctx)).isFalse();
    }

    @Test
    void builds_short_plan_with_correct_rr_when_above_vah() {
        MarketContext ctx = context(
            PriceLocation.ABOVE_VAH, PdZone.PREMIUM,
            new SessionInfo("LONDON", true, true, false));
        OrderBlockZone bearOb = new OrderBlockZone(false,
            new BigDecimal("100.50"), new BigDecimal("99.50"),
            new BigDecimal("100.00"), 75.0);
        ZoneContext zones = new ZoneContext(List.of(bearOb), List.of(), List.of());

        StrategyInput input = new StrategyInput(ctx, zones, null, LondonSweepPlaybook.ID);
        Optional<MechanicalPlan> plan = playbook.buildPlan(input);

        assertThat(plan).isPresent();
        MechanicalPlan p = plan.get();
        assertThat(p.direction()).isEqualTo(Direction.SHORT);
        assertThat(p.entry()).isEqualByComparingTo("100.00");
        // OB top = 100.50, SL buffer 0.25, SL = 100.75
        assertThat(p.stopLoss()).isEqualByComparingTo("100.75");
        // risk 0.75, TP1 = entry - 2R = 98.50
        assertThat(p.takeProfit1()).isEqualByComparingTo("98.50");
    }

    @Test
    void minimum_score_matches_reversal_convention() {
        assertThat(playbook.minimumScoreForExecution()).isEqualTo(55.0);
    }

    @Test
    void id_is_ls() {
        assertThat(playbook.id()).isEqualTo("LS");
    }

    private static MarketContext context(PriceLocation loc, PdZone pd, SessionInfo session) {
        return new MarketContext(
            Instrument.E6, "1h",
            MacroBias.NEUTRAL, MarketRegime.RANGING,
            loc, pd,
            new BigDecimal("100.00"), new BigDecimal("1.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(), session, AT
        );
    }
}

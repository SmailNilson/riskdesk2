package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MtfSnapshot;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PortfolioState;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.SessionInfo;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RiskGateAgentTest {

    private final RiskGateAgent agent = new RiskGateAgent();
    private static final Instant AT = Instant.parse("2026-04-17T10:00:00Z");

    @Test
    void abstains_when_portfolio_state_unknown() {
        AgentVote v = agent.evaluate(input(PortfolioState.unknown()));
        assertThat(v.abstain()).isTrue();
        assertThat(v.hasVeto()).isFalse();
    }

    @Test
    void vetoes_when_daily_drawdown_exceeds_3_percent() {
        PortfolioState p = new PortfolioState(-500.0, 3.5, 2, false, 40.0);
        AgentVote v = agent.evaluate(input(p));
        assertThat(v.hasVeto()).isTrue();
        assertThat(v.vetoReason().get()).contains("daily-drawdown-breach");
    }

    @Test
    void vetoes_when_margin_utilisation_over_80_percent() {
        PortfolioState p = new PortfolioState(0.0, 1.0, 2, false, 85.0);
        AgentVote v = agent.evaluate(input(p));
        assertThat(v.hasVeto()).isTrue();
        assertThat(v.vetoReason().get()).contains("margin-near-limit");
    }

    @Test
    void vetoes_on_correlated_position_at_cap() {
        PortfolioState p = new PortfolioState(100.0, 1.0, 3, true, 40.0);
        AgentVote v = agent.evaluate(input(p));
        assertThat(v.hasVeto()).isTrue();
        assertThat(v.vetoReason().get()).contains("correlated-position-cap");
    }

    @Test
    void no_veto_on_three_positions_without_correlation() {
        // Boundary: at cap but no correlated exposure → allow
        PortfolioState p = new PortfolioState(100.0, 1.0, 3, false, 40.0);
        AgentVote v = agent.evaluate(input(p));
        assertThat(v.hasVeto()).isFalse();
        assertThat(v.abstain()).isFalse();
        assertThat(v.directionalVote()).isZero();
    }

    @Test
    void drawdown_gate_has_priority_over_margin_gate() {
        // Both thresholds breached — the veto message should mention drawdown
        PortfolioState p = new PortfolioState(-2_000.0, 5.0, 1, false, 95.0);
        AgentVote v = agent.evaluate(input(p));
        assertThat(v.vetoReason().get()).contains("daily-drawdown-breach");
    }

    @Test
    void happy_path_produces_zero_directional_vote_with_active_evidence() {
        PortfolioState p = new PortfolioState(200.0, 0.5, 1, false, 20.0);
        AgentVote v = agent.evaluate(input(p));
        assertThat(v.abstain()).isFalse();
        assertThat(v.hasVeto()).isFalse();
        assertThat(v.directionalVote()).isZero();
        assertThat(v.confidence()).isPositive();
        assertThat(v.evidence()).anyMatch(e -> e.contains("Risk gates ok"));
    }

    @Test
    void layer_is_context() {
        assertThat(agent.layer()).isEqualTo(StrategyLayer.CONTEXT);
    }

    private static StrategyInput input(PortfolioState portfolio) {
        MarketContext ctx = new MarketContext(
            Instrument.MGC, "1h",
            MacroBias.BULL, MarketRegime.TRENDING,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT,
            new BigDecimal("2000"), new BigDecimal("5.0"),
            MtfSnapshot.neutral(), portfolio, SessionInfo.unknown(), AT
        );
        return new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null);
    }
}

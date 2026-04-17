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

class SessionTimingAgentTest {

    private final SessionTimingAgent agent = new SessionTimingAgent();
    private static final Instant AT = Instant.parse("2026-04-17T10:00:00Z");

    @Test
    void abstains_when_session_info_unknown() {
        AgentVote v = agent.evaluate(input("1h", SessionInfo.unknown()));
        assertThat(v.abstain()).isTrue();
    }

    @Test
    void vetoes_during_maintenance_window() {
        SessionInfo s = new SessionInfo("CLOSE", false, true, true);
        AgentVote v = agent.evaluate(input("1h", s));
        assertThat(v.hasVeto()).isTrue();
        assertThat(v.vetoReason().get()).contains("maintenance-window");
    }

    @Test
    void vetoes_when_market_closed() {
        SessionInfo s = new SessionInfo("CLOSE", false, false, false);
        AgentVote v = agent.evaluate(input("1h", s));
        assertThat(v.hasVeto()).isTrue();
        assertThat(v.vetoReason().get()).contains("market-closed");
    }

    @Test
    void vetoes_5m_outside_kill_zone() {
        SessionInfo s = new SessionInfo("NY_PM", false, true, false);
        AgentVote v = agent.evaluate(input("5m", s));
        assertThat(v.hasVeto()).isTrue();
        assertThat(v.vetoReason().get()).contains("5m-outside-kill-zone");
    }

    @Test
    void allows_5m_inside_kill_zone() {
        SessionInfo s = new SessionInfo("NY_AM", true, true, false);
        AgentVote v = agent.evaluate(input("5m", s));
        assertThat(v.hasVeto()).isFalse();
    }

    @Test
    void does_not_apply_kill_zone_gate_to_hourly_tf() {
        // Even outside a kill zone, H1 / H4 / D setups should not be vetoed by
        // the session timing agent — the gate is scoped to 5m only.
        SessionInfo s = new SessionInfo("NY_PM", false, true, false);
        AgentVote v = agent.evaluate(input("1h", s));
        assertThat(v.hasVeto()).isFalse();
    }

    @Test
    void asian_session_produces_negative_vote_with_low_confidence() {
        SessionInfo s = new SessionInfo("ASIAN", false, true, false);
        AgentVote v = agent.evaluate(input("1h", s));
        assertThat(v.hasVeto()).isFalse();
        assertThat(v.abstain()).isFalse();
        assertThat(v.directionalVote()).isNegative();
        assertThat(v.confidence()).isLessThan(0.5);
    }

    @Test
    void ny_am_session_produces_all_clear_evidence() {
        SessionInfo s = new SessionInfo("NY_AM", true, true, false);
        AgentVote v = agent.evaluate(input("1h", s));
        assertThat(v.hasVeto()).isFalse();
        assertThat(v.evidence().toString()).contains("NY_AM");
        assertThat(v.evidence().toString()).containsIgnoringCase("high-liquidity");
    }

    @Test
    void layer_is_context() {
        assertThat(agent.layer()).isEqualTo(StrategyLayer.CONTEXT);
    }

    private static StrategyInput input(String timeframe, SessionInfo session) {
        MarketContext ctx = new MarketContext(
            Instrument.MGC, timeframe,
            MacroBias.BULL, MarketRegime.TRENDING,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT,
            new BigDecimal("2000"), new BigDecimal("5.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(), session, AT
        );
        return new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null);
    }
}

package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.IndicatorContext;
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

class BollingerPositionAgentTest {

    private final BollingerPositionAgent agent = new BollingerPositionAgent();
    private static final Instant AT = Instant.parse("2026-04-17T15:00:00Z");

    @Test
    void layer_is_context() {
        assertThat(agent.layer()).isEqualTo(StrategyLayer.CONTEXT);
        assertThat(agent.id()).isEqualTo("bollinger-position");
    }

    @Test
    void abstains_when_bb_pct_missing() {
        AgentVote v = agent.evaluate(input(IndicatorContext.empty()));
        assertThat(v.abstain()).isTrue();
    }

    @Test
    void near_lower_band_votes_long() {
        AgentVote v = agent.evaluate(input(bb(0.05)));
        assertThat(v.directionalVote()).isEqualTo(50);
        assertThat(v.confidence()).isEqualTo(0.6);
        assertThat(v.evidence().toString()).contains("lower band");
        assertThat(v.evidence().toString()).contains("0.05");
    }

    @Test
    void near_upper_band_votes_short() {
        AgentVote v = agent.evaluate(input(bb(0.95)));
        assertThat(v.directionalVote()).isEqualTo(-50);
        assertThat(v.confidence()).isEqualTo(0.6);
        assertThat(v.evidence().toString()).contains("upper band");
    }

    @Test
    void mid_band_abstains() {
        AgentVote v = agent.evaluate(input(bb(0.50)));
        assertThat(v.abstain()).isTrue();
        assertThat(v.evidence().toString()).contains("mid-band");
    }

    @Test
    void lower_half_moderate_votes_mild_long() {
        AgentVote v = agent.evaluate(input(bb(0.25)));
        assertThat(v.directionalVote()).isEqualTo(25);
        assertThat(v.confidence()).isEqualTo(0.3);
    }

    @Test
    void upper_half_moderate_votes_mild_short() {
        AgentVote v = agent.evaluate(input(bb(0.75)));
        assertThat(v.directionalVote()).isEqualTo(-25);
        assertThat(v.confidence()).isEqualTo(0.3);
    }

    @Test
    void boundary_at_extreme_low_threshold() {
        // bbPct = 0.10 — boundary: NOT < 0.10 → falls into 0.10..0.40 moderate bucket.
        AgentVote v = agent.evaluate(input(bb(0.10)));
        assertThat(v.directionalVote()).isEqualTo(25);
    }

    private static IndicatorContext bb(double bbPct) {
        return new IndicatorContext(null, null, null,
            BigDecimal.valueOf(bbPct), new BigDecimal("3.0"), null, null);
    }

    private static StrategyInput input(IndicatorContext indicators) {
        MarketContext ctx = new MarketContext(
            Instrument.MNQ, "5m",
            MacroBias.NEUTRAL, MarketRegime.RANGING,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM,
            new BigDecimal("100"), new BigDecimal("5.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(),
            SessionInfo.unknown(), AT, indicators
        );
        return new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null);
    }
}

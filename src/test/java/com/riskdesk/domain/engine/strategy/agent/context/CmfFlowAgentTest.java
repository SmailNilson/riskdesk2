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
import static org.assertj.core.data.Offset.offset;

class CmfFlowAgentTest {

    private final CmfFlowAgent agent = new CmfFlowAgent();
    private static final Instant AT = Instant.parse("2026-04-17T15:00:00Z");

    @Test
    void layer_is_context() {
        assertThat(agent.layer()).isEqualTo(StrategyLayer.CONTEXT);
        assertThat(agent.id()).isEqualTo("cmf-flow");
    }

    @Test
    void abstains_when_cmf_missing() {
        AgentVote v = agent.evaluate(input(IndicatorContext.empty()));
        assertThat(v.abstain()).isTrue();
    }

    @Test
    void strong_buy_votes_long_max() {
        AgentVote v = agent.evaluate(input(cmf(0.20)));
        assertThat(v.directionalVote()).isEqualTo(70);
        assertThat(v.confidence()).isEqualTo(0.80, offset(0.001));
        assertThat(v.evidence().toString()).contains("strong-buy");
    }

    @Test
    void mild_buy_votes_long() {
        AgentVote v = agent.evaluate(input(cmf(0.10)));
        assertThat(v.directionalVote()).isEqualTo(30);
        assertThat(v.evidence().toString()).contains("mild-buy");
    }

    @Test
    void strong_sell_votes_short_max() {
        AgentVote v = agent.evaluate(input(cmf(-0.30)));
        assertThat(v.directionalVote()).isEqualTo(-70);
        // |cmf| = 0.30 → 0.30 * 4 = 1.2 → capped at 1.0
        assertThat(v.confidence()).isEqualTo(1.0);
        assertThat(v.evidence().toString()).contains("strong-sell");
    }

    @Test
    void mild_sell_votes_short() {
        AgentVote v = agent.evaluate(input(cmf(-0.08)));
        assertThat(v.directionalVote()).isEqualTo(-30);
        assertThat(v.evidence().toString()).contains("mild-sell");
    }

    @Test
    void neutral_band_abstains() {
        AgentVote v = agent.evaluate(input(cmf(0.02)));
        assertThat(v.abstain()).isTrue();
        assertThat(v.evidence().toString()).contains("neutral");
    }

    @Test
    void boundary_at_mild_threshold_abstains() {
        // |cmf| == 0.05 — exactly on the threshold, abstains (<= MILD_THRESHOLD).
        AgentVote v = agent.evaluate(input(cmf(0.05)));
        assertThat(v.abstain()).isTrue();
    }

    private static IndicatorContext cmf(double cmf) {
        return new IndicatorContext(null, null, null, null, null,
            BigDecimal.valueOf(cmf), BigDecimal.ZERO);
    }

    private static StrategyInput input(IndicatorContext indicators) {
        MarketContext ctx = new MarketContext(
            Instrument.MNQ, "5m",
            MacroBias.NEUTRAL, MarketRegime.TRENDING,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM,
            new BigDecimal("100"), new BigDecimal("5.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(),
            SessionInfo.unknown(), AT, indicators
        );
        return new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null);
    }
}

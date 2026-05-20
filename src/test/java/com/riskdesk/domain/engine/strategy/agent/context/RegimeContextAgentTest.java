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
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RegimeContextAgentTest {

    private final RegimeContextAgent agent = new RegimeContextAgent();
    private static final Instant AT = Instant.parse("2026-04-30T14:00:00Z");

    @Test
    void trending_with_aligned_macro_bias_uses_full_magnitude() {
        StrategyInput in = input(MarketRegime.TRENDING, MacroBias.BEAR, MacroBias.NEUTRAL);
        AgentVote vote = agent.evaluate(in);

        assertThat(vote.abstain()).isFalse();
        assertThat(vote.directionalVote()).isEqualTo(-50);
        assertThat(vote.confidence()).isCloseTo(0.75, within(0.001));
    }

    @Test
    void trending_with_neutral_macro_and_bear_momentum_hint_uses_fallback_vote() {
        // Replicates the MCL 2026-04-30 incident: regime detector flipped to TRENDING
        // via the fast-path, but the swing-derived macro bias is still NEUTRAL.
        // The agent must recover a directional vote using the momentum hint.
        StrategyInput in = input(MarketRegime.TRENDING, MacroBias.NEUTRAL, MacroBias.BEAR);
        AgentVote vote = agent.evaluate(in);

        assertThat(vote.abstain()).isFalse();
        assertThat(vote.directionalVote()).isEqualTo(-35);
        assertThat(vote.confidence()).isCloseTo(0.5, within(0.001));
        assertThat(vote.evidence().toString()).contains("momentum hint", "fast-path");
    }

    @Test
    void trending_with_neutral_macro_and_bull_momentum_hint_votes_long() {
        StrategyInput in = input(MarketRegime.TRENDING, MacroBias.NEUTRAL, MacroBias.BULL);
        AgentVote vote = agent.evaluate(in);

        assertThat(vote.directionalVote()).isEqualTo(+35);
        assertThat(vote.confidence()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void trending_with_neutral_macro_and_neutral_hint_votes_zero() {
        // Without a momentum hint we have no way to recover a sign — the existing
        // behaviour (zero vote) is preserved.
        StrategyInput in = input(MarketRegime.TRENDING, MacroBias.NEUTRAL, MacroBias.NEUTRAL);
        AgentVote vote = agent.evaluate(in);

        assertThat(vote.directionalVote()).isZero();
    }

    @Test
    void choppy_regime_does_not_use_momentum_hint() {
        // The hint fallback is gated to TRENDING only — CHOPPY semantics are unchanged.
        StrategyInput in = input(MarketRegime.CHOPPY, MacroBias.NEUTRAL, MacroBias.BEAR);
        AgentVote vote = agent.evaluate(in);

        assertThat(vote.directionalVote()).isZero();
        assertThat(vote.confidence()).isCloseTo(0.4, within(0.001));
    }

    @Test
    void aligned_macro_bias_takes_precedence_over_momentum_hint() {
        // When the macro bias is already directional, the existing logic owns the
        // vote — momentum hint only fills in when bias=NEUTRAL.
        StrategyInput in = input(MarketRegime.TRENDING, MacroBias.BULL, MacroBias.BEAR);
        AgentVote vote = agent.evaluate(in);

        // BULL bias produces +50 ; BEAR hint is ignored.
        assertThat(vote.directionalVote()).isEqualTo(+50);
        assertThat(vote.confidence()).isCloseTo(0.75, within(0.001));
    }

    @Test
    void unknown_regime_abstains() {
        StrategyInput in = input(MarketRegime.UNKNOWN, MacroBias.BULL, MacroBias.BULL);
        AgentVote vote = agent.evaluate(in);

        assertThat(vote.abstain()).isTrue();
    }

    private static StrategyInput input(MarketRegime regime, MacroBias bias, MacroBias momentumHint) {
        MarketContext ctx = new MarketContext(
            Instrument.MCL, "5m",
            bias, regime,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM,
            new BigDecimal("80.50"), new BigDecimal("0.30"),
            MtfSnapshot.neutral(), PortfolioState.unknown(), SessionInfo.unknown(),
            AT, IndicatorContext.empty(), momentumHint
        );
        return new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null);
    }
}

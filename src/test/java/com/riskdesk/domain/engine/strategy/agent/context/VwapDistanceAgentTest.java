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

class VwapDistanceAgentTest {

    private final VwapDistanceAgent agent = new VwapDistanceAgent();
    private static final Instant AT = Instant.parse("2026-04-17T15:00:00Z");

    @Test
    void layer_is_context() {
        assertThat(agent.layer()).isEqualTo(StrategyLayer.CONTEXT);
        assertThat(agent.id()).isEqualTo("vwap-distance");
    }

    @Test
    void abstains_when_vwap_missing() {
        AgentVote v = agent.evaluate(input(new BigDecimal("100"), IndicatorContext.empty()));
        assertThat(v.abstain()).isTrue();
        assertThat(v.evidence().toString()).contains("VWAP");
    }

    @Test
    void abstains_when_last_price_null() {
        IndicatorContext ind = vwapBands(new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("98"));
        AgentVote v = agent.evaluate(input(null, ind));
        assertThat(v.abstain()).isTrue();
    }

    @Test
    void price_far_above_vwap_votes_short() {
        // VWAP=100, upper=102 → 1σ = 2. Price=104 → +2σ → above EXTREME_SIGMA(1.5).
        IndicatorContext ind = vwapBands(new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("98"));
        AgentVote v = agent.evaluate(input(new BigDecimal("104"), ind));
        assertThat(v.abstain()).isFalse();
        assertThat(v.directionalVote()).isEqualTo(-60);
        assertThat(v.evidence().toString()).contains("Far above VWAP");
        assertThat(v.confidence()).isBetween(0.6, 0.7);
    }

    @Test
    void price_far_below_vwap_votes_long() {
        // VWAP=100, lower=98 → 1σ_down = 2. Price=96 → −2σ.
        IndicatorContext ind = vwapBands(new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("98"));
        AgentVote v = agent.evaluate(input(new BigDecimal("96"), ind));
        assertThat(v.directionalVote()).isEqualTo(60);
        assertThat(v.evidence().toString()).contains("Far below VWAP");
    }

    @Test
    void price_aligned_with_vwap_votes_zero() {
        // 0.25σ from VWAP — inside neutral band.
        IndicatorContext ind = vwapBands(new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("98"));
        AgentVote v = agent.evaluate(input(new BigDecimal("100.5"), ind));
        assertThat(v.abstain()).isFalse();
        assertThat(v.directionalVote()).isZero();
        assertThat(v.evidence().toString()).contains("aligned");
    }

    @Test
    void moderate_distance_produces_scaled_short_vote() {
        // VWAP=100, upper=102, price=102 → exactly 1σ — between neutral(0.5) and extreme(1.5).
        IndicatorContext ind = vwapBands(new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("98"));
        AgentVote v = agent.evaluate(input(new BigDecimal("102"), ind));
        assertThat(v.directionalVote()).isNegative();
        assertThat(v.directionalVote()).isGreaterThan(-60);
    }

    @Test
    void band_collapse_does_not_throw() {
        // Bands collapsed to VWAP — denom would be 0 without the floor guard.
        IndicatorContext ind = vwapBands(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"));
        AgentVote v = agent.evaluate(input(new BigDecimal("100.10"), ind));
        // 0.10 / 0.01 floor = 10σ → far-above, vote -60.
        assertThat(v.directionalVote()).isEqualTo(-60);
        assertThat(v.confidence()).isEqualTo(1.0);
    }

    private static IndicatorContext vwapBands(BigDecimal vwap, BigDecimal upper, BigDecimal lower) {
        return new IndicatorContext(vwap, lower, upper, null, null, null, null);
    }

    private static StrategyInput input(BigDecimal lastPrice, IndicatorContext indicators) {
        MarketContext ctx = new MarketContext(
            Instrument.MNQ, "5m",
            MacroBias.NEUTRAL, MarketRegime.RANGING,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM,
            lastPrice, new BigDecimal("5.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(),
            SessionInfo.unknown(), AT, indicators
        );
        return new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null);
    }
}

package com.riskdesk.domain.engine.strategy.agent.trigger;

import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.DeltaSignature;
import com.riskdesk.domain.engine.strategy.model.DomSignal;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.ReactionPattern;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import com.riskdesk.domain.engine.strategy.model.TickDataQuality;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Maps the {@link OrderFlowPattern} 4-quadrant verdict to a signed LSAR vote.
 * Verifies the full table + abstain branches + quality multiplier.
 */
class QuantFlowPatternAgentTest {

    private static final Instant NOW = Instant.parse("2026-04-29T18:00:00Z");
    private final QuantFlowPatternAgent agent = new QuantFlowPatternAgent();

    @Test
    @DisplayName("VRAI_ACHAT HIGH → +90 vote, conf 0.85 × quality")
    void vraiAchatHigh_realTicks() {
        AgentVote v = agent.evaluate(input(pattern(OrderFlowPattern.VRAI_ACHAT,
            PatternAnalysis.Confidence.HIGH), TickDataQuality.REAL_TICKS));

        assertThat(v.abstain()).isFalse();
        assertThat(v.directionalVote()).isEqualTo(+90);
        assertThat(v.confidence()).isCloseTo(0.85, within(1e-6));
        assertThat(v.layer()).isEqualTo(StrategyLayer.TRIGGER);
    }

    @Test
    @DisplayName("VRAIE_VENTE HIGH → -90 vote, conf 0.85 × quality")
    void vraieVenteHigh_realTicks() {
        AgentVote v = agent.evaluate(input(pattern(OrderFlowPattern.VRAIE_VENTE,
            PatternAnalysis.Confidence.HIGH), TickDataQuality.REAL_TICKS));

        assertThat(v.directionalVote()).isEqualTo(-90);
        assertThat(v.confidence()).isCloseTo(0.85, within(1e-6));
    }

    @Test
    @DisplayName("ABSORPTION_HAUSSIERE HIGH → +70 (contrarian setup, magnitude < VRAI_ACHAT)")
    void absorptionHaussiereHigh() {
        AgentVote v = agent.evaluate(input(pattern(OrderFlowPattern.ABSORPTION_HAUSSIERE,
            PatternAnalysis.Confidence.HIGH), TickDataQuality.REAL_TICKS));

        assertThat(v.directionalVote()).isEqualTo(+70);
        assertThat(v.confidence()).isCloseTo(0.75, within(1e-6));
    }

    @Test
    @DisplayName("DISTRIBUTION_SILENCIEUSE HIGH → -70 (contrarian SHORT setup)")
    void distributionSilencieuseHigh() {
        AgentVote v = agent.evaluate(input(pattern(OrderFlowPattern.DISTRIBUTION_SILENCIEUSE,
            PatternAnalysis.Confidence.HIGH), TickDataQuality.REAL_TICKS));

        assertThat(v.directionalVote()).isEqualTo(-70);
        assertThat(v.confidence()).isCloseTo(0.75, within(1e-6));
    }

    @Test
    @DisplayName("MEDIUM confidence → smaller magnitude (e.g. VRAI_ACHAT MED → +60)")
    void mediumConfidenceShrinksVote() {
        AgentVote v = agent.evaluate(input(pattern(OrderFlowPattern.VRAI_ACHAT,
            PatternAnalysis.Confidence.MEDIUM), TickDataQuality.REAL_TICKS));

        assertThat(v.directionalVote()).isEqualTo(+60);
        assertThat(v.confidence()).isCloseTo(0.65, within(1e-6));
    }

    @Test
    @DisplayName("LOW confidence on any pattern → abstain (single-scan fallback)")
    void lowConfidenceAbstains() {
        AgentVote v = agent.evaluate(input(pattern(OrderFlowPattern.VRAIE_VENTE,
            PatternAnalysis.Confidence.LOW), TickDataQuality.REAL_TICKS));

        assertThat(v.abstain()).isTrue();
        assertThat(v.layer()).isEqualTo(StrategyLayer.TRIGGER);
    }

    @Test
    @DisplayName("INDETERMINE pattern → abstain")
    void indetermineAbstains() {
        AgentVote v = agent.evaluate(input(PatternAnalysis.indeterminate("no data"),
            TickDataQuality.REAL_TICKS));

        assertThat(v.abstain()).isTrue();
    }

    @Test
    @DisplayName("Null pattern (warming up) → abstain, never crash")
    void nullPatternAbstains() {
        AgentVote v = agent.evaluate(input(null, TickDataQuality.REAL_TICKS));

        assertThat(v.abstain()).isTrue();
    }

    @Test
    @DisplayName("CLV-only data halves the confidence (quality multiplier)")
    void clvDataHalvesConfidence() {
        AgentVote v = agent.evaluate(input(pattern(OrderFlowPattern.VRAI_ACHAT,
            PatternAnalysis.Confidence.HIGH), TickDataQuality.CLV_ESTIMATED));

        assertThat(v.directionalVote()).isEqualTo(+90);
        // 0.85 base × 0.5 CLV multiplier
        assertThat(v.confidence()).isCloseTo(0.425, within(1e-6));
    }

    @Test
    @DisplayName("UNAVAILABLE quality → 0.20× confidence")
    void unavailableQualityDeepDiscount() {
        AgentVote v = agent.evaluate(input(pattern(OrderFlowPattern.VRAIE_VENTE,
            PatternAnalysis.Confidence.HIGH), TickDataQuality.UNAVAILABLE));

        assertThat(v.directionalVote()).isEqualTo(-90);
        // 0.85 × 0.2
        assertThat(v.confidence()).isCloseTo(0.17, within(1e-6));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static PatternAnalysis pattern(OrderFlowPattern type,
                                            PatternAnalysis.Confidence conf) {
        return new PatternAnalysis(type, type.name(), "test reason", conf,
            PatternAnalysis.Action.WAIT);
    }

    private static StrategyInput input(PatternAnalysis pattern, TickDataQuality quality) {
        TriggerContext trig = new TriggerContext(
            DeltaSignature.NEUTRAL,
            new BigDecimal("0.50"),
            BigDecimal.ZERO,
            DomSignal.UNAVAILABLE,
            ReactionPattern.NONE,
            quality,
            pattern
        );
        MarketContext ctx = new MarketContext(
            Instrument.MNQ, "10m",
            MacroBias.NEUTRAL, MarketRegime.UNKNOWN,
            PriceLocation.UNKNOWN, PdZone.UNKNOWN,
            null, null, NOW
        );
        return new StrategyInput(ctx, ZoneContext.empty(), trig, null);
    }
}

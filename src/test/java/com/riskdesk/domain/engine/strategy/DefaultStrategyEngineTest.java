package com.riskdesk.domain.engine.strategy;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.agent.context.RegimeContextAgent;
import com.riskdesk.domain.engine.strategy.agent.context.SmcMacroBiasAgent;
import com.riskdesk.domain.engine.strategy.agent.context.VolumeProfileContextAgent;
import com.riskdesk.domain.engine.strategy.agent.trigger.QuantFlowPatternAgent;
import com.riskdesk.domain.engine.strategy.agent.zone.OrderBlockZoneAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.DecisionType;
import com.riskdesk.domain.engine.strategy.model.DeltaSignature;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.OrderBlockZone;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.ReactionPattern;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import com.riskdesk.domain.engine.strategy.model.TickDataQuality;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import com.riskdesk.domain.engine.strategy.playbook.LsarPlaybook;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookSelector;
import com.riskdesk.domain.engine.strategy.playbook.SbdrPlaybook;
import com.riskdesk.domain.engine.strategy.policy.StrategyScoringPolicy;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the strategy engine wiring (selector + agents + scoring).
 * Exercises the two headline scenarios the user originally complained about:
 *
 * <ol>
 *   <li><b>SMC BULL vs VA SHORT contradiction</b>: no longer contradicts because
 *       the two signals alimentent different layers (SMC = CONTEXT bias, VA =
 *       CONTEXT location). The engine selects LSAR when price is at an extreme
 *       and both sources align with a reversion thesis.</li>
 *   <li><b>"95% rejected"</b>: a clean LSAR setup produces a tradeable decision,
 *       not NO_TRADE, because the scoring is gradual.</li>
 * </ol>
 */
class DefaultStrategyEngineTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-17T10:00:00Z"), ZoneOffset.UTC);
    private final StrategyScoringPolicy policy = new StrategyScoringPolicy();

    private StrategyEngine buildEngine() {
        List<StrategyAgent> agents = List.of(
            new SmcMacroBiasAgent(),
            new VolumeProfileContextAgent(),
            new RegimeContextAgent(),
            new OrderBlockZoneAgent(),
            new QuantFlowPatternAgent()
        );
        PlaybookSelector selector = new PlaybookSelector(List.of(new SbdrPlaybook(), new LsarPlaybook()));
        return new DefaultStrategyEngine(selector, agents, policy, clock);
    }

    @Test
    void lsar_setup_at_value_area_low_produces_tradeable_decision() {
        // Context: Ranging market, price below VAL in DISCOUNT zone.
        // Bias is NEUTRAL (ranging means no directional HTF conviction), which is
        // fine — LSAR is a reversal setup that does NOT require a macro bias.
        MarketContext ctx = new MarketContext(
            Instrument.MGC, "1h",
            MacroBias.NEUTRAL, MarketRegime.RANGING,
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new BigDecimal("2000.00"), new BigDecimal("5.0"),
            clock.instant()
        );
        // A bullish OB just below current price → anchor for the plan
        OrderBlockZone bullOb = new OrderBlockZone(true,
            new BigDecimal("2001.00"), new BigDecimal("1998.00"),
            new BigDecimal("1999.50"), 75.0);
        ZoneContext zones = new ZoneContext(List.of(bullOb), List.of(), List.of());
        // Trigger: heavy sell delta but price holds → bullish absorption pattern.
        // The QuantFlowPatternAgent reads the precomputed PatternAnalysis from
        // the TriggerContext (which TriggerContextBuilder builds via the Quant
        // OrderFlowPatternDetector in production); here we hand-craft the
        // ABSORPTION_HAUSSIERE / HIGH equivalent the detector would emit.
        PatternAnalysis bullAbsorption = new PatternAnalysis(
            OrderFlowPattern.ABSORPTION_HAUSSIERE,
            "Absorption haussière",
            "Δ=-1500 mais prix stable → acheteurs absorbent",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.AVOID  // SHORT-side action; LONG side gets TRADE via actionFor
        );
        TriggerContext trig = new TriggerContext(
            DeltaSignature.ABSORPTION,
            new BigDecimal("0.30"),           // 30% buys → heavy sellers
            new BigDecimal("-1500"),          // strongly negative cumulative delta
            com.riskdesk.domain.engine.strategy.model.DomSignal.UNAVAILABLE,
            ReactionPattern.NONE,
            TickDataQuality.CLV_ESTIMATED,
            bullAbsorption
        );

        StrategyDecision d = buildEngine().evaluate(new StrategyInput(ctx, zones, trig, null));

        assertThat(d.candidatePlaybookId()).contains(LsarPlaybook.ID);
        assertThat(d.decision()).isNotEqualTo(DecisionType.NO_TRADE);
        assertThat(d.finalScore()).isPositive();
        assertThat(d.plan()).isPresent();
    }

    @Test
    void no_playbook_applicable_returns_standby() {
        // Choppy + inside VA → neither LSAR nor SBDR apply
        MarketContext ctx = new MarketContext(
            Instrument.MCL, "10m",
            MacroBias.NEUTRAL, MarketRegime.CHOPPY,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM,
            new BigDecimal("75.00"), new BigDecimal("0.30"),
            clock.instant()
        );
        StrategyDecision d = buildEngine().evaluate(
            new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null));

        assertThat(d.decision()).isEqualTo(DecisionType.NO_TRADE);
        assertThat(d.candidatePlaybookId()).isEmpty();
    }

    @Test
    void agents_abstaining_do_not_produce_fake_neutral_votes() {
        // Everything unknown → every agent abstains, score = 0 exactly.
        MarketContext ctx = new MarketContext(
            Instrument.MNQ, "1h",
            MacroBias.NEUTRAL, MarketRegime.UNKNOWN,
            PriceLocation.UNKNOWN, PdZone.UNKNOWN,
            null, null, clock.instant()
        );
        StrategyDecision d = buildEngine().evaluate(
            new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null));

        assertThat(d.finalScore()).isZero();
        assertThat(d.layerScores().values()).allMatch(v -> v == 0.0);
        // Every vote should be an abstain
        long abstains = d.votes().stream().filter(AgentVote::abstain).count();
        assertThat(abstains).isEqualTo(d.votes().size());
    }

    @Test
    void ranging_market_ignores_trend_playbook() {
        MarketContext ctx = new MarketContext(
            Instrument.E6, "10m",
            MacroBias.BULL, MarketRegime.RANGING,      // ranging but with a bull bias
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT,
            new BigDecimal("1.08000"), new BigDecimal("0.00100"),
            clock.instant()
        );
        // SBDR requires TRENDING. LSAR requires an extreme. Neither → standby.
        StrategyDecision d = buildEngine().evaluate(
            new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null));
        assertThat(d.candidatePlaybookId()).isEmpty();
        assertThat(d.decision()).isEqualTo(DecisionType.NO_TRADE);
    }

    @Test
    void votes_have_expected_layer_distribution() {
        MarketContext ctx = new MarketContext(
            Instrument.MGC, "1h",
            MacroBias.BULL, MarketRegime.TRENDING,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT,
            new BigDecimal("2000.00"), new BigDecimal("5.0"),
            clock.instant()
        );
        OrderBlockZone bullOb = new OrderBlockZone(true,
            new BigDecimal("2001.00"), new BigDecimal("1998.00"),
            new BigDecimal("1999.50"), 80.0);
        ZoneContext zones = new ZoneContext(List.of(bullOb), List.of(), List.of());

        StrategyDecision d = buildEngine().evaluate(new StrategyInput(ctx, zones,
            TriggerContext.unavailable(), null));

        long ctxVotes = d.votes().stream().filter(v -> v.layer() == StrategyLayer.CONTEXT).count();
        long zoneVotes = d.votes().stream().filter(v -> v.layer() == StrategyLayer.ZONE).count();
        long trigVotes = d.votes().stream().filter(v -> v.layer() == StrategyLayer.TRIGGER).count();
        assertThat(ctxVotes).isEqualTo(3);  // smc-bias + vp-context + regime
        assertThat(zoneVotes).isEqualTo(1); // ob-zone
        assertThat(trigVotes).isEqualTo(1); // quant-flow-pattern (replaces delta-flow)
    }
}

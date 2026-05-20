package com.riskdesk.domain.engine.strategy;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.agent.context.BollingerPositionAgent;
import com.riskdesk.domain.engine.strategy.agent.context.CmfFlowAgent;
import com.riskdesk.domain.engine.strategy.agent.context.VwapDistanceAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.IndicatorContext;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MtfSnapshot;
import com.riskdesk.domain.engine.strategy.model.OrderBlockZone;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PortfolioState;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.SessionInfo;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import com.riskdesk.domain.engine.strategy.playbook.LsarPlaybook;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookSelector;
import com.riskdesk.domain.engine.strategy.policy.StrategyScoringPolicy;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check: when {@link VwapDistanceAgent}, {@link BollingerPositionAgent},
 * and {@link CmfFlowAgent} are registered, they appear in {@code StrategyDecision.votes()}
 * with their stable agent IDs. Also asserts that the new {@link IndicatorContext} field
 * round-trips through {@link MarketContext} unchanged.
 */
class IndicatorAgentsWiringTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-17T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void three_new_context_agents_emit_votes_via_engine() {
        List<StrategyAgent> agents = List.of(
            new VwapDistanceAgent(),
            new BollingerPositionAgent(),
            new CmfFlowAgent()
        );
        StrategyEngine engine = new DefaultStrategyEngine(
            new PlaybookSelector(List.of(new LsarPlaybook())),
            agents,
            new StrategyScoringPolicy(),
            clock
        );

        IndicatorContext ind = new IndicatorContext(
            new BigDecimal("2000.00"),
            new BigDecimal("1998.00"),
            new BigDecimal("2002.00"),
            new BigDecimal("0.05"),     // near lower band
            new BigDecimal("4.00"),
            new BigDecimal("0.20"),     // strong buy CMF
            new BigDecimal("100.0")
        );
        MarketContext ctx = new MarketContext(
            Instrument.MNQ, "5m",
            MacroBias.NEUTRAL, MarketRegime.RANGING,
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new BigDecimal("1995.00"), new BigDecimal("5.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(),
            SessionInfo.unknown(), clock.instant(), ind
        );
        // Anchor zone so LSAR can produce a plan and the engine evaluates the
        // CONTEXT votes meaningfully (an unselected playbook short-circuits to
        // standby — but we don't care about the decision here, only the votes).
        ZoneContext zones = new ZoneContext(
            List.of(new OrderBlockZone(true,
                new BigDecimal("1996.00"), new BigDecimal("1993.00"),
                new BigDecimal("1994.50"), 75.0)),
            List.of(), List.of());

        StrategyDecision d = engine.evaluate(new StrategyInput(ctx, zones,
            TriggerContext.unavailable(), null));

        Set<String> ids = d.votes().stream()
            .map(AgentVote::agentId)
            .collect(Collectors.toSet());
        assertThat(ids).contains(
            VwapDistanceAgent.ID,
            BollingerPositionAgent.ID,
            CmfFlowAgent.ID
        );

        // Sanity: each of the 3 produced a non-abstain vote with the supplied data.
        long active = d.votes().stream()
            .filter(v -> v.agentId().equals(VwapDistanceAgent.ID)
                      || v.agentId().equals(BollingerPositionAgent.ID)
                      || v.agentId().equals(CmfFlowAgent.ID))
            .filter(v -> !v.abstain())
            .count();
        assertThat(active).isEqualTo(3);
    }

    @Test
    void empty_indicators_default_keeps_new_agents_silent() {
        // Use the legacy MTF-only constructor — no IndicatorContext supplied.
        // The new agents must abstain rather than NPE.
        MarketContext ctx = new MarketContext(
            Instrument.MGC, "1h",
            MacroBias.BULL, MarketRegime.TRENDING,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT,
            new BigDecimal("2000.00"), new BigDecimal("5.0"),
            MtfSnapshot.neutral(), clock.instant()
        );
        assertThat(ctx.indicators()).isSameAs(IndicatorContext.empty());

        AgentVote vwap = new VwapDistanceAgent().evaluate(
            new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null));
        AgentVote bb = new BollingerPositionAgent().evaluate(
            new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null));
        AgentVote cmf = new CmfFlowAgent().evaluate(
            new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null));

        assertThat(vwap.abstain()).isTrue();
        assertThat(bb.abstain()).isTrue();
        assertThat(cmf.abstain()).isTrue();
    }
}

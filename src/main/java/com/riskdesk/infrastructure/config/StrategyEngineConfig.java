package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.engine.strategy.DefaultStrategyEngine;
import com.riskdesk.domain.engine.strategy.StrategyEngine;
import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.agent.context.BollingerPositionAgent;
import com.riskdesk.domain.engine.strategy.agent.context.CmfFlowAgent;
import com.riskdesk.domain.engine.strategy.agent.context.HtfAlignmentAgent;
import com.riskdesk.domain.engine.strategy.agent.context.RegimeContextAgent;
import com.riskdesk.domain.engine.strategy.agent.context.RiskGateAgent;
import com.riskdesk.domain.engine.strategy.agent.context.SessionTimingAgent;
import com.riskdesk.domain.engine.strategy.agent.context.SmcMacroBiasAgent;
import com.riskdesk.domain.engine.strategy.agent.context.VolumeProfileContextAgent;
import com.riskdesk.domain.engine.strategy.agent.context.VwapDistanceAgent;
import com.riskdesk.domain.engine.strategy.agent.trigger.DeltaFlowTriggerAgent;
import com.riskdesk.domain.engine.strategy.agent.trigger.ReactionTriggerAgent;
import com.riskdesk.domain.engine.strategy.agent.zone.LiquidityZoneAgent;
import com.riskdesk.domain.engine.strategy.agent.zone.OrderBlockZoneAgent;
import com.riskdesk.domain.engine.strategy.playbook.ContextualPullbackPlaybook;
import com.riskdesk.domain.engine.strategy.playbook.LondonSweepPlaybook;
import com.riskdesk.domain.engine.strategy.playbook.LsarPlaybook;
import com.riskdesk.domain.engine.strategy.playbook.NyOpenReversalPlaybook;
import com.riskdesk.domain.engine.strategy.playbook.Playbook;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookSelector;
import com.riskdesk.domain.engine.strategy.playbook.SbdrPlaybook;
import com.riskdesk.domain.engine.strategy.playbook.SilverBulletPlaybook;
import com.riskdesk.domain.engine.strategy.policy.StrategyScoringPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.util.List;

/**
 * Wires the new strategy engine as pure-domain beans. Adding an agent or a
 * playbook is a single {@code @Bean} method here — no application-layer change
 * required, the engine picks up the list automatically.
 *
 * <p>This configuration is independent from {@link TradingAgentConfig}. The legacy
 * playbook orchestrator continues to work during coexistence; the new engine
 * lives in parallel.
 */
@Configuration
public class StrategyEngineConfig {

    // ── Agents ──────────────────────────────────────────────────────────────

    @Bean public SmcMacroBiasAgent smcMacroBiasAgent() { return new SmcMacroBiasAgent(); }
    @Bean public VolumeProfileContextAgent volumeProfileContextAgent() { return new VolumeProfileContextAgent(); }
    @Bean public RegimeContextAgent regimeContextAgent() { return new RegimeContextAgent(); }
    @Bean public HtfAlignmentAgent htfAlignmentAgent() { return new HtfAlignmentAgent(); }
    @Bean public RiskGateAgent strategyRiskGateAgent() { return new RiskGateAgent(); }
    // Deliberately named `strategySessionTimingAgent` — the legacy
    // TradingAgentConfig already contributes a bean named `sessionTimingAgent`
    // (the pre-hexagonal playbook agent of the same short name). Spring rejects
    // duplicate bean names, so we keep the two ecosystems namespace-prefixed.
    @Bean public SessionTimingAgent strategySessionTimingAgent() { return new SessionTimingAgent(); }
    @Bean public VwapDistanceAgent vwapDistanceAgent() { return new VwapDistanceAgent(); }
    @Bean public BollingerPositionAgent bollingerPositionAgent() { return new BollingerPositionAgent(); }
    @Bean public CmfFlowAgent cmfFlowAgent() { return new CmfFlowAgent(); }
    @Bean public OrderBlockZoneAgent orderBlockZoneAgent() { return new OrderBlockZoneAgent(); }
    @Bean public LiquidityZoneAgent liquidityZoneAgent() { return new LiquidityZoneAgent(); }
    @Bean public DeltaFlowTriggerAgent deltaFlowTriggerAgent() { return new DeltaFlowTriggerAgent(); }
    @Bean public ReactionTriggerAgent reactionTriggerAgent() { return new ReactionTriggerAgent(); }

    // ── Playbooks ───────────────────────────────────────────────────────────

    @Bean public LsarPlaybook lsarPlaybook() { return new LsarPlaybook(); }
    @Bean public SbdrPlaybook sbdrPlaybook() { return new SbdrPlaybook(); }
    @Bean public SilverBulletPlaybook silverBulletPlaybook() { return new SilverBulletPlaybook(); }
    @Bean public NyOpenReversalPlaybook nyOpenReversalPlaybook() { return new NyOpenReversalPlaybook(); }
    @Bean public LondonSweepPlaybook londonSweepPlaybook() { return new LondonSweepPlaybook(); }
    @Bean public ContextualPullbackPlaybook contextualPullbackPlaybook() { return new ContextualPullbackPlaybook(); }

    /**
     * Playbook priority order — <b>most specific first, fallback last</b>.
     *
     * <p>The selector returns the first applicable playbook, so placing session-
     * scoped setups ahead of session-agnostic ones guarantees that during a NY
     * kill zone with an FVG+bias we pick {@code SB}, not the generic
     * {@code SBDR} that would also match. Outside a kill zone, session-scoped
     * playbooks abstain via {@link Playbook#isApplicable(com.riskdesk.domain.engine.strategy.model.MarketContext)}
     * and the fallback tier applies.
     *
     * <p>Explicit ordering here (rather than relying on {@code List<Playbook>}
     * autowire order, which is fragile across Spring versions) is the only
     * contract guaranteeing that priority rule holds.
     */
    @Bean
    public PlaybookSelector playbookSelector(SilverBulletPlaybook sb,
                                              NyOpenReversalPlaybook nor,
                                              LondonSweepPlaybook ls,
                                              SbdrPlaybook sbdr,
                                              LsarPlaybook lsar,
                                              ContextualPullbackPlaybook ctx) {
        // Order: most specific (session + structural) → least specific (fallback last).
        // CTX is the catch-all that fires when none of the kill-zone or VA-extreme
        // playbooks match; it is intentionally last so it never preempts a more
        // disciplined setup.
        List<Playbook> ordered = List.of(sb, nor, ls, sbdr, lsar, ctx);
        return new PlaybookSelector(ordered);
    }

    // ── Engine core ─────────────────────────────────────────────────────────

    /**
     * Shared UTC clock — injected wherever the engine needs {@code Instant.now()}.
     * Marked {@link Primary} so tests can override with a fixed-clock bean without
     * matcher gymnastics.
     */
    @Bean
    @Primary
    public Clock strategyClock() {
        return Clock.systemUTC();
    }

    @Bean
    public StrategyScoringPolicy strategyScoringPolicy() {
        return new StrategyScoringPolicy();
    }

    @Bean
    public StrategyEngine strategyEngine(PlaybookSelector selector,
                                          List<StrategyAgent> agents,
                                          StrategyScoringPolicy policy,
                                          Clock clock) {
        return new DefaultStrategyEngine(selector, agents, policy, clock);
    }
}

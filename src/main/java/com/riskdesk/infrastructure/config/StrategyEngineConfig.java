package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.engine.strategy.DefaultStrategyEngine;
import com.riskdesk.domain.engine.strategy.StrategyEngine;
import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.agent.context.HtfAlignmentAgent;
import com.riskdesk.domain.engine.strategy.agent.context.RegimeContextAgent;
import com.riskdesk.domain.engine.strategy.agent.context.RiskGateAgent;
import com.riskdesk.domain.engine.strategy.agent.context.SessionTimingAgent;
import com.riskdesk.domain.engine.strategy.agent.context.SmcMacroBiasAgent;
import com.riskdesk.domain.engine.strategy.agent.context.VolumeProfileContextAgent;
import com.riskdesk.domain.engine.strategy.agent.trigger.DeltaFlowTriggerAgent;
import com.riskdesk.domain.engine.strategy.agent.trigger.ReactionTriggerAgent;
import com.riskdesk.domain.engine.strategy.agent.zone.LiquidityZoneAgent;
import com.riskdesk.domain.engine.strategy.agent.zone.OrderBlockZoneAgent;
import com.riskdesk.domain.engine.strategy.playbook.LsarPlaybook;
import com.riskdesk.domain.engine.strategy.playbook.Playbook;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookSelector;
import com.riskdesk.domain.engine.strategy.playbook.SbdrPlaybook;
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
    @Bean public OrderBlockZoneAgent orderBlockZoneAgent() { return new OrderBlockZoneAgent(); }
    @Bean public LiquidityZoneAgent liquidityZoneAgent() { return new LiquidityZoneAgent(); }
    @Bean public DeltaFlowTriggerAgent deltaFlowTriggerAgent() { return new DeltaFlowTriggerAgent(); }
    @Bean public ReactionTriggerAgent reactionTriggerAgent() { return new ReactionTriggerAgent(); }

    // ── Playbooks ───────────────────────────────────────────────────────────

    @Bean public LsarPlaybook lsarPlaybook() { return new LsarPlaybook(); }
    @Bean public SbdrPlaybook sbdrPlaybook() { return new SbdrPlaybook(); }

    @Bean
    public PlaybookSelector playbookSelector(List<Playbook> playbooks) {
        return new PlaybookSelector(playbooks);
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

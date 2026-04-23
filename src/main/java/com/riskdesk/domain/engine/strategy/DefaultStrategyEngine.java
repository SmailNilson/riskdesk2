package com.riskdesk.domain.engine.strategy;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.playbook.Playbook;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookSelector;
import com.riskdesk.domain.engine.strategy.policy.StrategyScoringPolicy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure-domain {@link StrategyEngine} implementation. Wire-up:
 *
 * <ol>
 *   <li>Select a candidate playbook via the {@link PlaybookSelector}. If none → STANDBY.</li>
 *   <li>Run every agent on the input (now enriched with the candidate's id).</li>
 *   <li>Ask the selected playbook to build a {@link MechanicalPlan}.</li>
 *   <li>Hand votes + plan + candidate to the {@link StrategyScoringPolicy}.</li>
 * </ol>
 *
 * <p>Agent evaluation is sequential here — the concurrency layer lives in the
 * application service (where Spring's executor is available). Sequential execution in
 * the pure-domain engine keeps tests deterministic and the engine framework-free.
 */
public final class DefaultStrategyEngine implements StrategyEngine {

    private final PlaybookSelector selector;
    private final List<StrategyAgent> agents;
    private final StrategyScoringPolicy policy;
    private final Clock clock;

    public DefaultStrategyEngine(PlaybookSelector selector,
                                  List<StrategyAgent> agents,
                                  StrategyScoringPolicy policy,
                                  Clock clock) {
        this.selector = Objects.requireNonNull(selector, "selector");
        this.agents = List.copyOf(Objects.requireNonNull(agents, "agents"));
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StrategyDecision evaluate(StrategyInput rawInput) {
        Optional<Playbook> selected = selector.select(rawInput.context());
        StrategyInput input = selected
            .map(p -> rawInput.withCandidatePlaybook(p.id()))
            .orElse(rawInput);

        List<AgentVote> votes = runAgents(input);

        Optional<MechanicalPlan> plan = selected
            .flatMap(p -> p.buildPlan(input));

        return policy.decide(votes, selected.orElse(null), plan, clock.instant());
    }

    private List<AgentVote> runAgents(StrategyInput input) {
        List<AgentVote> out = new ArrayList<>(agents.size());
        for (StrategyAgent a : agents) {
            AgentVote v;
            try {
                v = a.evaluate(input);
            } catch (RuntimeException e) {
                // One agent's error never sinks the whole decision — record and skip.
                v = AgentVote.abstain(a.id(), a.layer(),
                    "agent error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            out.add(v);
        }
        return out;
    }
}

package com.riskdesk.domain.engine.strategy.policy;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.DecisionType;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import com.riskdesk.domain.engine.strategy.playbook.Playbook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure-function scoring policy — takes a list of {@link AgentVote}s and the selected
 * {@link Playbook} and returns a {@link StrategyDecision}.
 *
 * <h2>Aggregation formula</h2>
 *
 * <p>Step 1 — per layer:
 * <pre>
 *   LayerScore(layer) =
 *       Σ (vote.directionalVote × vote.confidence)  over non-abstain votes in that layer
 *       ───────────────────────────────────────────
 *                      Σ (vote.confidence)
 * </pre>
 *
 * <p>Step 2 — weighted sum across layers:
 * <pre>
 *   finalScore = 0.50 × LayerScore(CONTEXT) + 0.30 × LayerScore(ZONE) + 0.20 × LayerScore(TRIGGER)
 * </pre>
 * These weights match the Mentor "Structure 50% &gt; Order Flow 30% &gt; Momentum 20%"
 * doctrine in CLAUDE.md.
 *
 * <h2>Gates</h2>
 * <ol>
 *   <li><b>Hard veto</b> — any vote with a {@code vetoReason} forces NO_TRADE.</li>
 *   <li><b>Plan present</b> — a non-null candidate without a {@link MechanicalPlan}
 *       degrades to STANDBY. Tradeable verdicts must carry entry/SL/TP; this
 *       prevents the engine from emitting HALF_SIZE/FULL_SIZE with empty plan
 *       fields when a playbook is applicable but cannot anchor a plan
 *       (e.g. no matching-direction OB).</li>
 *   <li><b>Inter-layer coherence</b> — if CONTEXT and TRIGGER disagree in sign AND
 *       {@code |finalScore| < 70}, the decision is MONITORING. This is the lever
 *       that catches "SMC says LONG but flow says SHORT" without silently averaging.</li>
 *   <li><b>Playbook minimum</b> — {@code |finalScore|} must reach
 *       {@link Playbook#minimumScoreForExecution()} to exit MONITORING/PAPER_TRADE.</li>
 * </ol>
 *
 * <p>The policy is stateless and thread-safe.
 */
public final class StrategyScoringPolicy {

    public static final double WEIGHT_CONTEXT = 0.50;
    public static final double WEIGHT_ZONE = 0.30;
    public static final double WEIGHT_TRIGGER = 0.20;

    /**
     * Absolute floor — below this magnitude there is nothing worth reporting, not
     * even a paper trade. Independent of any playbook's {@code minimumScoreForExecution}.
     */
    public static final double PAPER_TRADE_FLOOR = 30.0;
    /**
     * Spread added to the playbook's minimum to graduate from HALF to FULL size.
     * i.e. {@code FULL_SIZE} starts at {@code playbook.min + this}.
     */
    public static final double FULL_SIZE_SPREAD = 20.0;

    public StrategyDecision decide(Collection<AgentVote> votes, Playbook candidate,
                                    Optional<MechanicalPlan> plan, Instant evaluatedAt) {
        Map<StrategyLayer, Double> layerScores = scoreByLayer(votes);
        double finalScore = layerWeighted(layerScores);

        List<String> vetoes = collectVetoReasons(votes);

        if (!vetoes.isEmpty()) {
            return buildDecision(candidate, votes, layerScores, finalScore, vetoes,
                DecisionType.NO_TRADE, plan, evaluatedAt);
        }
        if (candidate == null) {
            return StrategyDecision.standby(evaluatedAt, List.copyOf(votes), layerScores);
        }
        // Tradeable verdicts must carry a mechanical plan. When a playbook accepts
        // the context but cannot build a plan (e.g. the {@code CTX} fallback fires
        // on regime+bias alone but finds no matching-direction OB), fall back to
        // STANDBY rather than emit HALF_SIZE/FULL_SIZE with empty entry/SL/TP.
        // This protects every playbook whose buildPlan() is allowed to return
        // empty — SB, SBDR, CTX — not just CTX.
        if (plan.isEmpty()) {
            return StrategyDecision.standby(evaluatedAt, List.copyOf(votes), layerScores);
        }

        // Inter-layer coherence gate — only relevant when both layers have
        // non-abstain votes (non-zero denominator).
        double contextScore = layerScores.getOrDefault(StrategyLayer.CONTEXT, 0.0);
        double triggerScore = layerScores.getOrDefault(StrategyLayer.TRIGGER, 0.0);
        boolean triggerActive = hasAnyActiveVote(votes, StrategyLayer.TRIGGER);
        boolean contextActive = hasAnyActiveVote(votes, StrategyLayer.CONTEXT);
        if (triggerActive && contextActive
            && Math.signum(contextScore) != 0.0
            && Math.signum(triggerScore) != 0.0
            && Math.signum(contextScore) != Math.signum(triggerScore)
            && Math.abs(finalScore) < 70.0) {
            return buildDecision(candidate, votes, layerScores, finalScore, List.of(),
                DecisionType.MONITORING, plan, evaluatedAt);
        }

        double magnitude = Math.abs(finalScore);
        double playbookMin = candidate.minimumScoreForExecution();
        double fullSizeMin = playbookMin + FULL_SIZE_SPREAD;

        // Layered thresholds:
        //   < PAPER_TRADE_FLOOR         → nothing worth logging
        //   < playbook.min              → setup seen but below live-trade threshold → PAPER
        //   < playbook.min + SPREAD     → meets min; trade half risk
        //   >= playbook.min + SPREAD    → well above min; trade full risk
        DecisionType decision;
        if (magnitude < PAPER_TRADE_FLOOR) {
            decision = DecisionType.NO_TRADE;
        } else if (magnitude < playbookMin) {
            decision = DecisionType.PAPER_TRADE;
        } else if (magnitude < fullSizeMin) {
            decision = DecisionType.HALF_SIZE;
        } else {
            decision = DecisionType.FULL_SIZE;
        }

        return buildDecision(candidate, votes, layerScores, finalScore, List.of(),
            decision, plan, evaluatedAt);
    }

    // ── Aggregation helpers ────────────────────────────────────────────────

    private static Map<StrategyLayer, Double> scoreByLayer(Collection<AgentVote> votes) {
        Map<StrategyLayer, Double> numer = new EnumMap<>(StrategyLayer.class);
        Map<StrategyLayer, Double> denom = new EnumMap<>(StrategyLayer.class);
        for (AgentVote v : votes) {
            if (v.abstain()) continue;
            if (v.hasVeto()) continue;
            numer.merge(v.layer(), v.directionalVote() * v.confidence(), Double::sum);
            denom.merge(v.layer(), v.confidence(), Double::sum);
        }
        Map<StrategyLayer, Double> result = new EnumMap<>(StrategyLayer.class);
        for (StrategyLayer layer : StrategyLayer.values()) {
            double n = numer.getOrDefault(layer, 0.0);
            double d = denom.getOrDefault(layer, 0.0);
            result.put(layer, d == 0.0 ? 0.0 : n / d);
        }
        return result;
    }

    private static double layerWeighted(Map<StrategyLayer, Double> layerScores) {
        double raw = WEIGHT_CONTEXT * layerScores.getOrDefault(StrategyLayer.CONTEXT, 0.0)
                   + WEIGHT_ZONE    * layerScores.getOrDefault(StrategyLayer.ZONE, 0.0)
                   + WEIGHT_TRIGGER * layerScores.getOrDefault(StrategyLayer.TRIGGER, 0.0);
        if (raw > 100.0)  return 100.0;
        if (raw < -100.0) return -100.0;
        return raw;
    }

    private static boolean hasAnyActiveVote(Collection<AgentVote> votes, StrategyLayer layer) {
        for (AgentVote v : votes) {
            if (v.layer() == layer && !v.abstain() && !v.hasVeto()) return true;
        }
        return false;
    }

    private static List<String> collectVetoReasons(Collection<AgentVote> votes) {
        List<String> out = new ArrayList<>();
        for (AgentVote v : votes) {
            v.vetoReason().ifPresent(r -> out.add(v.agentId() + ": " + r));
        }
        return out;
    }

    private static StrategyDecision buildDecision(Playbook candidate,
                                                   Collection<AgentVote> votes,
                                                   Map<StrategyLayer, Double> layerScores,
                                                   double finalScore,
                                                   List<String> vetoes,
                                                   DecisionType type,
                                                   Optional<MechanicalPlan> plan,
                                                   Instant at) {
        Optional<Direction> direction;
        Optional<MechanicalPlan> outPlan;
        if (type.isTradeable() && plan.isPresent()) {
            direction = Optional.of(plan.get().direction());
            outPlan = plan;
        } else if (type == DecisionType.PAPER_TRADE && plan.isPresent()) {
            direction = Optional.of(plan.get().direction());
            outPlan = plan;
        } else if (type == DecisionType.MONITORING && plan.isPresent()) {
            direction = Optional.of(plan.get().direction());
            outPlan = Optional.empty(); // don't expose a plan on MONITORING
        } else {
            direction = Optional.empty();
            outPlan = Optional.empty();
        }
        return new StrategyDecision(
            Optional.ofNullable(candidate).map(Playbook::id),
            List.copyOf(votes),
            layerScores,
            finalScore,
            type,
            direction,
            outPlan,
            vetoes,
            at
        );
    }
}

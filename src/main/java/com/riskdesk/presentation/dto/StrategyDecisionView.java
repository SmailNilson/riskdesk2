package com.riskdesk.presentation.dto;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.DecisionType;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Presentation DTO for a {@link StrategyDecision}. Flattens {@code Optional<T>} to
 * nullable fields so the JSON contract matches what the TypeScript client expects
 * without needing Jackson's {@code Jdk8Module} to be configured.
 */
public record StrategyDecisionView(
    String candidatePlaybookId,
    List<AgentVoteView> votes,
    Map<StrategyLayer, Double> layerScores,
    double finalScore,
    DecisionType decision,
    Direction direction,
    MechanicalPlanView plan,
    List<String> vetoReasons,
    Instant evaluatedAt
) {
    public static StrategyDecisionView from(StrategyDecision d) {
        return new StrategyDecisionView(
            d.candidatePlaybookId().orElse(null),
            d.votes().stream().map(AgentVoteView::from).toList(),
            d.layerScores(),
            d.finalScore(),
            d.decision(),
            d.direction().orElse(null),
            d.plan().map(MechanicalPlanView::from).orElse(null),
            d.vetoReasons(),
            d.evaluatedAt()
        );
    }

    public record AgentVoteView(
        String agentId,
        StrategyLayer layer,
        int directionalVote,
        double confidence,
        boolean abstain,
        List<String> evidence,
        String vetoReason
    ) {
        public static AgentVoteView from(AgentVote v) {
            return new AgentVoteView(
                v.agentId(), v.layer(), v.directionalVote(), v.confidence(),
                v.abstain(), v.evidence(), v.vetoReason().orElse(null)
            );
        }
    }

    public record MechanicalPlanView(
        Direction direction,
        BigDecimal entry,
        BigDecimal stopLoss,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        double rrRatio
    ) {
        public static MechanicalPlanView from(MechanicalPlan p) {
            return new MechanicalPlanView(
                p.direction(), p.entry(), p.stopLoss(),
                p.takeProfit1(), p.takeProfit2(), p.rrRatio()
            );
        }
    }
}

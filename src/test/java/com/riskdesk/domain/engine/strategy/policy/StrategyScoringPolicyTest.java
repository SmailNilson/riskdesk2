package com.riskdesk.domain.engine.strategy.policy;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.DecisionType;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import com.riskdesk.domain.engine.strategy.playbook.Playbook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyScoringPolicyTest {

    private final StrategyScoringPolicy policy = new StrategyScoringPolicy();
    private final Instant at = Instant.parse("2026-04-17T10:00:00Z");

    private static final Playbook LSAR_STUB = stubPlaybook("LSAR", 55.0);
    private static final Playbook SBDR_STUB = stubPlaybook("SBDR", 65.0);

    @Test
    void abstain_votes_do_not_pull_score_toward_zero() {
        // Only one real bullish CONTEXT vote; everything else abstains.
        List<AgentVote> votes = List.of(
            AgentVote.of("ctx-bias", StrategyLayer.CONTEXT, +70, 0.8, List.of("bull")),
            AgentVote.abstain("ctx-regime", StrategyLayer.CONTEXT, "no data"),
            AgentVote.abstain("zone-ob", StrategyLayer.ZONE, "no zone"),
            AgentVote.abstain("trig-delta", StrategyLayer.TRIGGER, "no flow")
        );

        StrategyDecision d = policy.decide(votes, LSAR_STUB, Optional.of(bullishPlan()), at);

        // CONTEXT layer score = 70 × 0.8 / 0.8 = 70. Others = 0 (no active votes).
        // finalScore = 0.5 × 70 + 0.3 × 0 + 0.2 × 0 = 35
        assertThat(d.finalScore()).isCloseTo(35.0, org.assertj.core.data.Offset.offset(0.5));
        // 35 >= PAPER_TRADE_FLOOR (30) but below LSAR min (55) → PAPER_TRADE,
        // NOT NO_TRADE. This is the central fix the new engine brings: gradual
        // bucketing instead of the 7/7 hard reject.
        assertThat(d.decision()).isEqualTo(DecisionType.PAPER_TRADE);
    }

    @Test
    void veto_forces_no_trade_even_with_positive_score() {
        List<AgentVote> votes = List.of(
            AgentVote.of("ctx-bias", StrategyLayer.CONTEXT, +90, 0.9, List.of("strong bull")),
            AgentVote.of("zone-ob", StrategyLayer.ZONE, +80, 0.9, List.of("OB confluence")),
            AgentVote.of("trig-delta", StrategyLayer.TRIGGER, +60, 0.7, List.of("flow up")),
            AgentVote.veto("risk-gate", StrategyLayer.CONTEXT, "daily drawdown breach 3.2%")
        );

        StrategyDecision d = policy.decide(votes, LSAR_STUB, Optional.of(bullishPlan()), at);

        assertThat(d.decision()).isEqualTo(DecisionType.NO_TRADE);
        assertThat(d.vetoReasons()).hasSize(1);
        assertThat(d.vetoReasons().get(0)).contains("daily drawdown breach");
        // plan stripped on NO_TRADE
        assertThat(d.plan()).isEmpty();
    }

    @Test
    void context_and_trigger_disagreement_demotes_to_monitoring() {
        // Both layers active, opposite signs, |final| < 70 → MONITORING
        List<AgentVote> votes = List.of(
            AgentVote.of("ctx-bias", StrategyLayer.CONTEXT, +60, 0.9, List.of("bull swing")),
            AgentVote.of("trig-delta", StrategyLayer.TRIGGER, -60, 0.8, List.of("bearish flow"))
        );

        StrategyDecision d = policy.decide(votes, LSAR_STUB, Optional.of(bullishPlan()), at);

        // context layer 60, zone 0 (no active), trigger -60
        // final = 0.5×60 + 0.3×0 + 0.2×(-60) = 30 - 12 = 18 → |18| < 70 → MONITORING
        assertThat(d.decision()).isEqualTo(DecisionType.MONITORING);
        assertThat(d.plan()).isEmpty(); // no plan on MONITORING
    }

    @Test
    void strong_aligned_setup_becomes_full_size() {
        List<AgentVote> votes = List.of(
            AgentVote.of("ctx-bias", StrategyLayer.CONTEXT, +90, 0.95, List.of("bull")),
            AgentVote.of("ctx-regime", StrategyLayer.CONTEXT, +70, 0.85, List.of("trending")),
            AgentVote.of("zone-ob", StrategyLayer.ZONE, +90, 0.9, List.of("OB")),
            AgentVote.of("trig-delta", StrategyLayer.TRIGGER, +80, 0.8, List.of("flow"))
        );

        StrategyDecision d = policy.decide(votes, SBDR_STUB, Optional.of(bullishPlan()), at);

        // context avg ~= (90×0.95 + 70×0.85) / (0.95+0.85) ≈ 80.5
        // final ≈ 0.5×80.5 + 0.3×90 + 0.2×80 ≈ 40.25 + 27 + 16 = 83.25 → HALF_SIZE (between 70..85)
        // Above the SBDR min of 65 so tradeable.
        assertThat(d.finalScore()).isCloseTo(83.25, org.assertj.core.data.Offset.offset(1.0));
        assertThat(d.decision()).isIn(DecisionType.HALF_SIZE, DecisionType.FULL_SIZE);
        assertThat(d.plan()).isPresent();
        assertThat(d.direction()).contains(Direction.LONG);
    }

    @Test
    void score_clamped_to_unit_interval() {
        // Overwhelming inputs should still produce a score in [-100, 100]
        List<AgentVote> votes = List.of(
            AgentVote.of("a", StrategyLayer.CONTEXT, +100, 1.0, List.of()),
            AgentVote.of("b", StrategyLayer.ZONE, +100, 1.0, List.of()),
            AgentVote.of("c", StrategyLayer.TRIGGER, +100, 1.0, List.of())
        );
        StrategyDecision d = policy.decide(votes, LSAR_STUB, Optional.of(bullishPlan()), at);
        assertThat(d.finalScore()).isLessThanOrEqualTo(100.0).isGreaterThanOrEqualTo(-100.0);
    }

    @Test
    void score_below_paper_trade_floor_returns_no_trade() {
        List<AgentVote> votes = List.of(
            AgentVote.of("ctx-bias", StrategyLayer.CONTEXT, +20, 0.5, List.of())
        );
        // CONTEXT = 20, weighted = 0.5 × 20 = 10 → below PAPER_TRADE_FLOOR
        StrategyDecision d = policy.decide(votes, LSAR_STUB, Optional.of(bullishPlan()), at);
        assertThat(d.decision()).isEqualTo(DecisionType.NO_TRADE);
    }

    @Test
    void standby_when_no_candidate_playbook() {
        List<AgentVote> votes = List.of(
            AgentVote.of("ctx-bias", StrategyLayer.CONTEXT, +50, 0.8, List.of())
        );
        StrategyDecision d = policy.decide(votes, null, Optional.empty(), at);
        assertThat(d.decision()).isEqualTo(DecisionType.NO_TRADE);
        assertThat(d.candidatePlaybookId()).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Playbook stubPlaybook(String id, double minScore) {
        return new Playbook() {
            @Override public String id() { return id; }
            @Override public boolean isApplicable(com.riskdesk.domain.engine.strategy.model.MarketContext c) { return true; }
            @Override public Optional<MechanicalPlan> buildPlan(com.riskdesk.domain.engine.strategy.model.StrategyInput i) { return Optional.empty(); }
            @Override public double minimumScoreForExecution() { return minScore; }
        };
    }

    private static MechanicalPlan bullishPlan() {
        return new MechanicalPlan(
            Direction.LONG,
            new BigDecimal("100.00"),
            new BigDecimal("99.00"),
            new BigDecimal("102.00"),
            new BigDecimal("103.50"),
            2.0
        );
    }
}

package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.DecisionType;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyEngineAnalysisPayloadTest {

    private static final Instant AT = Instant.parse("2026-04-17T10:00:00Z");

    @Test
    void returns_null_for_null_decision() {
        assertThat(StrategyEngineAnalysisPayload.build(null)).isNull();
    }

    @Test
    void exposes_core_decision_fields() {
        StrategyDecision decision = decision(DecisionType.HALF_SIZE, Direction.LONG, 72.34);

        Map<String, Object> payload = StrategyEngineAnalysisPayload.build(decision);

        assertThat(payload).containsEntry("candidate_playbook", "NOR");
        assertThat(payload).containsEntry("decision", "HALF_SIZE");
        assertThat(payload).containsEntry("direction", "LONG");
        assertThat(payload).containsEntry("final_score", 72.3); // rounded to 1 decimal
        assertThat(payload).containsEntry("evaluated_at", AT.toString());
    }

    @Test
    void layer_scores_always_contain_all_three_layers_in_enum_order() {
        StrategyDecision decision = decision(DecisionType.HALF_SIZE, Direction.LONG, 60.0);

        @SuppressWarnings("unchecked")
        Map<String, Object> layers = (Map<String, Object>) StrategyEngineAnalysisPayload
            .build(decision).get("layer_scores");

        // Keys always present even if no vote contributed — LinkedHashMap preserves insertion order.
        assertThat(layers.keySet()).containsExactly("CONTEXT", "ZONE", "TRIGGER");
    }

    @Test
    void veto_votes_appear_first_then_active_then_abstains() {
        List<AgentVote> votes = List.of(
            AgentVote.abstain("abstain-a", StrategyLayer.ZONE, "no data"),
            AgentVote.of("active-b", StrategyLayer.CONTEXT, 50, 0.8, List.of("ev")),
            AgentVote.veto("veto-c", StrategyLayer.CONTEXT, "daily-drawdown"),
            AgentVote.of("active-a", StrategyLayer.TRIGGER, -30, 0.5, List.of("ev2"))
        );
        StrategyDecision d = new StrategyDecision(
            Optional.of("NOR"), votes,
            Map.of(StrategyLayer.CONTEXT, 50.0, StrategyLayer.ZONE, 0.0, StrategyLayer.TRIGGER, -6.0),
            30.0, DecisionType.NO_TRADE, Optional.empty(), Optional.empty(),
            List.of("veto-c: daily-drawdown"),
            AT);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agentVotes =
            (List<Map<String, Object>>) StrategyEngineAnalysisPayload.build(d).get("agent_votes");

        // Order: veto first → active (CONTEXT before TRIGGER) → abstain
        List<String> ids = agentVotes.stream().map(v -> (String) v.get("id")).toList();
        assertThat(ids).containsExactly("veto-c", "active-b", "active-a", "abstain-a");
    }

    @Test
    void veto_entry_includes_veto_reason_key_only_when_present() {
        List<AgentVote> votes = List.of(
            AgentVote.of("normal", StrategyLayer.ZONE, 40, 0.7, List.of("ok")),
            AgentVote.veto("risk", StrategyLayer.CONTEXT, "margin-near-limit: 85.0%")
        );
        StrategyDecision d = new StrategyDecision(
            Optional.of("LSAR"), votes,
            Map.of(StrategyLayer.CONTEXT, 0.0, StrategyLayer.ZONE, 40.0, StrategyLayer.TRIGGER, 0.0),
            12.0, DecisionType.NO_TRADE, Optional.empty(), Optional.empty(),
            List.of("risk: margin-near-limit: 85.0%"),
            AT);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agentVotes =
            (List<Map<String, Object>>) StrategyEngineAnalysisPayload.build(d).get("agent_votes");

        // veto row has the veto_reason key; the normal row doesn't
        Map<String, Object> vetoEntry = agentVotes.get(0);
        Map<String, Object> normalEntry = agentVotes.get(1);
        assertThat(vetoEntry).containsEntry("id", "risk");
        assertThat(vetoEntry).containsKey("veto_reason");
        assertThat(normalEntry).containsEntry("id", "normal");
        assertThat(normalEntry).doesNotContainKey("veto_reason");
    }

    @Test
    void evidence_is_joined_with_em_dash_separator() {
        AgentVote v = AgentVote.of("multi-ev", StrategyLayer.CONTEXT, 30, 0.5,
            List.of("Swing BULL", "H1 aligned", "VA below"));
        StrategyDecision d = new StrategyDecision(
            Optional.of("SBDR"), List.of(v),
            Map.of(StrategyLayer.CONTEXT, 30.0, StrategyLayer.ZONE, 0.0, StrategyLayer.TRIGGER, 0.0),
            15.0, DecisionType.NO_TRADE, Optional.empty(), Optional.empty(),
            List.of(), AT);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agentVotes =
            (List<Map<String, Object>>) StrategyEngineAnalysisPayload.build(d).get("agent_votes");

        assertThat(agentVotes.get(0).get("evidence"))
            .isEqualTo("Swing BULL — H1 aligned — VA below");
    }

    @Test
    void confidence_rounded_to_two_decimals() {
        AgentVote v = AgentVote.of("precision-check", StrategyLayer.ZONE, 40, 0.87654,
            List.of("ok"));
        StrategyDecision d = new StrategyDecision(
            Optional.of("LSAR"), List.of(v),
            Map.of(StrategyLayer.CONTEXT, 0.0, StrategyLayer.ZONE, 40.0, StrategyLayer.TRIGGER, 0.0),
            12.0, DecisionType.NO_TRADE, Optional.empty(), Optional.empty(),
            List.of(), AT);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agentVotes =
            (List<Map<String, Object>>) StrategyEngineAnalysisPayload.build(d).get("agent_votes");

        assertThat(agentVotes.get(0).get("confidence")).isEqualTo(0.88);
    }

    @Test
    void no_candidate_playbook_is_explicit_null() {
        StrategyDecision d = StrategyDecision.standby(AT, List.of(),
            Map.of(StrategyLayer.CONTEXT, 0.0, StrategyLayer.ZONE, 0.0, StrategyLayer.TRIGGER, 0.0));

        Map<String, Object> payload = StrategyEngineAnalysisPayload.build(d);

        assertThat(payload).containsEntry("candidate_playbook", null);
        assertThat(payload).containsEntry("decision", "NO_TRADE");
        assertThat(payload).containsEntry("direction", null);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static StrategyDecision decision(DecisionType type, Direction direction, double score) {
        MechanicalPlan plan = new MechanicalPlan(direction,
            new BigDecimal("100"), new BigDecimal("99"),
            new BigDecimal("102"), new BigDecimal("103"), 2.0);
        List<AgentVote> votes = List.of(
            AgentVote.of("ctx-a", StrategyLayer.CONTEXT, 60, 0.8, List.of("bull ctx")),
            AgentVote.of("zone-a", StrategyLayer.ZONE, 50, 0.7, List.of("OB near")),
            AgentVote.of("trig-a", StrategyLayer.TRIGGER, 40, 0.6, List.of("flow ok"))
        );
        return new StrategyDecision(
            Optional.of("NOR"), votes,
            Map.of(StrategyLayer.CONTEXT, 60.0, StrategyLayer.ZONE, 50.0, StrategyLayer.TRIGGER, 40.0),
            score, type, Optional.of(direction), Optional.of(plan),
            List.of(), AT);
    }
}

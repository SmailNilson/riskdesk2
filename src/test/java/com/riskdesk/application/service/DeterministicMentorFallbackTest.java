package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the deterministic fallback invoked when Gemini is unavailable.
 *
 * <p>Policy: synthetic response is always INELIGIBLE (conservative stand-down).
 * The mechanical plan from the playbook is preserved for operator visibility.
 */
class DeterministicMentorFallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeterministicMentorFallback fallback = new DeterministicMentorFallback();

    @Test
    void build_withFullPlaybookPayload_preservesMechanicalPlanAsIneligible() throws Exception {
        JsonNode payload = objectMapper.readTree("""
            {
              "metadata": {"asset": "MGC1!", "timeframe_focus": "10m"},
              "trade_intention": {"action": "LONG"},
              "market_structure_the_king": {"last_event": "BOS_BULLISH"},
              "playbook_pre_analysis": {
                "checklist_score": "6/7",
                "verdict": "Setup structurel valide",
                "checklist_items": [
                  {"label": "Zone Premium/Discount respectée", "passed": true},
                  {"label": "BOS dans la même direction", "passed": true},
                  {"label": "Flux acheteur", "passed": false},
                  {"label": "R:R >= 2", "passed": true}
                ],
                "mechanical_plan": {
                  "entry": 3050.25,
                  "sl": 3045.0,
                  "tp1": 3065.5,
                  "rr": 2.9,
                  "sl_rationale": "Sous la zone OB + marge ATR 0.3"
                }
              }
            }
            """);

        MentorAnalyzeResponse response = fallback.build(payload, "Connection reset by peer");

        // Metadata
        assertThat(response.auditId()).isNull();
        assertThat(response.model()).isEqualTo(DeterministicMentorFallback.FALLBACK_MODEL);
        assertThat(response.payload()).isSameAs(payload);
        assertThat(response.rawResponse()).isNull();
        assertThat(response.similarAudits()).isEmpty();

        MentorStructuredResponse structured = response.analysis();
        assertThat(structured).isNotNull();

        // Policy: conservative stand-down — INELIGIBLE even though playbook score is high.
        assertThat(structured.executionEligibilityStatus()).isEqualTo(ExecutionEligibilityStatus.INELIGIBLE);
        assertThat(structured.verdict()).isEqualTo(DeterministicMentorFallback.FALLBACK_VERDICT);
        assertThat(structured.executionEligibilityReason())
            .contains("Mentor IA offline")
            .contains("Connection reset by peer")
            .contains("6/7");

        // Technical summary surfaces the key payload fields.
        assertThat(structured.technicalQuickAnalysis())
            .contains("MGC1!")
            .contains("LONG")
            .contains("10m")
            .contains("BOS_BULLISH");

        // Strengths: only the checklist items that passed + the playbook verdict.
        assertThat(structured.strengths())
            .contains("Zone Premium/Discount respectée")
            .contains("BOS dans la même direction")
            .doesNotContain("Flux acheteur")
            .anyMatch(s -> s.startsWith("Playbook:"));

        // Errors explain that the fallback was triggered.
        assertThat(structured.errors())
            .anyMatch(e -> e.contains("Mentor IA indisponible"))
            .anyMatch(e -> e.contains("revue humaine"));

        // Mechanical plan preserved for UI display.
        MentorProposedTradePlan plan = structured.proposedTradePlan();
        assertThat(plan).isNotNull();
        assertThat(plan.entryPrice()).isEqualTo(3050.25);
        assertThat(plan.stopLoss()).isEqualTo(3045.0);
        assertThat(plan.takeProfit()).isEqualTo(3065.5);
        assertThat(plan.rewardToRiskRatio()).isEqualTo(2.9);
        assertThat(plan.tpSource()).isEqualTo("PLAYBOOK_MECHANICAL");
        assertThat(plan.rationale()).contains("Sous la zone OB");
    }

    @Test
    void build_withNoPlaybook_returnsIneligibleWithoutPlan() throws Exception {
        JsonNode payload = objectMapper.readTree("""
            {
              "metadata": {"asset": "MCL1!", "timeframe_focus": "5m"},
              "trade_intention": {"action": "SHORT"}
            }
            """);

        MentorAnalyzeResponse response = fallback.build(payload, "timeout");

        MentorStructuredResponse structured = response.analysis();
        assertThat(structured.executionEligibilityStatus()).isEqualTo(ExecutionEligibilityStatus.INELIGIBLE);
        assertThat(structured.proposedTradePlan()).isNull();
        assertThat(structured.strengths()).isEmpty();
        assertThat(structured.technicalQuickAnalysis()).contains("MCL1!").contains("SHORT");
        assertThat(structured.executionEligibilityReason()).contains("timeout");
    }

    @Test
    void build_withBlankReason_fallsBackToGenericLabel() throws Exception {
        JsonNode payload = objectMapper.readTree("""
            {"metadata": {"asset": "MNQ1!"}, "trade_intention": {"action": "LONG"}}
            """);

        MentorAnalyzeResponse response = fallback.build(payload, "   ");

        assertThat(response.analysis().executionEligibilityReason())
            .contains("Gemini mentor unavailable");
    }

    @Test
    void build_withNullReason_fallsBackToGenericLabel() throws Exception {
        JsonNode payload = objectMapper.readTree("""
            {"metadata": {"asset": "MNQ1!"}, "trade_intention": {"action": "LONG"}}
            """);

        MentorAnalyzeResponse response = fallback.build(payload, null);

        assertThat(response.analysis().executionEligibilityReason())
            .contains("Gemini mentor unavailable");
    }

    @Test
    void build_withNullPayload_stillReturnsIneligibleResponse() {
        MentorAnalyzeResponse response = fallback.build(null, "Network down");

        assertThat(response).isNotNull();
        assertThat(response.payload()).isNull();
        assertThat(response.analysis().executionEligibilityStatus()).isEqualTo(ExecutionEligibilityStatus.INELIGIBLE);
        assertThat(response.analysis().proposedTradePlan()).isNull();
        assertThat(response.analysis().executionEligibilityReason()).contains("Network down");
    }

    @Test
    void build_withPartialMechanicalPlan_stillReturnsPlan() throws Exception {
        // Only entry and SL — no TP/RR. Fallback should still produce a plan skeleton.
        JsonNode payload = objectMapper.readTree("""
            {
              "metadata": {"asset": "E6", "timeframe_focus": "1h"},
              "trade_intention": {"action": "LONG"},
              "playbook_pre_analysis": {
                "checklist_score": "4/7",
                "mechanical_plan": {
                  "entry": 1.0845,
                  "sl": 1.0820
                }
              }
            }
            """);

        MentorAnalyzeResponse response = fallback.build(payload, "503 Service Unavailable");

        MentorProposedTradePlan plan = response.analysis().proposedTradePlan();
        assertThat(plan).isNotNull();
        assertThat(plan.entryPrice()).isEqualTo(1.0845);
        assertThat(plan.stopLoss()).isEqualTo(1.0820);
        assertThat(plan.takeProfit()).isNull();
        assertThat(plan.rewardToRiskRatio()).isNull();
    }

    @Test
    void build_withEmptyMechanicalPlan_omitsPlan() throws Exception {
        JsonNode payload = objectMapper.readTree("""
            {
              "metadata": {"asset": "MGC1!"},
              "trade_intention": {"action": "SHORT"},
              "playbook_pre_analysis": {
                "checklist_score": "2/7",
                "mechanical_plan": {}
              }
            }
            """);

        MentorAnalyzeResponse response = fallback.build(payload, "HTTP 500");

        assertThat(response.analysis().proposedTradePlan()).isNull();
    }

    @Test
    void build_withStringNumericFields_parsesCorrectly() throws Exception {
        // Some payloads encode numbers as strings — ensure the fallback is tolerant.
        JsonNode payload = objectMapper.readTree("""
            {
              "metadata": {"asset": "MNQ1!"},
              "trade_intention": {"action": "LONG"},
              "playbook_pre_analysis": {
                "checklist_score": "6/7",
                "mechanical_plan": {
                  "entry": "24410.5",
                  "sl": "24390.0",
                  "tp1": "24450.0",
                  "rr": "2.0"
                }
              }
            }
            """);

        MentorAnalyzeResponse response = fallback.build(payload, "read timed out");

        MentorProposedTradePlan plan = response.analysis().proposedTradePlan();
        assertThat(plan).isNotNull();
        assertThat(plan.entryPrice()).isEqualTo(24410.5);
        assertThat(plan.stopLoss()).isEqualTo(24390.0);
        assertThat(plan.takeProfit()).isEqualTo(24450.0);
        assertThat(plan.rewardToRiskRatio()).isEqualTo(2.0);
    }

    @Test
    void build_eligibilityReason_alwaysMentionsStandDownSemantics() throws Exception {
        JsonNode payload = objectMapper.readTree("""
            {"metadata": {"asset": "MCL1!"}, "trade_intention": {"action": "SHORT"}}
            """);

        MentorAnalyzeResponse response = fallback.build(payload, "429 quota exhausted");
        String reason = response.analysis().executionEligibilityReason();

        // The operator must understand this is a policy-driven stand-down, not a verdict.
        assertThat(reason)
            .contains("conservative standdown")
            .contains("Execution auto bloquee")
            .contains("revue humaine");
    }

    @Test
    void build_improvementTip_isActionable() throws Exception {
        JsonNode payload = objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "LONG"}}
            """);

        MentorAnalyzeResponse response = fallback.build(payload, "connection refused");

        assertThat(response.analysis().improvementTip())
            .isNotBlank()
            .contains("mentor IA");
    }
}

package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentorAnalysisServiceTest {

    @Mock
    private MentorModelClient mentorModelClient;

    @Mock
    private MentorAuditRepositoryPort mentorAuditRepository;

    @Mock
    private MentorMemoryService mentorMemoryService;

    private final MentorProperties mentorProperties = new MentorProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void analyze_parsesStructuredResponseAndPersistsAudit() throws Exception {
        mentorProperties.setPersistAudits(true);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient,
            mentorMemoryService,
            mentorAuditRepository,
            mentorProperties,
            objectMapper
        );

        String rawJson = """
            {
              "technicalQuickAnalysis": "Trend bullish near VWAP.",
              "strengths": ["Respect de la structure"],
              "errors": ["Corrélations absentes"],
              "verdict": "Trade Validé - Discipline Respectée",
              "executionEligibilityStatus": "ELIGIBLE",
              "executionEligibilityReason": "Plan complet et setup conforme.",
              "improvementTip": "Attends plus de confluence macro.",
              "proposedTradePlan": {
                "entryPrice": 3012.5,
                "stopLoss": 3005.0,
                "takeProfit": 3035.0,
                "rewardToRiskRatio": 3.0,
                "rationale": "Entrée sur reprise.",
                "safeDeepEntry": {
                  "entryPrice": 3008.0,
                  "rationale": "Entrée plus basse si le money flow reste vendeur."
                }
              }
            }
            """;
        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of(
            new MentorSimilarAudit(9L, Instant.parse("2026-03-25T02:00:00Z"), "MGC1!", "M5", "LONG", "Trade Validé - Discipline Respectée", 0.91, "MGC1! | LONG | VWAP")
        ));
        when(mentorModelClient.analyze(any(), any())).thenReturn(new MentorModelClient.MentorModelResult("gemini-test", rawJson));
        when(mentorAuditRepository.save(any())).thenAnswer(invocation -> {
            MentorAudit audit = invocation.getArgument(0);
            audit.setId(42L);
            return audit;
        });

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {
              "metadata": {"asset": "MGC1!", "timeframe_focus": "M5", "selected_timezone": "Africa/Casablanca"},
              "trade_intention": {"action": "LONG"}
            }
            """));

        assertThat(response.auditId()).isEqualTo(42L);
        assertThat(response.model()).isEqualTo("gemini-test");
        assertThat(response.analysis().verdict()).isEqualTo("Trade Validé - Discipline Respectée");
        assertThat(response.analysis().executionEligibilityStatus()).isEqualTo(ExecutionEligibilityStatus.ELIGIBLE);
        assertThat(response.analysis().strengths()).containsExactly("Respect de la structure");
        assertThat(response.analysis().proposedTradePlan()).isNotNull();
        assertThat(response.analysis().proposedTradePlan().safeDeepEntry()).isNotNull();
        assertThat(response.similarAudits()).hasSize(1);
        ArgumentCaptor<MentorAudit> auditCaptor = ArgumentCaptor.forClass(MentorAudit.class);
        verify(mentorAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getSelectedTimezone()).isEqualTo("Africa/Casablanca");
    }

    @Test
    void analyze_fallsBackWhenModelReturnsNonJson() throws Exception {
        mentorProperties.setPersistAudits(false);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient,
            mentorMemoryService,
            mentorAuditRepository,
            mentorProperties,
            objectMapper
        );

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any())).thenReturn(new MentorModelClient.MentorModelResult("gemini-test", "plain text answer"));

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "SHORT"}}
            """));

        assertThat(response.auditId()).isNull();
        assertThat(response.analysis().technicalQuickAnalysis()).isEqualTo("plain text answer");
        assertThat(response.analysis().errors()).contains("La réponse du modèle n'était pas un JSON strictement exploitable.");
    }

    @Test
    void analyze_recoversFromTruncatedJson() throws Exception {
        // Reproduces the prod bug: Gemini response truncated mid-string in the first field
        // (maxOutputTokens exhausted). The strict parser fails; the recovery path should
        // salvage whatever fields are present and mark the response as partial.
        mentorProperties.setPersistAudits(false);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient,
            mentorMemoryService,
            mentorAuditRepository,
            mentorProperties,
            objectMapper
        );

        // Truncated mid-technicalQuickAnalysis — no closing quote, no closing brace.
        String truncated = "{\"technicalQuickAnalysis\": \"Trend is bullish near VWAP with absorption on demand OB";

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", truncated));

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "LONG"}}
            """));

        assertThat(response.analysis().technicalQuickAnalysis())
            .isEqualTo("Trend is bullish near VWAP with absorption on demand OB");
        // Recovered responses stay INELIGIBLE — promotion to ELIGIBLE is the playbook fallback's job.
        assertThat(response.analysis().executionEligibilityStatus())
            .isEqualTo(ExecutionEligibilityStatus.INELIGIBLE);
        assertThat(response.analysis().errors())
            .anySatisfy(e -> assertThat(e).contains("tronquée"));
        assertThat(response.analysis().verdict()).contains("Analyse Partielle");
    }

    @Test
    void analyze_buildsSemanticTextFromCurrentV2Keys() throws Exception {
        // Regression for the silent RAG bug: before the fix, the semantic text
        // read `market_structure_the_king` / `momentum_and_flow_the_trigger` —
        // keys the v2 payload writer no longer emits — so every embedding was
        // built from asset|action|"" |"" |"". The new builder reads v2 keys first.
        mentorProperties.setPersistAudits(true);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository, mentorProperties, objectMapper);

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test",
                "{\"verdict\":\"Trade Validé - Discipline Respectée\"}"));
        when(mentorAuditRepository.save(any())).thenAnswer(inv -> {
            MentorAudit a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });

        service.analyze(objectMapper.readTree("""
            {
              "metadata": {"asset": "MGC1!", "market_session": "NEW_YORK"},
              "trade_intention": {"action": "LONG", "analysis_mode": "CONSERVATIVE"},
              "market_structure_smc": {"last_event": "BOS_UP"},
              "momentum_oscillators": {"money_flow_state": "BUYING"},
              "risk_and_emotional_check": {"reward_to_risk_ratio": "2.5"}
            }
            """));

        ArgumentCaptor<MentorAudit> captor = ArgumentCaptor.forClass(MentorAudit.class);
        verify(mentorAuditRepository).save(captor.capture());
        String semantic = captor.getValue().getSemanticText();
        // Before the fix BOS_UP and BUYING would be missing from the embedding text.
        assertThat(semantic).contains("BOS_UP").contains("BUYING");
    }

    @Test
    void analyze_buildsSemanticTextFromLegacyKeysAsFallback() throws Exception {
        // Historical audits stored under v1 keys must still produce a usable
        // embedding when re-indexed. The builder reads v2 first, v1 second.
        mentorProperties.setPersistAudits(true);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository, mentorProperties, objectMapper);

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test",
                "{\"verdict\":\"Trade Validé - Discipline Respectée\"}"));
        when(mentorAuditRepository.save(any())).thenAnswer(inv -> {
            MentorAudit a = inv.getArgument(0);
            a.setId(2L);
            return a;
        });

        service.analyze(objectMapper.readTree("""
            {
              "metadata": {"asset": "MGC1!"},
              "trade_intention": {"action": "LONG"},
              "market_structure_the_king": {"last_event": "LEGACY_BOS"},
              "momentum_and_flow_the_trigger": {"money_flow_state": "LEGACY_BUYING"}
            }
            """));

        ArgumentCaptor<MentorAudit> captor = ArgumentCaptor.forClass(MentorAudit.class);
        verify(mentorAuditRepository).save(captor.capture());
        String semantic = captor.getValue().getSemanticText();
        assertThat(semantic).contains("LEGACY_BOS").contains("LEGACY_BUYING");
    }

    @Test
    void analyze_recoversPartialJsonWithTradePlanFields() throws Exception {
        // Truncated later in the response — we got strengths + part of the trade plan.
        mentorProperties.setPersistAudits(false);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient,
            mentorMemoryService,
            mentorAuditRepository,
            mentorProperties,
            objectMapper
        );

        String truncated = """
            {
              "technicalQuickAnalysis": "Bullish setup with BOS confirmed.",
              "strengths": ["Respect de la structure", "Flow acheteur"],
              "verdict": "Trade Validé - Discipline Respectée",
              "executionEligibilityStatus": "ELIGIBLE",
              "executionEligibilityReason": "Plan conforme.",
              "proposedTradePlan": {
                "entryPrice": 3012.5,
                "stopLoss": 3005.0,
                "takeProfit": 3035.0,
                "rationale": "Entree sur pullback EMA
            """;

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", truncated));

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "LONG"}}
            """));

        assertThat(response.analysis().strengths()).containsExactly("Respect de la structure", "Flow acheteur");
        assertThat(response.analysis().proposedTradePlan()).isNotNull();
        assertThat(response.analysis().proposedTradePlan().entryPrice()).isEqualTo(3012.5);
        assertThat(response.analysis().proposedTradePlan().stopLoss()).isEqualTo(3005.0);
        assertThat(response.analysis().proposedTradePlan().takeProfit()).isEqualTo(3035.0);
        // Still INELIGIBLE — the strict-schema promise was broken, downstream must decide.
        assertThat(response.analysis().executionEligibilityStatus())
            .isEqualTo(ExecutionEligibilityStatus.INELIGIBLE);
        assertThat(response.analysis().errors())
            .anySatisfy(e -> assertThat(e).contains("tronquée"));
    }
}

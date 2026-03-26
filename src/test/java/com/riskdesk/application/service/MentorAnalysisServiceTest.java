package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
              "metadata": {"asset": "MGC1!", "timeframe_focus": "M5"},
              "trade_intention": {"action": "LONG"}
            }
            """));

        assertThat(response.auditId()).isEqualTo(42L);
        assertThat(response.model()).isEqualTo("gemini-test");
        assertThat(response.analysis().verdict()).isEqualTo("Trade Validé - Discipline Respectée");
        assertThat(response.analysis().strengths()).containsExactly("Respect de la structure");
        assertThat(response.analysis().proposedTradePlan()).isNotNull();
        assertThat(response.analysis().proposedTradePlan().safeDeepEntry()).isNotNull();
        assertThat(response.similarAudits()).hasSize(1);
        verify(mentorAuditRepository).save(any());
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
}

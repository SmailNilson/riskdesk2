package com.riskdesk.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.service.MentorAnalysisService;
import com.riskdesk.presentation.dto.MentorAnalyzeResponse;
import com.riskdesk.presentation.dto.MentorProposedTradePlan;
import com.riskdesk.presentation.dto.MentorSimilarAudit;
import com.riskdesk.presentation.dto.MentorStructuredResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MentorControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MentorAnalysisService mentorAnalysisService;

    @Test
    void analyze_validPayload_returnsStructuredResponse() throws Exception {
        when(mentorAnalysisService.analyze(any())).thenReturn(
            new MentorAnalyzeResponse(
                7L,
                "gemini-test",
                objectMapper.readTree("{\"metadata\":{\"asset\":\"MGC1!\"}}"),
                new MentorStructuredResponse(
                    "Trend mixed.",
                    List.of("VWAP respected"),
                    List.of("No DXY context"),
                    "Trade Non-Conforme - Erreur de Processus",
                    "Wait for clearer structure.",
                    new MentorProposedTradePlan(3012.5, 3005.0, 3035.0, 3.0, "Plan proposé")
                ),
                "{\"verdict\":\"Trade Non-Conforme - Erreur de Processus\"}",
                List.of(new MentorSimilarAudit(12L, java.time.Instant.parse("2026-03-25T01:00:00Z"), "MGC1!", "M5", "LONG", "Trade Validé - Discipline Respectée", 0.88, "MGC1! | LONG"))
            )
        );

        mockMvc.perform(post("/api/mentor/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "payload": {
                        "metadata": {"asset": "MGC1!"},
                        "trade_intention": {"action": "LONG"}
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auditId").value(7))
            .andExpect(jsonPath("$.model").value("gemini-test"))
            .andExpect(jsonPath("$.analysis.verdict").value("Trade Non-Conforme - Erreur de Processus"))
            .andExpect(jsonPath("$.analysis.strengths[0]").value("VWAP respected"))
            .andExpect(jsonPath("$.analysis.proposedTradePlan.entryPrice").value(3012.5))
            .andExpect(jsonPath("$.similarAudits[0].auditId").value(12));
    }

    @Test
    void analyze_whenServiceUnavailable_returns503() throws Exception {
        when(mentorAnalysisService.analyze(any()))
            .thenThrow(new IllegalStateException("GEMINI_API_KEY is not configured on the backend."));

        mockMvc.perform(post("/api/mentor/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "payload": {
                        "metadata": {"asset": "MGC1!"},
                        "trade_intention": {"action": "LONG"}
                      }
                    }
                    """))
            .andExpect(status().isServiceUnavailable());
    }
}

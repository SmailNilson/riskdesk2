package com.riskdesk.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.application.service.HistoricalTradeImporterService;
import com.riskdesk.application.service.MentorAnalysisService;
import com.riskdesk.application.service.MentorMemoryService;
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

    @MockBean
    private HistoricalTradeImporterService historicalTradeImporterService;

    @MockBean
    private MentorMemoryService mentorMemoryService;

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

    @Test
    void importHistoricalTrades_withJsonPayload_returnsSummary() throws Exception {
        when(historicalTradeImporterService.importTrades(any()))
            .thenReturn(new HistoricalTradeImporterService.ImportSummary(1, 0, 0));
        when(mentorMemoryService.currentStorageMode()).thenReturn("pgvector");

        mockMvc.perform(post("/api/mentor/import-historical-trades")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "instrument": "MGC",
                      "trades": [
                        {
                          "trade_id": "MGC_TEST_001",
                          "instrument": "MGC",
                          "timeframe": "M5",
                          "direction": "Long",
                          "entry_price": 4700.0,
                          "ai_verdict": "Valid Trade"
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.instrument").value("MGC"))
            .andExpect(jsonPath("$.imported").value(1))
            .andExpect(jsonPath("$.storage").value("pgvector"))
            .andExpect(jsonPath("$.source").value("request_body"));
    }

    @Test
    void importHistoricalTradesFromFile_withPath_returnsSummary() throws Exception {
        when(historicalTradeImporterService.importFromFile(any()))
            .thenReturn(new HistoricalTradeImporterService.ImportSummary(3, 1, 0));
        when(mentorMemoryService.currentStorageMode()).thenReturn("pgvector");

        mockMvc.perform(post("/api/mentor/import-historical-trades/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "filePath": "/tmp/historical_trades.json"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(3))
            .andExpect(jsonPath("$.skipped").value(1))
            .andExpect(jsonPath("$.storage").value("pgvector"))
            .andExpect(jsonPath("$.source").value("/tmp/historical_trades.json"));
    }
}

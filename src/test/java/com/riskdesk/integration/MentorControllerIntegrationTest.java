package com.riskdesk.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorAlternativeEntry;
import com.riskdesk.application.dto.MentorManualReview;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.application.dto.MentorSignalReview;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.application.service.HistoricalTradeImporterService;
import com.riskdesk.application.service.MentorAnalysisService;
import com.riskdesk.application.service.MentorManualReviewService;
import com.riskdesk.application.service.MentorMemoryService;
import com.riskdesk.application.service.MentorSignalReviewService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockBean
    private MentorManualReviewService mentorManualReviewService;

    @MockBean
    private MentorSignalReviewService mentorSignalReviewService;

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
                    new MentorProposedTradePlan(
                        3012.5,
                        3005.0,
                        3035.0,
                        3.0,
                        "Plan proposé",
                        new MentorAlternativeEntry(3008.0, "Entrée safe plus profonde.")
                    )
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
            .andExpect(jsonPath("$.analysis.proposedTradePlan.safeDeepEntry.entryPrice").value(3008.0))
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
    void recentManualReviews_returnsManualMentorHistory() throws Exception {
        when(mentorManualReviewService.getRecentManualReviews()).thenReturn(List.of(
            new MentorManualReview(
                21L,
                "MANUAL_MENTOR",
                "2026-03-26T03:10:54Z",
                "E6",
                "M10",
                "LONG",
                "gemini-test",
                "Trade Validé - Discipline Respectée",
                true,
                null,
                new MentorAnalyzeResponse(
                    21L,
                    "gemini-test",
                    objectMapper.readTree("{\"metadata\":{\"asset\":\"6E1!\"}}"),
                    new MentorStructuredResponse(
                        "Pullback propre.",
                        List.of("Momentum clean"),
                        List.of(),
                        "Trade Validé - Discipline Respectée",
                        "Patience sur l'entrée.",
                        null
                    ),
                    "{\"verdict\":\"Trade Validé - Discipline Respectée\"}",
                    List.of()
                )
            )
        ));

        mockMvc.perform(get("/api/mentor/manual-reviews/recent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].auditId").value(21))
            .andExpect(jsonPath("$[0].sourceType").value("MANUAL_MENTOR"))
            .andExpect(jsonPath("$[0].response.model").value("gemini-test"));
    }

    @Test
    void recentAutoAlerts_returnsSavedReviews() throws Exception {
        when(mentorSignalReviewService.getRecentReviews()).thenReturn(List.of(
            new MentorSignalReview(
                5L,
                "2026-03-26T02:30:39Z:MNQ:SMC:Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
                1,
                "INITIAL",
                "DONE",
                "INFO",
                "SMC",
                "Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
                "MNQ",
                "10m",
                "SHORT",
                "2026-03-26T02:30:39Z",
                "2026-03-26T02:30:40Z",
                null,
                null
            )
        ));

        mockMvc.perform(get("/api/mentor/auto-alerts/recent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(5))
            .andExpect(jsonPath("$[0].alertKey").exists())
            .andExpect(jsonPath("$[0].triggerType").value("INITIAL"));
    }

    @Test
    void autoAlertThread_returnsPersistedHistory() throws Exception {
        when(mentorSignalReviewService.getReviewsForAlert(any())).thenReturn(List.of(
            new MentorSignalReview(
                11L,
                "2026-03-26T02:30:39Z:MNQ:SMC:Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
                1,
                "INITIAL",
                "DONE",
                "INFO",
                "SMC",
                "Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
                "MNQ",
                "10m",
                "SHORT",
                "2026-03-26T02:30:39Z",
                "2026-03-26T02:30:40Z",
                null,
                null
            ),
            new MentorSignalReview(
                12L,
                "2026-03-26T02:30:39Z:MNQ:SMC:Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
                2,
                "MANUAL_REANALYSIS",
                "ANALYZING",
                "INFO",
                "SMC",
                "Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
                "MNQ",
                "10m",
                "SHORT",
                "2026-03-26T02:30:39Z",
                "2026-03-26T02:31:10Z",
                null,
                null
            )
        ));

        mockMvc.perform(post("/api/mentor/auto-alerts/thread")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "severity": "INFO",
                      "category": "SMC",
                      "message": "Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
                      "instrument": "MNQ",
                      "timestamp": "2026-03-26T02:30:39Z"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].triggerType").value("MANUAL_REANALYSIS"));
    }

    @Test
    void reanalyzeExistingAlert_returnsPendingReview() throws Exception {
        when(mentorSignalReviewService.reanalyzeAlert(any())).thenReturn(
            new MentorSignalReview(
                13L,
                "2026-03-26T02:30:39Z:MNQ:SMC:Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
                3,
                "MANUAL_REANALYSIS",
                "ANALYZING",
                "INFO",
                "SMC",
                "Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
                "MNQ",
                "10m",
                "SHORT",
                "2026-03-26T02:30:39Z",
                "2026-03-26T02:32:00Z",
                null,
                null
            )
        );

        mockMvc.perform(post("/api/mentor/auto-alerts/reanalyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "severity": "INFO",
                      "category": "SMC",
                      "message": "Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
                      "instrument": "MNQ",
                      "timestamp": "2026-03-26T02:30:39Z"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(13))
            .andExpect(jsonPath("$.revision").value(3))
            .andExpect(jsonPath("$.status").value("ANALYZING"));
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

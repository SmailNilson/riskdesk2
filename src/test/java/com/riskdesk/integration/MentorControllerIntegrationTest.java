package com.riskdesk.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorAlternativeEntry;
import com.riskdesk.application.dto.MentorManualReview;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.application.dto.MentorSignalReview;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.application.service.ExecutionManagerService;
import com.riskdesk.application.service.HistoricalTradeImporterService;
import com.riskdesk.application.service.MentorAnalysisService;
import com.riskdesk.application.service.MentorManualReviewService;
import com.riskdesk.application.service.MentorMemoryService;
import com.riskdesk.application.service.MentorSignalReviewService;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyCollection;
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

    @MockBean
    private ExecutionManagerService executionManagerService;

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
                    ExecutionEligibilityStatus.INELIGIBLE,
                    "Setup not executable.",
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
                "Africa/Casablanca",
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
                        ExecutionEligibilityStatus.ELIGIBLE,
                        "Review explicitly eligible.",
                        "Patience sur l'entrée.",
                        null
                    ),
                    "{\"verdict\":\"Trade Validé - Discipline Respectée\"}",
                    List.of()
                ),
                null,
                null,
                null,
                null
            )
        ));

        mockMvc.perform(get("/api/mentor/manual-reviews/recent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].auditId").value(21))
            .andExpect(jsonPath("$[0].sourceType").value("MANUAL_MENTOR"))
            .andExpect(jsonPath("$[0].selectedTimezone").value("Africa/Casablanca"))
            .andExpect(jsonPath("$[0].response.model").value("gemini-test"));
    }

    @Test
    void recentAutoAlerts_returnsSavedReviews() throws Exception {
        when(mentorSignalReviewService.getRecentReviews(anyInt())).thenReturn(List.of(
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
                "UTC",
                ExecutionEligibilityStatus.ELIGIBLE,
                "Review explicitly eligible.",
                null,
                null,
                null,
                null,
                null,
                null
            )
        ));

        mockMvc.perform(get("/api/mentor/auto-alerts/recent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(5))
            .andExpect(jsonPath("$[0].alertKey").exists())
            .andExpect(jsonPath("$[0].selectedTimezone").value("UTC"))
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
                "UTC",
                ExecutionEligibilityStatus.ELIGIBLE,
                "Review explicitly eligible.",
                null,
                null,
                null,
                null,
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
                "Africa/Casablanca",
                ExecutionEligibilityStatus.NOT_EVALUATED,
                "Mentor analysis pending.",
                null,
                null,
                null,
                null,
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
        when(mentorSignalReviewService.reanalyzeAlert(any(), any(), any(), any(), any())).thenReturn(
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
                "Africa/Casablanca",
                ExecutionEligibilityStatus.NOT_EVALUATED,
                "Mentor analysis pending.",
                null,
                null,
                null,
                null,
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
                      "timestamp": "2026-03-26T02:30:39Z",
                      "selectedTimezone": "Africa/Casablanca"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(13))
            .andExpect(jsonPath("$.revision").value(3))
            .andExpect(jsonPath("$.selectedTimezone").value("Africa/Casablanca"))
            .andExpect(jsonPath("$.status").value("ANALYZING"));
    }

    @Test
    void createExecution_returnsSlice1Execution() throws Exception {
        when(executionManagerService.ensureExecutionCreated(any())).thenReturn(
            new com.riskdesk.domain.model.TradeExecutionRecord() {{
                setId(801L);
                setVersion(0L);
                setExecutionKey("exec:mentor-review:13");
                setMentorSignalReviewId(13L);
                setReviewAlertKey("2026-03-26T02:30:39Z:MNQ:SMC:...");
                setReviewRevision(3);
                setBrokerAccountId("DU1234567");
                setInstrument("MNQ");
                setTimeframe("10m");
                setAction("SHORT");
                setQuantity(2);
                setTriggerSource(ExecutionTriggerSource.MANUAL_ARMING);
                setRequestedBy("mentor-panel");
                setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
                setStatusReason("Execution foundation created. IBKR placement not started.");
                setNormalizedEntryPrice(new java.math.BigDecimal("18123.50"));
                setVirtualStopLoss(new java.math.BigDecimal("18099.75"));
                setVirtualTakeProfit(new java.math.BigDecimal("18160.25"));
                setCreatedAt(java.time.Instant.parse("2026-03-28T16:01:00Z"));
                setUpdatedAt(java.time.Instant.parse("2026-03-28T16:01:00Z"));
            }}
        );

        mockMvc.perform(post("/api/mentor/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "mentorSignalReviewId": 13,
                      "brokerAccountId": "DU1234567",
                      "quantity": 2
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(801))
            .andExpect(jsonPath("$.mentorSignalReviewId").value(13))
            .andExpect(jsonPath("$.brokerAccountId").value("DU1234567"))
            .andExpect(jsonPath("$.quantity").value(2))
            .andExpect(jsonPath("$.status").value("PENDING_ENTRY_SUBMISSION"));
    }

    @Test
    void createExecution_whenReviewIsNotEligible_returns409() throws Exception {
        when(executionManagerService.ensureExecutionCreated(any()))
            .thenThrow(new IllegalStateException("mentor review is not execution-eligible"));

        mockMvc.perform(post("/api/mentor/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "mentorSignalReviewId": 13,
                      "brokerAccountId": "DU1234567",
                      "quantity": 2
                    }
                    """))
            .andExpect(status().isConflict());
    }

    @Test
    void executionsByReviewIds_returnsExecutionBatch() throws Exception {
        when(executionManagerService.findByMentorSignalReviewIds(anyCollection())).thenReturn(List.of(
            new com.riskdesk.domain.model.TradeExecutionRecord() {{
                setId(901L);
                setExecutionKey("exec:mentor-review:21");
                setMentorSignalReviewId(21L);
                setReviewAlertKey("alert-key-21");
                setReviewRevision(1);
                setBrokerAccountId("DU1234567");
                setInstrument("MGC");
                setTimeframe("5m");
                setAction("LONG");
                setQuantity(1);
                setTriggerSource(ExecutionTriggerSource.MANUAL_ARMING);
                setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
                setNormalizedEntryPrice(new java.math.BigDecimal("3012.50"));
                setVirtualStopLoss(new java.math.BigDecimal("3005.00"));
                setVirtualTakeProfit(new java.math.BigDecimal("3035.00"));
                setCreatedAt(java.time.Instant.parse("2026-03-28T16:01:00Z"));
                setUpdatedAt(java.time.Instant.parse("2026-03-28T16:01:00Z"));
            }}
        ));

        mockMvc.perform(post("/api/mentor/executions/by-review-ids")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "mentorSignalReviewIds": [21, 22]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].mentorSignalReviewId").value(21))
            .andExpect(jsonPath("$[0].quantity").value(1))
            .andExpect(jsonPath("$[0].executionKey").value("exec:mentor-review:21"));
    }

    @Test
    void submitEntryOrder_returnsEntrySubmittedExecution() throws Exception {
        when(executionManagerService.submitEntryOrder(any())).thenReturn(
            new com.riskdesk.domain.model.TradeExecutionRecord() {{
                setId(901L);
                setExecutionKey("exec:mentor-review:21");
                setMentorSignalReviewId(21L);
                setReviewAlertKey("alert-key-21");
                setReviewRevision(1);
                setBrokerAccountId("DU1234567");
                setInstrument("MGC");
                setTimeframe("5m");
                setAction("LONG");
                setQuantity(1);
                setTriggerSource(ExecutionTriggerSource.MANUAL_ARMING);
                setStatus(ExecutionStatus.ENTRY_SUBMITTED);
                setEntryOrderId(44001L);
                setStatusReason("IBKR entry order submitted: Submitted");
                setNormalizedEntryPrice(new java.math.BigDecimal("3012.50"));
                setVirtualStopLoss(new java.math.BigDecimal("3005.00"));
                setVirtualTakeProfit(new java.math.BigDecimal("3035.00"));
                setEntrySubmittedAt(java.time.Instant.parse("2026-03-28T16:04:00Z"));
                setCreatedAt(java.time.Instant.parse("2026-03-28T16:01:00Z"));
                setUpdatedAt(java.time.Instant.parse("2026-03-28T16:04:00Z"));
            }}
        );

        mockMvc.perform(post("/api/mentor/executions/901/submit-entry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(901))
            .andExpect(jsonPath("$.entryOrderId").value(44001))
            .andExpect(jsonPath("$.status").value("ENTRY_SUBMITTED"));
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

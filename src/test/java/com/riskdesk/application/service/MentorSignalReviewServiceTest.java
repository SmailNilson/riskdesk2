package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.IndicatorSeriesSnapshot;
import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentorSignalReviewServiceTest {

    @Mock
    private MentorAnalysisService mentorAnalysisService;

    @Mock
    private IndicatorService indicatorService;

    @Mock
    private MentorIntermarketService mentorIntermarketService;

    @Mock
    private ObjectProvider<MarketDataService> marketDataServiceProvider;

    @Mock
    private MarketDataService marketDataService;

    @Mock
    private CandleRepositoryPort candleRepositoryPort;

    @Mock
    private ActiveContractRegistry contractRegistry;

    @Mock
    private MentorSignalReviewRepositoryPort reviewRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reanalyzeAlert_rebuildsLivePayloadAndPreservesOriginalAlertContext() throws Exception {
        MentorSignalReviewService service = new MentorSignalReviewService(
            mentorAnalysisService,
            indicatorService,
            mentorIntermarketService,
            marketDataServiceProvider,
            candleRepositoryPort,
            contractRegistry,
            reviewRepository,
            messagingTemplate,
            objectMapper
        );

        Alert alert = new Alert(
            "smc:MNQ:2026-03-26T02:30:39Z",
            AlertSeverity.INFO,
            "Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
            AlertCategory.SMC,
            "MNQ",
            Instant.parse("2026-03-26T02:30:39Z")
        );
        String alertKey = "2026-03-26T02:30:39Z:MNQ:SMC:Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH";
        String snapshotJson = """
            {
              "metadata": {
                "asset": "MNQ1!",
                "timestamp": "2026-03-26T02:30:39Z",
                "current_price": 24411.25
              },
              "market_structure_the_king": {
                "last_event": "CHOCH_BEARISH"
              },
              "trade_intention": {
                "action": "SHORT"
              }
            }
            """;

        MentorSignalReviewRecord baseReview = new MentorSignalReviewRecord();
        baseReview.setId(11L);
        baseReview.setAlertKey(alertKey);
        baseReview.setRevision(1);
        baseReview.setTriggerType("INITIAL");
        baseReview.setStatus("DONE");
        baseReview.setSeverity("INFO");
        baseReview.setCategory("SMC");
        baseReview.setMessage(alert.message());
        baseReview.setInstrument("MNQ");
        baseReview.setTimeframe("10m");
        baseReview.setAction("SHORT");
        baseReview.setAlertTimestamp(alert.timestamp());
        baseReview.setCreatedAt(alert.timestamp());
        baseReview.setSnapshotJson(snapshotJson);

        MentorSignalReviewRecord pendingReview = new MentorSignalReviewRecord();
        pendingReview.setId(12L);
        pendingReview.setAlertKey(alertKey);
        pendingReview.setRevision(2);
        pendingReview.setTriggerType("MANUAL_REANALYSIS");
        pendingReview.setStatus("ANALYZING");
        pendingReview.setSeverity("INFO");
        pendingReview.setCategory("SMC");
        pendingReview.setMessage(alert.message());
        pendingReview.setInstrument("MNQ");
        pendingReview.setTimeframe("10m");
        pendingReview.setAction("SHORT");
        pendingReview.setAlertTimestamp(alert.timestamp());
        pendingReview.setCreatedAt(Instant.parse("2026-03-26T02:31:00Z"));
        pendingReview.setSnapshotJson(snapshotJson);

        when(reviewRepository.findByAlertKeyOrderByRevisionAsc(alertKey)).thenReturn(List.of(baseReview));
        when(reviewRepository.save(any())).thenAnswer(invocation -> {
            MentorSignalReviewRecord record = invocation.getArgument(0);
            if (record.getId() == null) {
                record.setId(12L);
            }
            return record;
        });
        when(reviewRepository.findById(12L)).thenReturn(Optional.of(pendingReview));
        when(marketDataServiceProvider.getIfAvailable()).thenReturn(marketDataService);
        when(marketDataService.currentPrice(Instrument.MNQ)).thenReturn(
            new MarketDataService.StoredPrice(new BigDecimal("24430.75"), Instant.parse("2026-03-26T02:31:05Z"), "LIVE_PROVIDER")
        );
        when(indicatorService.computeSnapshot(Instrument.MNQ, "10m")).thenReturn(snapshot("10m", new BigDecimal("24422.50"), new BigDecimal("47.20"), "CHOCH_BEARISH"));
        when(indicatorService.computeSnapshot(Instrument.MNQ, "1h")).thenReturn(snapshot("1h", new BigDecimal("24405.00"), new BigDecimal("44.10"), "CHOCH_BEARISH"));
        when(indicatorService.computeSeries(Instrument.MNQ, "10m", 500)).thenReturn(new IndicatorSeriesSnapshot(
            "MNQ",
            "10m",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        ));
        when(candleRepositoryPort.findRecentCandles(Instrument.MNQ, "10m", 120)).thenReturn(List.of(
            candle("2026-03-26T02:20:00Z", "24418.25", "24422.00", "24410.00", "24415.00"),
            candle("2026-03-26T02:30:00Z", "24415.00", "24428.00", "24412.00", "24424.25")
        ));
        when(mentorIntermarketService.current(Instrument.MNQ)).thenReturn(new MentorIntermarketSnapshot(
            0.35,
            "BULLISH",
            null,
            null,
            null,
            "DXY_AVAILABLE"
        ));
        when(mentorAnalysisService.analyze(any(), any())).thenReturn(new MentorAnalyzeResponse(
            99L,
            "gemini-test",
            objectMapper.readTree("""
                {"trade_intention":{"review_type":"MANUAL_REANALYSIS"}}
                """),
            new MentorStructuredResponse(
                "Structure baissiere propre.",
                List.of("Live re-check"),
                List.of(),
                "Trade Validé - Discipline Respectée",
                ExecutionEligibilityStatus.ELIGIBLE,
                "Setup still executable.",
                "Rester discipline.",
                null
            ),
            "{\"verdict\":\"Trade Validé - Discipline Respectée\"}",
            List.of()
        ));

        var response = service.reanalyzeAlert(alert);

        assertThat(response.revision()).isEqualTo(2);
        assertThat(response.triggerType()).isEqualTo("MANUAL_REANALYSIS");
        assertThat(response.status()).isEqualTo("ANALYZING");

        ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(mentorAnalysisService, timeout(1000)).analyze(payloadCaptor.capture(), startsWith(MentorAnalysisService.ALERT_SOURCE_PREFIX));
        assertThat(payloadCaptor.getValue().path("metadata").path("asset").asText()).isEqualTo("MNQ1!");
        assertThat(payloadCaptor.getValue().path("metadata").path("timestamp").asText()).isEqualTo("2026-03-26T02:31:05Z");
        assertThat(payloadCaptor.getValue().path("metadata").path("current_price").decimalValue()).isEqualByComparingTo("24430.75");
        assertThat(payloadCaptor.getValue().path("trade_intention").path("action").asText()).isEqualTo("SHORT");
        assertThat(payloadCaptor.getValue().path("trade_intention").path("review_type").asText()).isEqualTo("MANUAL_REANALYSIS");
        assertThat(payloadCaptor.getValue().path("original_alert_context").path("original_alert_time").asText()).isEqualTo("2026-03-26T02:30:39Z");
        assertThat(payloadCaptor.getValue().path("original_alert_context").path("original_alert_reason").asText()).isEqualTo("CHOCH_BEARISH");
        assertThat(payloadCaptor.getValue().path("original_alert_context").path("original_alert_price").decimalValue()).isEqualByComparingTo("24411.25");
        assertThat(payloadCaptor.getValue().path("dynamic_levels_and_vwap").path("vwap_value").decimalValue()).isEqualByComparingTo("24422.50");
        assertThat(payloadCaptor.getValue().path("momentum_and_flow_the_trigger").path("oscillator_value").decimalValue()).isEqualByComparingTo("47.20");

        verify(indicatorService).computeSnapshot(Instrument.MNQ, "10m");
        verify(indicatorService).computeSnapshot(Instrument.MNQ, "1h");
        verify(indicatorService).computeSeries(Instrument.MNQ, "10m", 500);
        verify(marketDataService).currentPrice(Instrument.MNQ);
    }

    @Test
    void reanalyzeAlert_reusesLatestProposedTradePlanWhenAvailable() throws Exception {
        MentorSignalReviewService service = new MentorSignalReviewService(
            mentorAnalysisService,
            indicatorService,
            mentorIntermarketService,
            marketDataServiceProvider,
            candleRepositoryPort,
            contractRegistry,
            reviewRepository,
            messagingTemplate,
            objectMapper
        );

        Alert alert = new Alert(
            "smc:MNQ:2026-03-26T02:30:39Z",
            AlertSeverity.INFO,
            "Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH",
            AlertCategory.SMC,
            "MNQ",
            Instant.parse("2026-03-26T02:30:39Z")
        );
        String alertKey = "2026-03-26T02:30:39Z:MNQ:SMC:Micro E-mini Nasdaq-100 [10m] — CHoCH detected: CHOCH_BEARISH";

        MentorAnalyzeResponse latestAnalysis = new MentorAnalyzeResponse(
            100L,
            "gemini-test",
            objectMapper.readTree("""
                {"trade_intention":{"review_type":"MANUAL_REANALYSIS"}}
                """),
            new MentorStructuredResponse(
                "Structure baissiere propre.",
                List.of("Live re-check"),
                List.of(),
                "Trade Validé - Discipline Respectée",
                ExecutionEligibilityStatus.ELIGIBLE,
                "Plan remains executable.",
                "Rester discipline.",
                new MentorProposedTradePlan(
                    24390.25,
                    24445.25,
                    24280.25,
                    2.00,
                    "Plan existant",
                    null
                )
            ),
            "{\"verdict\":\"Trade Validé - Discipline Respectée\"}",
            List.of()
        );

        MentorSignalReviewRecord baseReview = new MentorSignalReviewRecord();
        baseReview.setId(11L);
        baseReview.setAlertKey(alertKey);
        baseReview.setRevision(1);
        baseReview.setTriggerType("INITIAL");
        baseReview.setStatus("DONE");
        baseReview.setSeverity("INFO");
        baseReview.setCategory("SMC");
        baseReview.setMessage(alert.message());
        baseReview.setInstrument("MNQ");
        baseReview.setTimeframe("10m");
        baseReview.setAction("SHORT");
        baseReview.setAlertTimestamp(alert.timestamp());
        baseReview.setCreatedAt(alert.timestamp());
        baseReview.setSnapshotJson("""
            {
              "metadata": {
                "asset": "MNQ1!",
                "timestamp": "2026-03-26T02:30:39Z",
                "current_price": 24411.25
              },
              "market_structure_the_king": {
                "last_event": "CHOCH_BEARISH"
              },
              "trade_intention": {
                "action": "SHORT"
              }
            }
            """);

        MentorSignalReviewRecord latestReview = new MentorSignalReviewRecord();
        latestReview.setId(12L);
        latestReview.setAlertKey(alertKey);
        latestReview.setRevision(2);
        latestReview.setTriggerType("MANUAL_REANALYSIS");
        latestReview.setStatus("DONE");
        latestReview.setSeverity("INFO");
        latestReview.setCategory("SMC");
        latestReview.setMessage(alert.message());
        latestReview.setInstrument("MNQ");
        latestReview.setTimeframe("10m");
        latestReview.setAction("SHORT");
        latestReview.setAlertTimestamp(alert.timestamp());
        latestReview.setCreatedAt(Instant.parse("2026-03-26T02:40:00Z"));
        latestReview.setAnalysisJson(objectMapper.writeValueAsString(latestAnalysis));

        MentorSignalReviewRecord pendingReview = new MentorSignalReviewRecord();
        pendingReview.setId(13L);
        pendingReview.setAlertKey(alertKey);
        pendingReview.setRevision(3);
        pendingReview.setTriggerType("MANUAL_REANALYSIS");
        pendingReview.setStatus("ANALYZING");
        pendingReview.setSeverity("INFO");
        pendingReview.setCategory("SMC");
        pendingReview.setMessage(alert.message());
        pendingReview.setInstrument("MNQ");
        pendingReview.setTimeframe("10m");
        pendingReview.setAction("SHORT");
        pendingReview.setAlertTimestamp(alert.timestamp());
        pendingReview.setCreatedAt(Instant.parse("2026-03-26T02:41:00Z"));

        when(reviewRepository.findByAlertKeyOrderByRevisionAsc(alertKey)).thenReturn(List.of(baseReview, latestReview));
        when(reviewRepository.save(any())).thenAnswer(invocation -> {
            MentorSignalReviewRecord record = invocation.getArgument(0);
            if (record.getId() == null) {
                record.setId(13L);
            }
            return record;
        });
        when(reviewRepository.findById(13L)).thenReturn(Optional.of(pendingReview));
        when(marketDataServiceProvider.getIfAvailable()).thenReturn(marketDataService);
        when(marketDataService.currentPrice(Instrument.MNQ)).thenReturn(
            new MarketDataService.StoredPrice(new BigDecimal("24430.75"), Instant.parse("2026-03-26T02:41:05Z"), "LIVE_PROVIDER")
        );
        when(indicatorService.computeSnapshot(Instrument.MNQ, "10m")).thenReturn(snapshot("10m", new BigDecimal("24422.50"), new BigDecimal("47.20"), "CHOCH_BEARISH"));
        when(indicatorService.computeSnapshot(Instrument.MNQ, "1h")).thenReturn(snapshot("1h", new BigDecimal("24405.00"), new BigDecimal("44.10"), "CHOCH_BEARISH"));
        when(indicatorService.computeSeries(Instrument.MNQ, "10m", 500)).thenReturn(new IndicatorSeriesSnapshot(
            "MNQ",
            "10m",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        ));
        when(candleRepositoryPort.findRecentCandles(Instrument.MNQ, "10m", 120)).thenReturn(List.of(
            candle("2026-03-26T02:20:00Z", "24418.25", "24422.00", "24410.00", "24415.00"),
            candle("2026-03-26T02:30:00Z", "24415.00", "24428.00", "24412.00", "24424.25")
        ));
        when(mentorIntermarketService.current(Instrument.MNQ)).thenReturn(new MentorIntermarketSnapshot(
            0.35,
            "BULLISH",
            null,
            null,
            null,
            "DXY_AVAILABLE"
        ));
        when(mentorAnalysisService.analyze(any(), any())).thenReturn(latestAnalysis);

        service.reanalyzeAlert(alert);

        ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(mentorAnalysisService, timeout(1000)).analyze(payloadCaptor.capture(), startsWith(MentorAnalysisService.ALERT_SOURCE_PREFIX));
        assertThat(payloadCaptor.getValue().path("trade_intention").path("entry_price").decimalValue()).isEqualByComparingTo("24390.25");
        assertThat(payloadCaptor.getValue().path("trade_intention").path("stop_loss").decimalValue()).isEqualByComparingTo("24445.25");
        assertThat(payloadCaptor.getValue().path("trade_intention").path("take_profit").decimalValue()).isEqualByComparingTo("24280.25");
        assertThat(payloadCaptor.getValue().path("risk_and_emotional_check").path("reward_to_risk_ratio").decimalValue()).isEqualByComparingTo("2.00");
    }

    private static IndicatorSnapshot snapshot(String timeframe, BigDecimal vwap, BigDecimal rsi, String lastBreakType) {
        return new IndicatorSnapshot(
            "MNQ",
            timeframe,
            new BigDecimal("24412.00"),
            new BigDecimal("24408.00"),
            new BigDecimal("24395.00"),
            null,
            rsi,
            null,
            null,
            null,
            null,
            "MACD Bearish Cross",
            null,
            false,
            vwap,
            null,
            null,
            null,
            new BigDecimal("-0.12"),
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            new BigDecimal("0.42"),
            "BEARISH",
            null,
            null,
            null,
            "WT_BEARISH",
            "OVERBOUGHT",
            // SMC: Internal structure
            "BEARISH",                          // internalBias
            null,                               // internalHigh
            null,                               // internalLow
            null,                               // internalHighTime
            null,                               // internalLowTime
            lastBreakType,                      // lastInternalBreakType
            // SMC: Swing structure
            null,                               // swingBias
            null,                               // swingHigh
            null,                               // swingLow
            null,                               // swingHighTime
            null,                               // swingLowTime
            null,                               // lastSwingBreakType
            // SMC: UC-SMC-008 confluence filter state
            false,
            // SMC: Legacy / derived
            "BEARISH",
            new BigDecimal("24520.00"),
            new BigDecimal("24320.00"),
            new BigDecimal("24480.00"),
            new BigDecimal("24380.00"),
            lastBreakType,
            null,
            null,
            null,
            null,
            // SMC: Liquidity (EQH / EQL)
            List.of(), List.of(),
            // SMC: Premium / Discount / Equilibrium
            null, null, null, null,
            // SMC: Zones
            List.of(
                new IndicatorSnapshot.OrderBlockView("BEARISH", "ACTIVE", new BigDecimal("24460.00"), new BigDecimal("24435.00"), new BigDecimal("24447.50"), 1L, "BEARISH", null),
                new IndicatorSnapshot.OrderBlockView("BULLISH", "ACTIVE", new BigDecimal("24405.00"), new BigDecimal("24380.00"), new BigDecimal("24392.50"), 2L, "BULLISH", null)
            ),
            List.of(),
            List.of(),   // recentOrderBlockEvents
            List.of(),
            List.of(new IndicatorSnapshot.StructureBreakView("CHOCH", "BEARISH", new BigDecimal("24412.00"), 1L, "INTERNAL")),
            null,   // mtfLevels (UC-SMC-005)
            null
        );
    }

    private static Candle candle(String timestamp, String open, String high, String low, String close) {
        return new Candle(
            Instrument.MNQ,
            "10m",
            Instant.parse(timestamp),
            new BigDecimal(open),
            new BigDecimal(high),
            new BigDecimal(low),
            new BigDecimal(close),
            100L
        );
    }
}

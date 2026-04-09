package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeSimulationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TradeSimulationServiceTest {

    @Mock
    private MentorSignalReviewRepositoryPort reviewRepository;

    @Mock
    private CandleRepositoryPort candleRepositoryPort;

    @Mock
    private MentorAuditRepositoryPort auditRepository;

    @Mock
    private ObjectProvider<MentorSignalReviewService> reviewServiceProvider;

    @Mock
    private ObjectProvider<SimpMessagingTemplate> messagingProvider;

    private TradeSimulationService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new TradeSimulationService(
            reviewRepository,
            auditRepository,
            candleRepositoryPort,
            objectMapper,
            reviewServiceProvider,
            messagingProvider
        );
    }

    @Test
    void evaluateTradeOutcome_longHitsTargetAfterEntry_returnsWin() throws Exception {
        MentorSignalReviewRecord review = review(
            "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            new MentorProposedTradePlan(100.0, 98.5, 102.0, 1.33, "Test setup", null, null)
        );

        List<Candle> candles = List.of(
            candle("2026-03-26T03:11:00Z", "100.80", "101.25", "99.75", "100.90"),
            candle("2026-03-26T03:12:00Z", "100.95", "102.10", "100.70", "101.95")
        );

        TradeSimulationService.SimulationResult result = service.evaluateTradeOutcome(review, candles);

        assertThat(result.status()).isEqualTo(TradeSimulationStatus.WIN);
        assertThat(result.activationTime()).isEqualTo(Instant.parse("2026-03-26T03:11:00Z"));
        assertThat(result.resolutionTime()).isEqualTo(Instant.parse("2026-03-26T03:12:00Z"));
        assertThat(result.maxDrawdownPoints()).isEqualByComparingTo("0.250000");
    }

    @Test
    void evaluateTradeOutcome_longTargetBeforeEntry_returnsMissed() throws Exception {
        MentorSignalReviewRecord review = review(
            "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            new MentorProposedTradePlan(100.0, 98.5, 102.0, 1.33, "Test setup", null, null)
        );

        List<Candle> candles = List.of(
            candle("2026-03-26T03:11:00Z", "101.00", "102.20", "100.40", "101.80")
        );

        TradeSimulationService.SimulationResult result = service.evaluateTradeOutcome(review, candles);

        assertThat(result.status()).isEqualTo(TradeSimulationStatus.MISSED);
        assertThat(result.activationTime()).isNull();
        assertThat(result.resolutionTime()).isEqualTo(Instant.parse("2026-03-26T03:11:00Z"));
    }

    @Test
    void evaluateTradeOutcome_sameCandleTouchesStopAndTarget_returnsLossPessimistically() throws Exception {
        MentorSignalReviewRecord review = review(
            "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            new MentorProposedTradePlan(100.0, 98.5, 102.0, 1.33, "Test setup", null, null)
        );

        List<Candle> candles = List.of(
            candle("2026-03-26T03:11:00Z", "100.40", "102.25", "98.40", "101.10")
        );

        TradeSimulationService.SimulationResult result = service.evaluateTradeOutcome(review, candles);

        assertThat(result.status()).isEqualTo(TradeSimulationStatus.LOSS);
        assertThat(result.activationTime()).isEqualTo(Instant.parse("2026-03-26T03:11:00Z"));
        assertThat(result.resolutionTime()).isEqualTo(Instant.parse("2026-03-26T03:11:00Z"));
        assertThat(result.maxDrawdownPoints()).isEqualByComparingTo("1.600000");
    }

    private MentorSignalReviewRecord review(String action,
                                            TradeSimulationStatus simulationStatus,
                                            MentorProposedTradePlan plan) throws Exception {
        MentorSignalReviewRecord review = new MentorSignalReviewRecord();
        review.setId(77L);
        review.setAction(action);
        review.setInstrument("MNQ");
        review.setTimeframe("10m");
        review.setCreatedAt(Instant.parse("2026-03-26T03:10:30Z"));
        review.setSimulationStatus(simulationStatus);
        review.setAnalysisJson(objectMapper.writeValueAsString(new MentorAnalyzeResponse(
            1L,
            "gemini-test",
            objectMapper.readTree("{\"metadata\":{\"asset\":\"MNQ1!\"}}"),
            new MentorStructuredResponse(
                "Technical analysis",
                List.of(),
                List.of(),
                "Trade Validé - Discipline Respectée",
                ExecutionEligibilityStatus.ELIGIBLE,
                "Simulation plan is executable.",
                "Stay patient",
                plan
            ),
            "{\"ok\":true}",
            List.of()
        )));
        return review;
    }

    private Candle candle(String timestamp, String open, String high, String low, String close) {
        return new Candle(
            Instrument.MNQ,
            "1m",
            Instant.parse(timestamp),
            new BigDecimal(open),
            new BigDecimal(high),
            new BigDecimal(low),
            new BigDecimal(close),
            100L
        );
    }
}

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
import com.riskdesk.domain.model.TrailingStopResult;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import com.riskdesk.infrastructure.config.TrailingStopProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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

    @Mock
    private TradeSimulationRepositoryPort simulationRepository;

    private TradeSimulationService service;
    private ObjectMapper objectMapper;
    private TrailingStopProperties trailingStopProperties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        trailingStopProperties = new TrailingStopProperties();
        trailingStopProperties.setEnabled(true);
        trailingStopProperties.setMultiplier(1.0);
        trailingStopProperties.setActivationThreshold(0.5);
        service = new TradeSimulationService(
            reviewRepository,
            auditRepository,
            candleRepositoryPort,
            objectMapper,
            reviewServiceProvider,
            messagingProvider,
            trailingStopProperties,
            simulationRepository
        );
    }

    // ── Fixed SL/TP tests (unchanged behavior) ─────────────────────────────

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

    // ── Trailing stop tests ─────────────────────────────────────────────────

    @Test
    void trailingStop_longGoesInProfitThenRetraces_capturesProfit() throws Exception {
        // Entry=100, SL=98, TP=103 → risk=2, activation threshold=0.5R=1pt
        // ATR will be ~0.5 from the pre-activation candles
        // Price goes to 102 (MFE) then retraces → trail at 102-0.5=101.5 → TRAILING_WIN
        MentorSignalReviewRecord review = review(
            "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            new MentorProposedTradePlan(100.0, 98.0, 103.0, 2.5, "Test", null, null)
        );

        List<Candle> candles = buildCandlesForTrailingTest_longWin();

        TradeSimulationService.SimulationResult result = service.evaluateTradeOutcome(review, candles);

        // Fixed result: TP never hit, SL hit → LOSS
        // But trailing should capture profit
        assertThat(result.trailingStopResult()).isEqualTo(TrailingStopResult.TRAILING_WIN);
        assertThat(result.trailingExitPrice()).isNotNull();
        assertThat(result.trailingExitPrice().doubleValue()).isGreaterThan(100.0);
        assertThat(result.bestFavorablePrice()).isNotNull();
        assertThat(result.bestFavorablePrice().doubleValue()).isGreaterThanOrEqualTo(102.0);
    }

    @Test
    void trailingStop_shortDescendsThenBounces_capturesMove() throws Exception {
        // Entry=100, SL=102, TP=97 → risk=2, activation threshold=0.5R=1pt
        // Price drops to 98 (MFE) then bounces → trail at 98+ATR → TRAILING_WIN
        MentorSignalReviewRecord review = review(
            "SHORT",
            TradeSimulationStatus.PENDING_ENTRY,
            new MentorProposedTradePlan(100.0, 102.0, 97.0, 1.5, "Test", null, null)
        );

        List<Candle> candles = buildCandlesForTrailingTest_shortWin();

        TradeSimulationService.SimulationResult result = service.evaluateTradeOutcome(review, candles);

        assertThat(result.trailingStopResult()).isEqualTo(TrailingStopResult.TRAILING_WIN);
        assertThat(result.trailingExitPrice()).isNotNull();
        assertThat(result.trailingExitPrice().doubleValue()).isLessThan(100.0);
        assertThat(result.bestFavorablePrice()).isNotNull();
    }

    @Test
    void trailingStop_tradeNeverInProfit_doesNotWorsenFixedSL() throws Exception {
        // Entry=100, SL=98, TP=103 → price immediately goes to SL
        // Trailing should not activate and result should be TRAILING_LOSS (mirrors fixed)
        MentorSignalReviewRecord review = review(
            "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            new MentorProposedTradePlan(100.0, 98.0, 103.0, 2.5, "Test", null, null)
        );

        List<Candle> candles = buildCandlesForTrailingTest_neverInProfit();

        TradeSimulationService.SimulationResult result = service.evaluateTradeOutcome(review, candles);

        assertThat(result.status()).isEqualTo(TradeSimulationStatus.LOSS);
        // Trailing should either be null (not enough data for ATR) or not activated
        if (result.trailingStopResult() != null) {
            assertThat(result.trailingStopResult()).isNotEqualTo(TrailingStopResult.TRAILING_WIN);
        }
    }

    @Test
    void trailingStop_belowActivationThreshold_notEngaged() throws Exception {
        // Entry=100, SL=98, TP=103 → activation threshold=0.5R=1pt
        // Price goes to 100.5 (below threshold) then retraces → trailing not engaged
        MentorSignalReviewRecord review = review(
            "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            new MentorProposedTradePlan(100.0, 98.0, 103.0, 2.5, "Test", null, null)
        );

        List<Candle> candles = buildCandlesForTrailingTest_belowThreshold();

        TradeSimulationService.SimulationResult result = service.evaluateTradeOutcome(review, candles);

        // Trailing should not have resolved since the move was below threshold
        assertThat(result.trailingStopResult()).isNull();
    }

    @Test
    void trailingStop_disabled_returnsNullTrailingFields() throws Exception {
        trailingStopProperties.setEnabled(false);

        MentorSignalReviewRecord review = review(
            "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            new MentorProposedTradePlan(100.0, 98.5, 102.0, 1.33, "Test", null, null)
        );

        List<Candle> candles = List.of(
            candle("2026-03-26T03:11:00Z", "100.80", "101.25", "99.75", "100.90"),
            candle("2026-03-26T03:12:00Z", "100.95", "102.10", "100.70", "101.95")
        );

        TradeSimulationService.SimulationResult result = service.evaluateTradeOutcome(review, candles);

        assertThat(result.status()).isEqualTo(TradeSimulationStatus.WIN);
        assertThat(result.trailingStopResult()).isNull();
        assertThat(result.trailingExitPrice()).isNull();
    }

    // ── Candle builders for trailing tests ───────────────────────────────────

    /**
     * LONG scenario: 20 pre-activation candles (for ATR), then entry hit,
     * price goes to 102 (MFE), then retraces to hit trailing SL.
     */
    private List<Candle> buildCandlesForTrailingTest_longWin() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2026-04-08T14:00:00Z");

        // 20 candles before entry — steady range around 100.5, ATR ~0.5
        for (int i = 0; i < 20; i++) {
            Instant t = base.plusSeconds(i * 300L); // 5m intervals
            candles.add(new Candle(Instrument.MNQ, "5m", t,
                    bd("100.30"), bd("100.70"), bd("100.10"), bd("100.50"), 50L));
        }

        // Entry candle: low touches 100 (entry), tight range so trailing doesn't fire
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(20 * 300L),
                bd("100.10"), bd("100.20"), bd("99.90"), bd("100.10"), 60L));

        // Gradual rise — each candle's low stays above trailing SL level
        // ATR ~0.5, so trail from 101 = 100.5, from 101.5 = 101.0, from 102 = 101.5
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(21 * 300L),
                bd("100.20"), bd("101.10"), bd("100.60"), bd("101.00"), 70L));
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(22 * 300L),
                bd("101.00"), bd("101.60"), bd("101.10"), bd("101.50"), 80L));
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(23 * 300L),
                bd("101.50"), bd("102.10"), bd("101.60"), bd("102.00"), 90L));

        // MFE = 102.10, trailingSL = 102.10 - 0.5 = 101.60
        // Retrace: low dips below trailing SL → trailing exit
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(24 * 300L),
                bd("101.80"), bd("101.90"), bd("101.50"), bd("101.60"), 60L));

        // Further drop to hit fixed SL
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(25 * 300L),
                bd("101.30"), bd("101.40"), bd("97.50"), bd("97.80"), 100L));

        return candles;
    }

    /**
     * SHORT scenario: 20 pre-activation candles, then entry hit,
     * price drops to 98 (MFE), then bounces to hit trailing SL.
     */
    private List<Candle> buildCandlesForTrailingTest_shortWin() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2026-04-08T14:00:00Z");

        // 20 candles before entry — steady range around 99.5
        for (int i = 0; i < 20; i++) {
            Instant t = base.plusSeconds(i * 300L);
            candles.add(new Candle(Instrument.MNQ, "5m", t,
                    bd("99.30"), bd("99.70"), bd("99.10"), bd("99.50"), 50L));
        }

        // Entry candle: high touches 100 (entry for short)
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(20 * 300L),
                bd("99.80"), bd("100.10"), bd("99.60"), bd("99.70"), 60L));

        // Price drops: 99.0, 98.5, 98.0 (MFE)
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(21 * 300L),
                bd("99.60"), bd("99.70"), bd("99.00"), bd("99.10"), 70L));
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(22 * 300L),
                bd("99.00"), bd("99.10"), bd("98.40"), bd("98.50"), 80L));
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(23 * 300L),
                bd("98.50"), bd("98.60"), bd("97.90"), bd("98.00"), 90L));

        // Bounce: 98.5, 99.0 — should hit trailing SL
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(24 * 300L),
                bd("98.10"), bd("98.70"), bd("98.00"), bd("98.60"), 60L));
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(25 * 300L),
                bd("98.70"), bd("99.20"), bd("98.60"), bd("99.10"), 50L));

        // Hard bounce through SL
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(26 * 300L),
                bd("99.20"), bd("102.50"), bd("99.10"), bd("102.20"), 100L));

        return candles;
    }

    /**
     * LONG that never goes in profit — price drops immediately to SL.
     */
    private List<Candle> buildCandlesForTrailingTest_neverInProfit() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2026-04-08T14:00:00Z");

        // Just a few candles — entry then immediate SL
        for (int i = 0; i < 5; i++) {
            Instant t = base.plusSeconds(i * 300L);
            candles.add(new Candle(Instrument.MNQ, "5m", t,
                    bd("100.30"), bd("100.60"), bd("100.10"), bd("100.40"), 50L));
        }

        // Entry candle: low = 100 (entry)
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(5 * 300L),
                bd("100.20"), bd("100.30"), bd("99.90"), bd("100.10"), 60L));

        // Immediate drop to SL at 98
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(6 * 300L),
                bd("100.00"), bd("100.10"), bd("97.50"), bd("97.80"), 100L));

        return candles;
    }

    /**
     * LONG where price moves slightly in favor but below activation threshold.
     */
    private List<Candle> buildCandlesForTrailingTest_belowThreshold() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2026-04-08T14:00:00Z");

        // Pre-activation candles
        for (int i = 0; i < 20; i++) {
            Instant t = base.plusSeconds(i * 300L);
            candles.add(new Candle(Instrument.MNQ, "5m", t,
                    bd("100.30"), bd("100.60"), bd("100.10"), bd("100.40"), 50L));
        }

        // Entry candle: low = 100
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(20 * 300L),
                bd("100.20"), bd("100.30"), bd("99.90"), bd("100.10"), 60L));

        // Small rise to 100.5 (below 1pt threshold for activation)
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(21 * 300L),
                bd("100.10"), bd("100.50"), bd("100.00"), bd("100.40"), 50L));

        // Stays in range — no resolution
        candles.add(new Candle(Instrument.MNQ, "5m", base.plusSeconds(22 * 300L),
                bd("100.30"), bd("100.60"), bd("100.10"), bd("100.40"), 50L));

        return candles;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}

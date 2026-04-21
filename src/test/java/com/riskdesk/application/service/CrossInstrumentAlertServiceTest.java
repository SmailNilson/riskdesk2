package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.correlation.CorrelationState;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.marketdata.event.MarketPriceUpdated;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CrossInstrumentAlertService — ONIMS orchestration")
class CrossInstrumentAlertServiceTest {

    @Mock private CandleRepositoryPort candlePort;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private CrossInstrumentAlertService service;

    private static final ZoneId ET = ZoneId.of("America/New_York");

    @BeforeEach
    void setUp() {
        service = new CrossInstrumentAlertService(candlePort, messagingTemplate);
    }

    private Instant nyTime(int hour, int minute) {
        return ZonedDateTime.of(LocalDate.of(2026, 4, 10), LocalTime.of(hour, minute), ET).toInstant();
    }

    private List<Candle> mclCandlesWithBreakout() {
        List<Candle> candles = new ArrayList<>();
        Instant base = nyTime(10, 0);
        for (int i = 0; i < 10; i++) {
            BigDecimal close = new BigDecimal("110.00");
            candles.add(new Candle(Instrument.MCL, "5m",
                    base.plusSeconds(i * 300L),
                    new BigDecimal("109.50"), new BigDecimal("110.50"),
                    new BigDecimal("109.00"), close, 500));
        }
        candles.add(new Candle(Instrument.MCL, "5m",
                base.plusSeconds(10 * 300L),
                new BigDecimal("110.50"), new BigDecimal("112.00"),
                new BigDecimal("110.00"), new BigDecimal("111.50"), 900));
        return candles;
    }

    private List<Candle> mnqCandlesWithVwapRejection(BigDecimal vwapApprox) {
        List<Candle> candles = new ArrayList<>();
        Instant base = nyTime(10, 0);
        for (int i = 0; i < 24; i++) {
            candles.add(new Candle(Instrument.MNQ, "5m",
                    base.plusSeconds(i * 300L),
                    vwapApprox.subtract(new BigDecimal("10")),
                    vwapApprox.add(new BigDecimal("20")),
                    vwapApprox.subtract(new BigDecimal("30")),
                    vwapApprox,
                    500));
        }
        candles.add(new Candle(Instrument.MNQ, "5m",
                base.plusSeconds(24 * 300L),
                vwapApprox.subtract(new BigDecimal("10")),
                vwapApprox.add(new BigDecimal("5")),
                vwapApprox.subtract(new BigDecimal("50")),
                vwapApprox.subtract(new BigDecimal("30")),
                800));
        return candles;
    }

    @Nested
    @DisplayName("Event filtering")
    class EventFiltering {

        @Test
        void ignoresNon5mTimeframes() {
            CandleClosed event = new CandleClosed("MCL", "1h", nyTime(10, 30));
            service.onCandleClosed(event);
            verifyNoInteractions(candlePort);
        }

        @Test
        void ignoresUnrelatedInstruments() {
            CandleClosed event = new CandleClosed("MGC", "5m", nyTime(10, 30));
            service.onCandleClosed(event);
            verifyNoInteractions(candlePort);
        }

        @Test
        void cachesVixPriceFromMarketEvent() {
            assertThat(service.getCachedVixPrice()).isNull();

            service.onMarketPriceUpdated(
                    new MarketPriceUpdated("VIX", new BigDecimal("22.5"), Instant.now()));

            assertThat(service.getCachedVixPrice()).isEqualByComparingTo("22.5");
        }

        @Test
        void ignoresNonVixPriceUpdates() {
            service.onMarketPriceUpdated(
                    new MarketPriceUpdated("MCL", new BigDecimal("110.0"), Instant.now()));
            assertThat(service.getCachedVixPrice()).isNull();
        }
    }

    @Nested
    @DisplayName("Session filter")
    class SessionFilter {

        @Test
        void mclBreakoutAcceptedDuringAmSession() {
            when(candlePort.findRecentCandles(eq(Instrument.MCL), eq("5m"), anyInt()))
                    .thenReturn(mclCandlesWithBreakout());

            CandleClosed event = new CandleClosed("MCL", "5m", nyTime(10, 30));
            service.onCandleClosed(event);

            verify(candlePort).findRecentCandles(eq(Instrument.MCL), eq("5m"), anyInt());
        }

        @Test
        void mclBreakoutRejectedOutsideSession() {
            CandleClosed event = new CandleClosed("MCL", "5m", nyTime(12, 0));
            service.onCandleClosed(event);
            verifyNoInteractions(candlePort);
        }

        @Test
        void mclBreakoutAcceptedDuringPmSession() {
            when(candlePort.findRecentCandles(eq(Instrument.MCL), eq("5m"), anyInt()))
                    .thenReturn(mclCandlesWithBreakout());

            CandleClosed event = new CandleClosed("MCL", "5m", nyTime(14, 0));
            service.onCandleClosed(event);

            verify(candlePort).findRecentCandles(eq(Instrument.MCL), eq("5m"), anyInt());
        }
    }

    @Nested
    @DisplayName("VIX regime filter")
    class VixFilter {

        @Test
        void failsOpenWhenNoVixDataReceived() {
            when(candlePort.findRecentCandles(eq(Instrument.MCL), eq("5m"), anyInt()))
                    .thenReturn(mclCandlesWithBreakout());

            CandleClosed event = new CandleClosed("MCL", "5m", nyTime(10, 30));
            service.onCandleClosed(event);

            verify(candlePort).findRecentCandles(eq(Instrument.MCL), eq("5m"), anyInt());
        }

        @Test
        void blockedWhenVixBelowThreshold() {
            service.onMarketPriceUpdated(
                    new MarketPriceUpdated("VIX", new BigDecimal("15.0"), Instant.now()));

            CandleClosed event = new CandleClosed("MCL", "5m", nyTime(10, 30));
            service.onCandleClosed(event);

            verifyNoInteractions(candlePort);
        }

        @Test
        void passesWhenVixAboveThreshold() {
            service.onMarketPriceUpdated(
                    new MarketPriceUpdated("VIX", new BigDecimal("25.0"), Instant.now()));

            when(candlePort.findRecentCandles(eq(Instrument.MCL), eq("5m"), anyInt()))
                    .thenReturn(mclCandlesWithBreakout());

            CandleClosed event = new CandleClosed("MCL", "5m", nyTime(10, 30));
            service.onCandleClosed(event);

            verify(candlePort).findRecentCandles(eq(Instrument.MCL), eq("5m"), anyInt());
        }
    }

    @Nested
    @DisplayName("Blackout filter")
    class BlackoutFilter {

        @Test
        void blackoutSuppressesMclEvaluation() {
            service.activateBlackout();

            CandleClosed event = new CandleClosed("MCL", "5m", Instant.now());
            service.onCandleClosed(event);

            verifyNoInteractions(candlePort);
        }
    }

    @Nested
    @DisplayName("Engine state management")
    class StateManagement {

        @Test
        void startsInIdleState() {
            assertThat(service.currentState()).isEqualTo(CorrelationState.IDLE);
        }

        @Test
        void resetClearsStateAndHistory() {
            service.reset();
            assertThat(service.currentState()).isEqualTo(CorrelationState.IDLE);
            assertThat(service.getSignalHistory()).isEmpty();
        }

        @Test
        void emptyCandleListDoesNotCrash() {
            when(candlePort.findRecentCandles(eq(Instrument.MCL), eq("5m"), anyInt()))
                    .thenReturn(Collections.emptyList());

            CandleClosed event = new CandleClosed("MCL", "5m", nyTime(10, 30));
            service.onCandleClosed(event);

            assertThat(service.currentState()).isEqualTo(CorrelationState.IDLE);
        }
    }

    @Nested
    @DisplayName("Configuration API")
    class Configuration {

        @Test
        void vixThresholdDefaultIs20() {
            assertThat(service.getVixThreshold()).isEqualTo(20.0);
        }

        @Test
        void vixThresholdIsConfigurable() {
            service.setVixThreshold(25.0);
            assertThat(service.getVixThreshold()).isEqualTo(25.0);
        }

        @Test
        void blackoutDurationIsConfigurable() {
            service.setBlackoutDurationMinutes(30);
            assertThat(service.getBlackoutDurationMinutes()).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("Error resilience")
    class ErrorResilience {

        @Test
        void candlePortExceptionDoesNotPropagate() {
            when(candlePort.findRecentCandles(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("DB connection lost"));

            CandleClosed event = new CandleClosed("MCL", "5m", nyTime(10, 30));
            service.onCandleClosed(event);

            assertThat(service.currentState()).isEqualTo(CorrelationState.IDLE);
        }
    }
}

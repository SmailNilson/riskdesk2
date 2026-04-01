package com.riskdesk.application.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.domain.alert.service.AlertDeduplicator;
import com.riskdesk.domain.alert.service.IndicatorAlertEvaluator;
import com.riskdesk.domain.alert.service.RiskAlertEvaluator;
import com.riskdesk.domain.alert.service.SignalPreFilterService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Money;
import com.riskdesk.domain.trading.aggregate.Portfolio;
import com.riskdesk.application.dto.IndicatorSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private PositionService positionService;

    @Mock
    private IndicatorService indicatorService;

    @Mock
    private RiskAlertEvaluator riskAlertEvaluator;

    @Mock
    private IndicatorAlertEvaluator indicatorAlertEvaluator;

    @Mock
    private SignalPreFilterService signalPreFilterService;

    @Mock
    private MentorSignalReviewService mentorSignalReviewService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    void tenMinuteAlertCanRefireAfterSharedDedupCooldownExpires() throws Exception {
        assertAlertCanRefireAfterSharedCooldown("macd:bullish:MCL:10m");
    }

    @Test
    void hourlyAlertCanRefireAfterSharedDedupCooldownExpires() throws Exception {
        assertAlertCanRefireAfterSharedCooldown("macd:bullish:MCL:1h");
    }

    @Test
    void orderBlockAlertsTriggerMentorReviewCapture() {
        Alert orderBlockAlert = new Alert(
            "ob:mitigated:MNQ:10m",
            AlertSeverity.INFO,
            "MNQ [10m] Bearish order block mitigated",
            AlertCategory.ORDER_BLOCK,
            "MNQ"
        );

        AlertService service = reviewAwareService();

        when(indicatorAlertEvaluator.evaluate(eq(Instrument.MNQ), eq("10m"), any()))
            .thenReturn(List.of(orderBlockAlert));
        when(indicatorAlertEvaluator.evaluate(eq(Instrument.MNQ), eq("1h"), any()))
            .thenReturn(List.of());
        when(signalPreFilterService.filter(anyList(), eq("10m"), anyString()))
            .thenReturn(List.of(orderBlockAlert));
        when(signalPreFilterService.filter(anyList(), eq("1h"), anyString()))
            .thenReturn(List.of());

        service.evaluate(Instrument.MNQ);

        ArgumentCaptor<List<Alert>> alertsCaptor = ArgumentCaptor.forClass(List.class);
        verify(mentorSignalReviewService).captureGroupReview(alertsCaptor.capture(), any());
        assertEquals(List.of(orderBlockAlert), alertsCaptor.getValue());
    }

    @Test
    void publishedIndicatorAlertsShareTheSameMentorReviewCaptureBatch() {
        Alert orderBlockAlert = new Alert(
            "ob:mitigated:MNQ:10m",
            AlertSeverity.INFO,
            "MNQ [10m] Bearish order block mitigated",
            AlertCategory.ORDER_BLOCK,
            "MNQ"
        );
        Alert macdAlert = new Alert(
            "macd:bearish:MNQ:10m",
            AlertSeverity.INFO,
            "MNQ [10m] MACD Bearish Cross",
            AlertCategory.MACD,
            "MNQ"
        );

        AlertService service = reviewAwareService();

        when(indicatorAlertEvaluator.evaluate(eq(Instrument.MNQ), eq("10m"), any()))
            .thenReturn(List.of(orderBlockAlert, macdAlert));
        when(indicatorAlertEvaluator.evaluate(eq(Instrument.MNQ), eq("1h"), any()))
            .thenReturn(List.of());
        when(signalPreFilterService.filter(anyList(), eq("10m"), anyString()))
            .thenReturn(List.of(orderBlockAlert, macdAlert));
        when(signalPreFilterService.filter(anyList(), eq("1h"), anyString()))
            .thenReturn(List.of());

        service.evaluate(Instrument.MNQ);

        ArgumentCaptor<List<Alert>> alertsCaptor = ArgumentCaptor.forClass(List.class);
        verify(mentorSignalReviewService).captureGroupReview(alertsCaptor.capture(), any());
        assertEquals(List.of(orderBlockAlert, macdAlert), alertsCaptor.getValue());
    }

    private void assertAlertCanRefireAfterSharedCooldown(String key) throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        MessageChannel channel = new CountingMessageChannel(sentMessages);
        AlertDeduplicator deduplicator = new AlertDeduplicator(1);
        AlertService service = new AlertService(
            null,
            null,
            null,
            null,
            deduplicator,
            null,
            null,
            new SimpMessagingTemplate(channel)
        );

        Method publishWithoutMentor = AlertService.class.getDeclaredMethod("publishAlertWithoutMentor", Alert.class);
        publishWithoutMentor.setAccessible(true);

        Alert alert = new Alert(key, AlertSeverity.INFO, "test message", AlertCategory.MACD, "MCL");

        assertTrue((Boolean) publishWithoutMentor.invoke(service, alert));
        assertFalse((Boolean) publishWithoutMentor.invoke(service, alert));

        Thread.sleep(1100);

        assertTrue((Boolean) publishWithoutMentor.invoke(service, alert));
        assertEquals(2, sentMessages.get());
    }

    private AlertService reviewAwareService() {
        when(positionService.getPortfolio()).thenReturn(new Portfolio(Money.ZERO, List.of()));
        when(riskAlertEvaluator.evaluate(any())).thenReturn(List.of());
        when(indicatorService.computeSnapshot(eq(Instrument.MNQ), eq("10m"))).thenReturn(snapshot("10m"));
        when(indicatorService.computeSnapshot(eq(Instrument.MNQ), eq("1h"))).thenReturn(snapshot("1h"));

        AlertDeduplicator deduplicator = new AlertDeduplicator(60);
        return new AlertService(
            positionService,
            indicatorService,
            riskAlertEvaluator,
            indicatorAlertEvaluator,
            deduplicator,
            signalPreFilterService,
            mentorSignalReviewService,
            messagingTemplate
        );
    }

    private static IndicatorSnapshot snapshot(String timeframe) {
        return new IndicatorSnapshot(
            "MNQ",
            timeframe,
            new BigDecimal("24412.00"),
            new BigDecimal("24408.00"),
            new BigDecimal("24395.00"),
            null,
            new BigDecimal("47.20"),
            null,
            null,
            null,
            null,
            "MACD Bearish Cross",
            null,
            false,
            new BigDecimal("24422.50"),
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
            "BEARISH",
            null,
            null,
            null,
            null,
            "CHOCH_BEARISH",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            "BEARISH",
            new BigDecimal("24520.00"),
            new BigDecimal("24320.00"),
            new BigDecimal("24480.00"),
            new BigDecimal("24380.00"),
            "CHOCH_BEARISH",
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            // V2 alert wiring fields
            null, null, List.of(), List.of(), null
        );
    }

    private record CountingMessageChannel(AtomicInteger sentMessages) implements MessageChannel {
        @Override
        public boolean send(Message<?> message) {
            sentMessages.incrementAndGet();
            return true;
        }

        @Override
        public boolean send(Message<?> message, long timeout) {
            return send(message);
        }
    }
}

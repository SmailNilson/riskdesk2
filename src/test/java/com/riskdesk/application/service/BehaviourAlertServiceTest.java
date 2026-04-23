package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertCategory;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;
import com.riskdesk.domain.behaviouralert.service.BehaviourAlertEvaluator;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BehaviourAlertServiceTest {

    @Mock private IndicatorService       indicatorService;
    @Mock private BehaviourAlertEvaluator evaluator;
    @Mock private SimpMessagingTemplate  messagingTemplate;
    @Mock private MentorSignalReviewService mentorSignalReviewService;

    private BehaviourAlertService service;

    @BeforeEach
    void setUp() {
        service = new BehaviourAlertService(indicatorService, evaluator, messagingTemplate, mentorSignalReviewService);
    }

    @Test
    void evaluate_publishesSignalToWebSocket() {
        IndicatorSnapshot snap = emptySnapshot();
        when(indicatorService.computeSnapshot(eq(Instrument.MCL), anyString())).thenReturn(snap);

        BehaviourAlertSignal signal = new BehaviourAlertSignal(
                "ema50:proximity:MCL:10m",
                BehaviourAlertCategory.EMA_PROXIMITY,
                "MCL [10m] — Price approaching EMA50",
                "MCL",
                Instant.now()
        );
        // Fire signal on first timeframe (10m), nothing on second (1h)
        when(evaluator.evaluate(any(BehaviourAlertContext.class)))
                .thenReturn(List.of(signal))
                .thenReturn(Collections.emptyList());

        service.evaluate(Instrument.MCL);

        verify(messagingTemplate, times(1))
                .convertAndSend(eq("/topic/alerts"), (Object) argThat(p -> {
                    if (!(p instanceof Map<?, ?> m)) return false;
                    return "WARNING".equals(m.get("severity"))
                        && "EMA_PROXIMITY".equals(m.get("category"))
                        && "MCL".equals(m.get("instrument"));
                }));
    }

    @Test
    void evaluate_deduplicationSuppressesRepeatWithinCooldown() {
        IndicatorSnapshot snap = emptySnapshot();
        when(indicatorService.computeSnapshot(eq(Instrument.MCL), anyString())).thenReturn(snap);

        BehaviourAlertSignal signal = new BehaviourAlertSignal(
                "ema50:proximity:MCL:10m",
                BehaviourAlertCategory.EMA_PROXIMITY,
                "MCL [10m] — Price approaching EMA50",
                "MCL",
                Instant.now()
        );
        when(evaluator.evaluate(any(BehaviourAlertContext.class)))
                .thenReturn(List.of(signal));

        service.evaluate(Instrument.MCL); // first call — should publish
        service.evaluate(Instrument.MCL); // second call within cooldown — should NOT publish

        // Each evaluate() call processes 2 timeframes. The signal fires on each timeframe call.
        // But deduplication uses the same key → only 1 publish total (first key encounter).
        verify(messagingTemplate, times(1))
                .convertAndSend(eq("/topic/alerts"), (Object) any());
    }

    @Test
    void evaluate_capturesMentorReviewOnPublishedSignal() {
        IndicatorSnapshot snap = emptySnapshot();
        when(indicatorService.computeSnapshot(eq(Instrument.MCL), anyString())).thenReturn(snap);

        BehaviourAlertSignal signal = new BehaviourAlertSignal(
                "sr:touch:MCL:1h",
                BehaviourAlertCategory.SUPPORT_RESISTANCE,
                "MCL [1h] — Price touching strong high",
                "MCL",
                Instant.now()
        );
        when(evaluator.evaluate(any(BehaviourAlertContext.class)))
                .thenReturn(List.of(signal))
                .thenReturn(Collections.emptyList());

        service.evaluate(Instrument.MCL);

        verify(mentorSignalReviewService).captureBehaviourReview(eq(signal), anyString(), eq(snap));
    }

    @Test
    void evaluate_mentorCaptureFailureDoesNotPreventWebSocketPublish() {
        IndicatorSnapshot snap = emptySnapshot();
        when(indicatorService.computeSnapshot(eq(Instrument.MGC), anyString())).thenReturn(snap);

        BehaviourAlertSignal signal = new BehaviourAlertSignal(
                "cmf:extreme:MGC:10m",
                BehaviourAlertCategory.CHAIKIN_BEHAVIOUR,
                "MGC [10m] — CMF extreme zone",
                "MGC",
                Instant.now()
        );
        when(evaluator.evaluate(any(BehaviourAlertContext.class)))
                .thenReturn(List.of(signal))
                .thenReturn(Collections.emptyList());

        doThrow(new RuntimeException("Gemini API timeout"))
                .when(mentorSignalReviewService).captureBehaviourReview(any(), anyString(), any());

        service.evaluate(Instrument.MGC);

        // WebSocket publish should still have happened before mentor capture
        verify(messagingTemplate, times(1))
                .convertAndSend(eq("/topic/alerts"), (Object) any());
    }

    @Test
    void evaluate_indicatorServiceFailureDoesNotPropagateException() {
        when(indicatorService.computeSnapshot(eq(Instrument.E6), eq("10m")))
                .thenThrow(new RuntimeException("DB connection lost"));
        when(indicatorService.computeSnapshot(eq(Instrument.E6), eq("1h")))
                .thenReturn(emptySnapshot());
        when(evaluator.evaluate(any(BehaviourAlertContext.class)))
                .thenReturn(Collections.emptyList());

        // Should not throw — errors are caught per-timeframe
        service.evaluate(Instrument.E6);
    }

    @Test
    void evaluate_iteratesBothTimeframes() {
        IndicatorSnapshot snap = emptySnapshot();
        when(indicatorService.computeSnapshot(eq(Instrument.MNQ), anyString())).thenReturn(snap);
        when(evaluator.evaluate(any(BehaviourAlertContext.class))).thenReturn(Collections.emptyList());

        service.evaluate(Instrument.MNQ);

        // Should evaluate for both 10m and 1h timeframes.
        // "1h" appears in both SR_SOURCE_TIMEFRAMES and TIMEFRAMES so it's called twice.
        verify(indicatorService, atLeastOnce()).computeSnapshot(Instrument.MNQ, "10m");
        verify(indicatorService, atLeastOnce()).computeSnapshot(Instrument.MNQ, "1h");
        verify(evaluator, times(2)).evaluate(any(BehaviourAlertContext.class));
    }

    @Test
    void evaluate_differentKeysPublishSeparately() {
        IndicatorSnapshot snap = emptySnapshot();
        when(indicatorService.computeSnapshot(eq(Instrument.MCL), anyString())).thenReturn(snap);

        BehaviourAlertSignal signal10m = new BehaviourAlertSignal(
                "ema50:proximity:MCL:10m",
                BehaviourAlertCategory.EMA_PROXIMITY,
                "MCL [10m] — Price approaching EMA50",
                "MCL",
                Instant.now()
        );
        BehaviourAlertSignal signal1h = new BehaviourAlertSignal(
                "ema200:proximity:MCL:1h",
                BehaviourAlertCategory.EMA_PROXIMITY,
                "MCL [1h] — Price approaching EMA200",
                "MCL",
                Instant.now()
        );
        when(evaluator.evaluate(any(BehaviourAlertContext.class)))
                .thenReturn(List.of(signal10m))
                .thenReturn(List.of(signal1h));

        service.evaluate(Instrument.MCL);

        // Both should publish because they have different keys
        verify(messagingTemplate, times(2))
                .convertAndSend(eq("/topic/alerts"), (Object) any());
    }

    @Test
    void evaluate_webSocketFailureDoesNotPreventMentorCapture() {
        IndicatorSnapshot snap = emptySnapshot();
        when(indicatorService.computeSnapshot(eq(Instrument.MCL), anyString())).thenReturn(snap);

        BehaviourAlertSignal signal = new BehaviourAlertSignal(
                "sr:touch:MCL:10m",
                BehaviourAlertCategory.SUPPORT_RESISTANCE,
                "MCL [10m] — Price touching strong low",
                "MCL",
                Instant.now()
        );
        when(evaluator.evaluate(any(BehaviourAlertContext.class)))
                .thenReturn(List.of(signal))
                .thenReturn(Collections.emptyList());

        doThrow(new RuntimeException("WebSocket closed"))
                .when(messagingTemplate).convertAndSend(anyString(), (Object) any());

        service.evaluate(Instrument.MCL);

        // Mentor capture should still be attempted because publish() returns true
        // (WebSocket error is caught inside publish, after lastFired update)
        verify(mentorSignalReviewService).captureBehaviourReview(eq(signal), anyString(), eq(snap));
    }

    // ---- helpers ----

    private static IndicatorSnapshot emptySnapshot() {
        return new IndicatorSnapshot(
                "MCL", "10m",
                null, null, null, null,
                null, null,
                null, null, null, null,
                null, false,
                null, null, null,
                null, null,
                null, null, null, null, null,
                null, false, null,
                null, null, null, null,
                null, null, null, null, null,
                // Stochastic
                null, null, null, null,
                // SMC: Internal
                null, null, null, null, null, null,
                // SMC: Swing
                null, null, null, null, null, null,
                // SMC: UC-SMC-008
                false,
                // SMC: Multi-resolution bias
                null,
                // SMC: Legacy
                "UNDEFINED", null, null, null, null, null,
                null, null, null, null,
                // EQH/EQL
                Collections.emptyList(), Collections.emptyList(),
                // Premium/Discount
                null, null, null, null,
                // Zones
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                // MTF
                null,
                // Session PD Array (intraday range-based)
                null, null, null, null,
                // UC-OF-012: Volume Profile
                null, null, null,
                // UC-OF-013: Session CME Context
                "NY_AM",
                null,
                null  // lastPrice
        );
    }
}

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

    private BehaviourAlertService service;

    @BeforeEach
    void setUp() {
        service = new BehaviourAlertService(indicatorService, evaluator, messagingTemplate);
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
                // SMC: Internal
                null, null, null, null, null, null,
                // SMC: Swing
                null, null, null, null, null, null,
                // SMC: UC-SMC-008
                false,
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
                null,
                null  // lastPrice
        );
    }
}

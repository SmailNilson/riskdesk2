package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.FlashCrashPhaseChanged;
import com.riskdesk.domain.orderflow.model.CrashPhase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderFlowCorrelationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void flashCrashPayloadMatchesFrontendContract() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        OrderFlowCorrelationService service = new OrderFlowCorrelationService(
            messagingTemplate, new com.riskdesk.infrastructure.config.OrderFlowProperties());

        boolean[] conditions = {true, false, true, false, true};
        service.onFlashCrashPhaseChanged(new FlashCrashPhaseChanged(
            Instrument.MNQ, CrashPhase.NORMAL, CrashPhase.INITIATING,
            3, conditions, 12.5, Instant.parse("2026-05-29T14:30:00Z")));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/flash-crash"), payloadCaptor.capture());
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();

        // Frontend FlashCrashState reads `phase` (string) + `conditions` (boolean[]).
        assertThat(payload).containsEntry("phase", "INITIATING");
        assertThat(payload).doesNotContainKey("currentPhase");
        assertThat(payload.get("conditions"))
            .isInstanceOf(boolean[].class)
            .isEqualTo(conditions);
        assertThat(payload).containsEntry("conditionsMet", 3);
        assertThat(payload).containsEntry("previousPhase", "NORMAL");
    }

    /**
     * Display calibration: spoofing events below the per-instrument display score
     * must not reach /topic/spoofing (detection/persistence are unfiltered upstream).
     */
    @Test
    void spoofingBelowDisplayScore_isNotPublished() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        com.riskdesk.infrastructure.config.OrderFlowProperties props =
            new com.riskdesk.infrastructure.config.OrderFlowProperties();
        // Default MNQ display min is 35.
        OrderFlowCorrelationService service = new OrderFlowCorrelationService(messagingTemplate, props);

        service.onSpoofingDetected(new com.riskdesk.domain.orderflow.event.SpoofingDetected(
            Instrument.MNQ, spoof(12.0), Instant.parse("2026-06-10T14:30:00Z")));
        verify(messagingTemplate, org.mockito.Mockito.never())
            .convertAndSend(eq("/topic/spoofing"), org.mockito.ArgumentMatchers.<Object>any());

        service.onSpoofingDetected(new com.riskdesk.domain.orderflow.event.SpoofingDetected(
            Instrument.MNQ, spoof(48.0), Instant.parse("2026-06-10T14:31:00Z")));
        verify(messagingTemplate)
            .convertAndSend(eq("/topic/spoofing"), org.mockito.ArgumentMatchers.<Object>any());
    }

    /**
     * /topic/absorption is owned by OrderFlowOrchestrator (its payload keys match the
     * frontend type). This listener publishing a second, key-mismatched payload caused
     * duplicate blank rows in the UI — it must stay log-only.
     */
    @Test
    void absorptionListener_doesNotPublishDuplicatePayload() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        OrderFlowCorrelationService service = new OrderFlowCorrelationService(
            messagingTemplate, new com.riskdesk.infrastructure.config.OrderFlowProperties());

        service.onAbsorptionDetected(new com.riskdesk.domain.orderflow.event.AbsorptionDetected(
            Instrument.MNQ,
            new com.riskdesk.domain.orderflow.model.AbsorptionSignal(
                Instrument.MNQ,
                com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionSide.BULLISH_ABSORPTION,
                150.0, -200, 8.0, 500, Instant.parse("2026-06-10T14:30:00Z"),
                com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionType.CLASSIC,
                "test"),
            Instant.parse("2026-06-10T14:30:00Z")));

        verify(messagingTemplate, org.mockito.Mockito.never())
            .convertAndSend(eq("/topic/absorption"), org.mockito.ArgumentMatchers.<Object>any());
    }

    private static com.riskdesk.domain.orderflow.model.SpoofingSignal spoof(double score) {
        return new com.riskdesk.domain.orderflow.model.SpoofingSignal(
            Instrument.MNQ,
            com.riskdesk.domain.orderflow.model.SpoofingSignal.SpoofSide.ASK_SPOOF,
            29020.0, 44, 3.0, false, score, Instant.parse("2026-06-10T14:30:00Z"));
    }
}

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
        OrderFlowCorrelationService service = new OrderFlowCorrelationService(messagingTemplate);

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
}

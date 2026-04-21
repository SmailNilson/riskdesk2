package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.CrossInstrumentAlertService;
import com.riskdesk.domain.engine.correlation.CorrelationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CorrelationController — ONIMS REST API")
class CorrelationControllerTest {

    @Mock private CrossInstrumentAlertService correlationService;

    private CorrelationController controller;

    @BeforeEach
    void setUp() {
        controller = new CorrelationController(correlationService);
    }

    @Nested
    @DisplayName("GET /status")
    class StatusEndpoint {

        @Test
        void returnsCurrentEngineState() {
            when(correlationService.currentState()).thenReturn(CorrelationState.IDLE);
            when(correlationService.getVixThreshold()).thenReturn(20.0);
            when(correlationService.getCachedVixPrice()).thenReturn(new BigDecimal("22.5"));
            when(correlationService.getBlackoutStart()).thenReturn(null);
            when(correlationService.getBlackoutDurationMinutes()).thenReturn(20);

            ResponseEntity<Map<String, Object>> response = controller.status();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = response.getBody();
            assertThat(body).containsEntry("strategy", "ONIMS");
            assertThat(body).containsEntry("engineState", "IDLE");
            assertThat(body).containsEntry("vixThreshold", 20.0);
            assertThat(body).containsEntry("blackoutActive", false);
        }

        @Test
        void blackoutActiveWhenWithinWindow() {
            Instant recentStart = Instant.now().minusSeconds(60);
            when(correlationService.currentState()).thenReturn(CorrelationState.MCL_TRIGGERED);
            when(correlationService.getVixThreshold()).thenReturn(20.0);
            when(correlationService.getCachedVixPrice()).thenReturn(null);
            when(correlationService.getBlackoutStart()).thenReturn(recentStart);
            when(correlationService.getBlackoutDurationMinutes()).thenReturn(20);

            ResponseEntity<Map<String, Object>> response = controller.status();

            assertThat(response.getBody()).containsEntry("blackoutActive", true);
            assertThat(response.getBody()).containsEntry("engineState", "MCL_TRIGGERED");
        }
    }

    @Nested
    @DisplayName("GET /history")
    class HistoryEndpoint {

        @Test
        void returnsEmptyListWhenNoSignals() {
            when(correlationService.getSignalHistory()).thenReturn(Collections.emptyList());

            ResponseEntity<List<Map<String, Object>>> response = controller.history();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEmpty();
        }

        @Test
        void returnsSignalHistory() {
            List<Map<String, Object>> history = List.of(
                    Map.of("type", "ONIMS", "lagSeconds", 180L));
            when(correlationService.getSignalHistory()).thenReturn(history);

            ResponseEntity<List<Map<String, Object>>> response = controller.history();

            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0)).containsEntry("type", "ONIMS");
        }
    }

    @Nested
    @DisplayName("POST /config")
    class ConfigEndpoint {

        @Test
        void updatesVixThreshold() {
            when(correlationService.getVixThreshold()).thenReturn(25.0);
            when(correlationService.getBlackoutDurationMinutes()).thenReturn(20);

            ResponseEntity<Map<String, Object>> response =
                    controller.config(Map.of("vixThreshold", 25.0));

            verify(correlationService).setVixThreshold(25.0);
            assertThat(response.getBody()).containsEntry("status", "updated");
            assertThat(response.getBody()).containsEntry("vixThreshold", 25.0);
        }

        @Test
        void updatesBlackoutDuration() {
            when(correlationService.getVixThreshold()).thenReturn(20.0);
            when(correlationService.getBlackoutDurationMinutes()).thenReturn(30);

            ResponseEntity<Map<String, Object>> response =
                    controller.config(Map.of("blackoutDurationMinutes", 30));

            verify(correlationService).setBlackoutDurationMinutes(30);
            assertThat(response.getBody()).containsEntry("blackoutDurationMins", 30);
        }

        @Test
        void ignoresUnknownConfigKeys() {
            when(correlationService.getVixThreshold()).thenReturn(20.0);
            when(correlationService.getBlackoutDurationMinutes()).thenReturn(20);

            ResponseEntity<Map<String, Object>> response =
                    controller.config(Map.of("unknownKey", "value"));

            verify(correlationService, never()).setVixThreshold(anyDouble());
            verify(correlationService, never()).setBlackoutDurationMinutes(anyInt());
            assertThat(response.getBody()).containsEntry("status", "updated");
        }
    }

    @Nested
    @DisplayName("POST /blackout")
    class BlackoutEndpoint {

        @Test
        void activatesBlackoutWindow() {
            Instant now = Instant.now();
            doNothing().when(correlationService).activateBlackout();
            when(correlationService.getBlackoutStart()).thenReturn(now);
            when(correlationService.getBlackoutDurationMinutes()).thenReturn(20);

            ResponseEntity<Map<String, Object>> response = controller.activateBlackout();

            verify(correlationService).activateBlackout();
            assertThat(response.getBody()).containsEntry("status", "blackout_activated");
        }
    }

    @Nested
    @DisplayName("POST /reset")
    class ResetEndpoint {

        @Test
        void resetsEngineAndHistory() {
            ResponseEntity<Map<String, Object>> response = controller.reset();

            verify(correlationService).reset();
            assertThat(response.getBody()).containsEntry("status", "reset");
            assertThat(response.getBody()).containsEntry("engineState", "IDLE");
        }
    }
}

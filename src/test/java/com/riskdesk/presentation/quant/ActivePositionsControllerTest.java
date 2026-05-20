package com.riskdesk.presentation.quant;

import com.riskdesk.application.dto.ActivePositionView;
import com.riskdesk.application.quant.positions.ActivePositionsService;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivePositionsControllerTest {

    private ActivePositionsService service;
    private ActivePositionsController controller;

    @BeforeEach
    void setUp() {
        service = mock(ActivePositionsService.class);
        controller = new ActivePositionsController(service);
    }

    @Test
    void active_returns_list_when_present() {
        ActivePositionView view = sampleView(ExecutionStatus.ACTIVE);
        when(service.listActive()).thenReturn(List.of(view));

        ResponseEntity<List<ActivePositionView>> response = controller.active();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).executionId()).isEqualTo(42L);
    }

    @Test
    void active_returns_empty_list_when_no_open_positions() {
        when(service.listActive()).thenReturn(List.of());

        ResponseEntity<List<ActivePositionView>> response = controller.active();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void close_existing_position_returns_updated_view() {
        ActivePositionView updated = sampleView(ExecutionStatus.EXIT_SUBMITTED);
        when(service.closePosition(42L, "tester")).thenReturn(Optional.of(updated));

        ResponseEntity<ActivePositionView> response = controller.close(42L, "tester");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(ExecutionStatus.EXIT_SUBMITTED);
    }

    @Test
    void close_unknown_id_returns_404() {
        when(service.closePosition(99L, "tester")).thenReturn(Optional.empty());

        ResponseEntity<ActivePositionView> response = controller.close(99L, "tester");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void close_already_terminal_position_is_idempotent() {
        // The service returns the existing terminal view rather than throwing — the
        // controller must surface that as 200 so the UI can refresh its list.
        ActivePositionView terminal = sampleView(ExecutionStatus.CLOSED);
        when(service.closePosition(42L, "tester")).thenReturn(Optional.of(terminal));

        ResponseEntity<ActivePositionView> response = controller.close(42L, "tester");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(ExecutionStatus.CLOSED);
    }

    private static ActivePositionView sampleView(ExecutionStatus status) {
        return new ActivePositionView(
            42L,
            "MNQ",
            "LONG",
            "BUY",
            status,
            "test-reason",
            new BigDecimal("27450.00"),
            new BigDecimal("27478.25"),
            new BigDecimal("27425.00"),
            new BigDecimal("27490.00"),
            null,
            1,
            Instant.parse("2026-04-30T13:30:00Z"),
            new BigDecimal("28.2500"),
            new BigDecimal("56.50"),
            new BigDecimal("0.103"),
            ExecutionTriggerSource.QUANT_AUTO_ARM,
            status != ExecutionStatus.CLOSED
        );
    }
}

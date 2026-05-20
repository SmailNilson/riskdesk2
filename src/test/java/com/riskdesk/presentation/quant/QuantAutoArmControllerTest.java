package com.riskdesk.presentation.quant;

import com.riskdesk.application.quant.automation.QuantAutoArmService;
import com.riskdesk.application.service.ExecutionManagerService;
import com.riskdesk.application.service.SubmitEntryOrderCommand;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.presentation.quant.dto.AutoArmStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantAutoArmControllerTest {

    private static final Instant NOW = Instant.parse("2026-04-30T13:30:00Z");

    private QuantAutoArmService autoArmService;
    private ExecutionManagerService executionManager;
    private TradeExecutionRepositoryPort repo;
    private QuantAutoArmController controller;

    @BeforeEach
    void setUp() {
        autoArmService = mock(QuantAutoArmService.class);
        executionManager = mock(ExecutionManagerService.class);
        repo = mock(TradeExecutionRepositoryPort.class);
        controller = new QuantAutoArmController(autoArmService, executionManager, repo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void fire_unknown_execution_returns_404() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        ResponseEntity<AutoArmStatusResponse> response = controller.fire(99L, "tester");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void fire_pending_execution_calls_submit_entry_order() {
        TradeExecutionRecord pending = newRec(1L, ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        TradeExecutionRecord submitted = newRec(1L, ExecutionStatus.ENTRY_SUBMITTED);
        when(repo.findById(1L)).thenReturn(Optional.of(pending));
        when(executionManager.submitEntryOrder(any())).thenReturn(submitted);

        ResponseEntity<AutoArmStatusResponse> response = controller.fire(1L, "tester");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("ENTRY_SUBMITTED");
        verify(executionManager, times(1)).submitEntryOrder(any(SubmitEntryOrderCommand.class));
    }

    @Test
    void fire_already_submitted_execution_is_idempotent() {
        TradeExecutionRecord submitted = newRec(1L, ExecutionStatus.ENTRY_SUBMITTED);
        when(repo.findById(1L)).thenReturn(Optional.of(submitted));

        ResponseEntity<AutoArmStatusResponse> response = controller.fire(1L, "tester");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("ENTRY_SUBMITTED");
        verify(executionManager, never()).submitEntryOrder(any());
    }

    @Test
    void cancel_pending_returns_cancelled_response() {
        TradeExecutionRecord cancelled = newRec(1L, ExecutionStatus.CANCELLED);
        when(autoArmService.cancel(1L, "tester")).thenReturn(Optional.of(cancelled));

        ResponseEntity<AutoArmStatusResponse> response = controller.cancel(1L, "tester");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancel_unknown_execution_returns_404() {
        when(autoArmService.cancel(99L, "tester")).thenReturn(Optional.empty());
        ResponseEntity<AutoArmStatusResponse> response = controller.cancel(99L, "tester");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void active_returns_pending_quant_auto_arm_executions() {
        TradeExecutionRecord pending = newRec(1L, ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        when(repo.findPendingByTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM)).thenReturn(List.of(pending));

        ResponseEntity<List<AutoArmStatusResponse>> response = controller.active();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).executionId()).isEqualTo(1L);
        assertThat(response.getBody().get(0).direction()).isEqualTo("SHORT");
    }

    private static TradeExecutionRecord newRec(long id, ExecutionStatus status) {
        TradeExecutionRecord rec = new TradeExecutionRecord();
        rec.setId(id);
        rec.setStatus(status);
        rec.setInstrument("MNQ");
        rec.setAction("SELL");
        rec.setTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM);
        rec.setCreatedAt(NOW);
        return rec;
    }
}

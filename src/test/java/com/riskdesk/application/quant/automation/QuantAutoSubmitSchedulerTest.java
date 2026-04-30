package com.riskdesk.application.quant.automation;

import com.riskdesk.application.service.ExecutionManagerService;
import com.riskdesk.application.service.SubmitEntryOrderCommand;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.automation.AutoArmStateChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuantAutoSubmitScheduler}. Drives the scheduler
 * imperatively via {@link QuantAutoSubmitScheduler#tickInternal()} so we
 * don't depend on Spring's scheduling thread.
 */
class QuantAutoSubmitSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-04-30T13:30:00Z");

    private TradeExecutionRepositoryPort repo;
    private ExecutionManagerService manager;
    private QuantAutoSubmitProperties props;
    private CapturingPublisher publisher;
    private QuantAutoSubmitScheduler scheduler;

    @BeforeEach
    void setUp() {
        repo = mock(TradeExecutionRepositoryPort.class);
        manager = mock(ExecutionManagerService.class);
        props = new QuantAutoSubmitProperties();
        props.setEnabled(true);
        props.setDelaySeconds(30);
        publisher = new CapturingPublisher();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        scheduler = new QuantAutoSubmitScheduler(repo, manager, props, publisher, clock);
    }

    @Test
    void disabled_scheduler_does_not_query_repo() {
        props.setEnabled(false);
        scheduler.tickInternal();
        verify(repo, never()).findPendingByTriggerSource(any());
        verify(manager, never()).submitEntryOrder(any());
    }

    @Test
    void empty_pending_list_skips_submission() {
        when(repo.findPendingByTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM)).thenReturn(List.of());
        scheduler.tickInternal();
        verify(manager, never()).submitEntryOrder(any());
    }

    @Test
    void young_pending_arm_is_not_submitted() {
        TradeExecutionRecord young = pending(NOW.minusSeconds(15)); // < 30 s delay
        when(repo.findPendingByTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM)).thenReturn(List.of(young));
        scheduler.tickInternal();
        verify(manager, never()).submitEntryOrder(any());
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void aged_pending_arm_is_submitted_and_event_fires() {
        TradeExecutionRecord aged = pending(NOW.minusSeconds(45)); // > 30 s delay
        when(repo.findPendingByTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM)).thenReturn(List.of(aged));
        when(manager.submitEntryOrder(any())).thenReturn(aged);
        scheduler.tickInternal();
        verify(manager, times(1)).submitEntryOrder(any(SubmitEntryOrderCommand.class));
        // The AUTO_SUBMITTED state-changed event was published.
        assertThat(publisher.events).hasSize(1);
        AutoArmStateChangedEvent ev = (AutoArmStateChangedEvent) publisher.events.get(0);
        assertThat(ev.state()).isEqualTo(AutoArmStateChangedEvent.State.AUTO_SUBMITTED);
    }

    @Test
    void manager_failure_does_not_break_loop() {
        TradeExecutionRecord aged1 = pending(NOW.minusSeconds(45));
        aged1.setId(1L);
        TradeExecutionRecord aged2 = pending(NOW.minusSeconds(45));
        aged2.setId(2L);
        when(repo.findPendingByTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM))
            .thenReturn(List.of(aged1, aged2));
        when(manager.submitEntryOrder(any()))
            .thenThrow(new RuntimeException("ibkr down"))
            .thenReturn(aged2);
        scheduler.tickInternal();
        // Both rows attempted — second one survives the first row's failure.
        verify(manager, times(2)).submitEntryOrder(any());
    }

    @Test
    void rows_with_null_created_at_are_skipped() {
        TradeExecutionRecord noTimestamp = pending(null);
        when(repo.findPendingByTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM)).thenReturn(List.of(noTimestamp));
        scheduler.tickInternal();
        verify(manager, never()).submitEntryOrder(any());
    }

    @Test
    void zero_delay_submits_immediately() {
        props.setDelaySeconds(0);
        TradeExecutionRecord justArmed = pending(NOW); // age = 0 seconds, delay = 0 → submit
        when(repo.findPendingByTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM)).thenReturn(List.of(justArmed));
        when(manager.submitEntryOrder(any())).thenReturn(justArmed);
        scheduler.tickInternal();
        verify(manager, times(1)).submitEntryOrder(any());
    }

    private static TradeExecutionRecord pending(Instant createdAt) {
        TradeExecutionRecord rec = new TradeExecutionRecord();
        rec.setId(1L);
        rec.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        rec.setTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM);
        rec.setInstrument("MNQ");
        rec.setAction("SELL");
        rec.setCreatedAt(createdAt);
        return rec;
    }

    private static class CapturingPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();
        @Override public void publishEvent(Object event) { events.add(event); }
        @Override public void publishEvent(ApplicationEvent event) { events.add(event); }
    }
}

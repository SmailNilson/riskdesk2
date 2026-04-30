package com.riskdesk.application.quant.automation;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.automation.AutoArmFiredEvent;
import com.riskdesk.domain.quant.automation.AutoArmStateChangedEvent;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Application-layer tests for {@link QuantAutoArmService}. The repository
 * port is hand-rolled (not Mockito) so we can model {@code createIfAbsent}
 * idempotence without verbose stubbing.
 */
class QuantAutoArmServiceTest {

    private static final Instant NY_KILL_ZONE = ZonedDateTime
        .of(2026, 4, 30, 9, 30, 0, 0, ZoneId.of("America/New_York"))
        .toInstant();

    private RecordingRepo repo;
    private CapturingPublisher publisher;
    private QuantAutoArmProperties autoArmProps;
    private QuantAutoSubmitProperties autoSubmitProps;
    private QuantAutoArmService service;

    @BeforeEach
    void setUp() {
        repo = new RecordingRepo();
        publisher = new CapturingPublisher();
        autoArmProps = new QuantAutoArmProperties();
        autoArmProps.setEnabled(true);
        autoArmProps.setMinScore(7);
        autoArmProps.setBrokerAccountId("DU1234567");
        autoArmProps.setDefaultQuantity(1);
        autoSubmitProps = new QuantAutoSubmitProperties();
        autoSubmitProps.setEnabled(true);
        autoSubmitProps.setDelaySeconds(30);
        Clock clock = Clock.fixed(NY_KILL_ZONE, ZoneOffset.UTC);
        service = new QuantAutoArmService(repo, publisher, autoArmProps, autoSubmitProps, clock);
    }

    @Test
    void disabled_service_creates_no_execution() {
        autoArmProps.setEnabled(false);
        Optional<TradeExecutionRecord> created = service.onSnapshot(Instrument.MNQ, snap(7));
        assertThat(created).isEmpty();
        assertThat(repo.created).isEmpty();
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void valid_snapshot_creates_execution_and_publishes_events() {
        Optional<TradeExecutionRecord> created = service.onSnapshot(Instrument.MNQ, snap(7));
        assertThat(created).isPresent();
        TradeExecutionRecord rec = created.get();
        assertThat(rec.getStatus()).isEqualTo(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        assertThat(rec.getTriggerSource()).isEqualTo(ExecutionTriggerSource.QUANT_AUTO_ARM);
        assertThat(rec.getInstrument()).isEqualTo("MNQ");
        assertThat(rec.getAction()).isEqualTo("SELL"); // SHORT → SELL
        assertThat(rec.getMentorSignalReviewId()).isNull();
        // Both AutoArmFiredEvent and AutoArmStateChangedEvent(ARMED) get published.
        assertThat(publisher.events).hasSize(2);
        assertThat(publisher.events.get(0)).isInstanceOf(AutoArmFiredEvent.class);
        assertThat(publisher.events.get(1)).isInstanceOf(AutoArmStateChangedEvent.class);
    }

    @Test
    void cooldown_blocks_second_arm_within_window() {
        service.onSnapshot(Instrument.MNQ, snap(7));
        publisher.events.clear();
        // Same wall-clock as the first arm — cooldown is active.
        Optional<TradeExecutionRecord> second = service.onSnapshot(Instrument.MNQ, snap(7));
        assertThat(second).isEmpty();
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void active_execution_blocks_arm() {
        TradeExecutionRecord existing = new TradeExecutionRecord();
        existing.setId(1L);
        existing.setStatus(ExecutionStatus.ACTIVE);
        existing.setInstrument("MNQ");
        repo.activeByInstrument.put("MNQ", existing);

        Optional<TradeExecutionRecord> result = service.onSnapshot(Instrument.MNQ, snap(7));
        assertThat(result).isEmpty();
        assertThat(repo.created).isEmpty();
    }

    @Test
    void cancel_pending_arm_sets_cancelled_status_and_publishes() {
        TradeExecutionRecord rec = service.onSnapshot(Instrument.MNQ, snap(7)).orElseThrow();
        publisher.events.clear();

        Optional<TradeExecutionRecord> cancelled = service.cancel(rec.getId(), "operator");
        assertThat(cancelled).isPresent();
        assertThat(cancelled.get().getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(publisher.events).hasSize(1);
        AutoArmStateChangedEvent ev = (AutoArmStateChangedEvent) publisher.events.get(0);
        assertThat(ev.state()).isEqualTo(AutoArmStateChangedEvent.State.CANCELLED);
    }

    @Test
    void cancel_already_cancelled_is_idempotent() {
        TradeExecutionRecord rec = service.onSnapshot(Instrument.MNQ, snap(7)).orElseThrow();
        service.cancel(rec.getId(), "operator");
        publisher.events.clear();

        Optional<TradeExecutionRecord> second = service.cancel(rec.getId(), "operator");
        assertThat(second).isPresent();
        assertThat(second.get().getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        // No second CANCELLED event — already cancelled returns the row without publishing.
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void cancel_non_pending_execution_is_no_op() {
        TradeExecutionRecord submitted = new TradeExecutionRecord();
        submitted.setId(99L);
        submitted.setInstrument("MNQ");
        submitted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        repo.byId.put(99L, submitted);

        Optional<TradeExecutionRecord> result = service.cancel(99L, "operator");
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
    }

    @Test
    void compute_auto_submit_at_returns_null_when_disabled() {
        autoSubmitProps.setEnabled(false);
        TradeExecutionRecord rec = new TradeExecutionRecord();
        rec.setCreatedAt(NY_KILL_ZONE);
        assertThat(service.computeAutoSubmitAt(rec)).isNull();
    }

    @Test
    void compute_auto_submit_at_returns_created_plus_delay() {
        TradeExecutionRecord rec = new TradeExecutionRecord();
        rec.setCreatedAt(NY_KILL_ZONE);
        assertThat(service.computeAutoSubmitAt(rec)).isEqualTo(NY_KILL_ZONE.plusSeconds(30));
    }

    // ───────────────────────── helpers ─────────────────────────

    private static QuantSnapshot snap(int score) {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        for (int i = 0; i < Gate.values().length; i++) {
            boolean ok = i < score;
            gates.put(Gate.values()[i], new GateResult(ok, "x"));
        }
        return new QuantSnapshot(
            Instrument.MNQ, gates, score, 20000.0, "test", 0.0,
            ZonedDateTime.now(),
            List.of(), List.of(), 0, false
        );
    }

    /** Minimal hand-rolled repo — Mockito gets verbose for this many methods. */
    private static class RecordingRepo implements TradeExecutionRepositoryPort {
        final List<TradeExecutionRecord> created = new ArrayList<>();
        final Map<Long, TradeExecutionRecord> byId = new java.util.HashMap<>();
        final Map<String, TradeExecutionRecord> activeByInstrument = new java.util.HashMap<>();
        long idSeq = 100;

        @Override public TradeExecutionRecord createIfAbsent(TradeExecutionRecord execution) {
            execution.setId(++idSeq);
            created.add(execution);
            byId.put(execution.getId(), execution);
            return execution;
        }
        @Override public TradeExecutionRecord save(TradeExecutionRecord execution) {
            byId.put(execution.getId(), execution);
            return execution;
        }
        @Override public Optional<TradeExecutionRecord> findById(Long id) { return Optional.ofNullable(byId.get(id)); }
        @Override public Optional<TradeExecutionRecord> findByIdForUpdate(Long id) { return findById(id); }
        @Override public Optional<TradeExecutionRecord> findByMentorSignalReviewId(Long id) { return Optional.empty(); }
        @Override public List<TradeExecutionRecord> findByMentorSignalReviewIds(Collection<Long> ids) { return List.of(); }
        @Override public Optional<TradeExecutionRecord> findByIbkrOrderId(Integer id) { return Optional.empty(); }
        @Override public Optional<TradeExecutionRecord> findByExecutionKey(String key) { return Optional.empty(); }
        @Override public Optional<TradeExecutionRecord> findActiveByInstrument(String instrument) {
            return Optional.ofNullable(activeByInstrument.get(instrument));
        }
        @Override public List<TradeExecutionRecord> findPendingByTriggerSource(ExecutionTriggerSource src) {
            return byId.values().stream()
                .filter(r -> r.getTriggerSource() == src)
                .filter(r -> r.getStatus() == ExecutionStatus.PENDING_ENTRY_SUBMISSION)
                .toList();
        }
        @Override public List<TradeExecutionRecord> findAllActive() {
            return byId.values().stream()
                .filter(r -> r.getStatus() != ExecutionStatus.CLOSED
                    && r.getStatus() != ExecutionStatus.CANCELLED
                    && r.getStatus() != ExecutionStatus.REJECTED
                    && r.getStatus() != ExecutionStatus.FAILED)
                .toList();
        }
    }

    private static class CapturingPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();
        @Override public void publishEvent(Object event) { events.add(event); }
        @Override public void publishEvent(ApplicationEvent event) { events.add(event); }
    }
}

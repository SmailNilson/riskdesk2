package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import com.riskdesk.infrastructure.config.TrailingStopProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 tests — verify that {@link TradeSimulationService} reads open
 * simulations from the {@link TradeSimulationRepositoryPort} and writes
 * state transitions exclusively to the simulation aggregate.
 *
 * <p>These tests replace the Phase 1b {@code TradeSimulationServiceDualWriteTest}.
 * Rather than asserting a dual-write behaviour to both the legacy review record
 * and the new simulation table, they assert the post-Phase-3 single-writer
 * model: no legacy sim fields are touched on {@code MentorSignalReviewRecord}
 * or {@code MentorAudit}, no simulation events fire on {@code /topic/mentor-alerts},
 * and simulation updates publish on {@code /topic/simulations}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradeSimulationServiceSchedulerTest {

    @Mock
    private MentorSignalReviewRepositoryPort reviewRepository;

    @Mock
    private MentorAuditRepositoryPort auditRepository;

    @Mock
    private CandleRepositoryPort candleRepositoryPort;

    @Mock
    private ObjectProvider<SimpMessagingTemplate> messagingProvider;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private TradeSimulationRepositoryPort simulationRepository;

    private TradeSimulationService service;

    @BeforeEach
    void setUp() {
        TrailingStopProperties trailingStopProperties = new TrailingStopProperties();
        trailingStopProperties.setEnabled(false);
        when(messagingProvider.getIfAvailable()).thenReturn(messagingTemplate);
        service = new TradeSimulationService(
            reviewRepository,
            auditRepository,
            candleRepositoryPort,
            new ObjectMapper(),
            messagingProvider,
            trailingStopProperties,
            simulationRepository
        );
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Test
    void refreshPendingSimulations_queriesSimulationPortForOpenStatuses() {
        when(simulationRepository.findByStatuses(any())).thenReturn(List.of());

        service.refreshPendingSimulations();

        ArgumentCaptor<java.util.Collection<TradeSimulationStatus>> captor =
            ArgumentCaptor.forClass(java.util.Collection.class);
        verify(simulationRepository, atLeastOnce()).findByStatuses(captor.capture());
        assertThat(captor.getValue())
            .contains(TradeSimulationStatus.PENDING_ENTRY, TradeSimulationStatus.ACTIVE);
    }

    @Test
    void refreshPendingSimulations_doesNotCallLegacyReviewFindBySimulationStatuses() {
        // Regression guard for Phase 3 — the scheduler must NOT touch the
        // legacy review-side simulation query path (which was removed from
        // the port). Historically reviewRepository.findBySimulationStatuses
        // drove the whole scheduler; if any caller is tempted to reintroduce
        // a legacy read, this test ensures the scheduler remains simulation-
        // aggregate-first.
        when(simulationRepository.findByStatuses(any())).thenReturn(List.of());

        service.refreshPendingSimulations();
        service.refreshPendingAuditSimulations();

        // The port no longer exposes findBySimulationStatuses — proving the
        // code compiles against the new contract is part of the test. We also
        // assert that no review/audit records are queried by id without the
        // simulation aggregate asking for them (no open sims → no lookups).
        verify(reviewRepository, never()).findById(any());
        verify(auditRepository, never()).findById(any());
    }

    // ── Expiry — writes only to the simulation aggregate ──────────────────

    @Test
    void expiredPendingEntry_writesCancelledOnlyToSimulationAggregate() {
        TradeSimulation sim = new TradeSimulation(
            7L, 42L, ReviewType.SIGNAL, "MNQ", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, null, null, null, null,
            Instant.now().minusSeconds(7200) // 2h ago → expired
        );
        when(simulationRepository.findByStatuses(any())).thenReturn(List.of(sim));
        when(simulationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refreshPendingSimulations();

        // The legacy review path is never touched.
        verify(reviewRepository, never()).save(any(MentorSignalReviewRecord.class));

        // A simulation save was issued with the new CANCELLED status.
        ArgumentCaptor<TradeSimulation> captor = ArgumentCaptor.forClass(TradeSimulation.class);
        verify(simulationRepository, atLeastOnce()).save(captor.capture());
        TradeSimulation saved = captor.getValue();
        assertThat(saved.reviewId()).isEqualTo(42L);
        assertThat(saved.reviewType()).isEqualTo(ReviewType.SIGNAL);
        assertThat(saved.simulationStatus()).isEqualTo(TradeSimulationStatus.CANCELLED);
        assertThat(saved.resolutionTime()).isNotNull();
        assertThat(saved.id()).isEqualTo(7L);
        assertThat(saved.createdAt()).isEqualTo(sim.createdAt());
    }

    @Test
    void expiredPendingAudit_writesCancelledOnlyToSimulationAggregate() {
        TradeSimulation sim = new TradeSimulation(
            9L, 99L, ReviewType.AUDIT, "MGC", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, null, null, null, null,
            Instant.now().minusSeconds(7200)
        );
        when(simulationRepository.findByStatuses(any())).thenReturn(List.of(sim));
        when(simulationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refreshPendingAuditSimulations();

        // The audit legacy path is never touched.
        verify(auditRepository, never()).save(any(MentorAudit.class));

        ArgumentCaptor<TradeSimulation> captor = ArgumentCaptor.forClass(TradeSimulation.class);
        verify(simulationRepository, atLeastOnce()).save(captor.capture());
        TradeSimulation saved = captor.getValue();
        assertThat(saved.reviewType()).isEqualTo(ReviewType.AUDIT);
        assertThat(saved.reviewId()).isEqualTo(99L);
        assertThat(saved.simulationStatus()).isEqualTo(TradeSimulationStatus.CANCELLED);
    }

    // ── WebSocket topic policy — no more /topic/mentor-alerts for sim ─────

    @Test
    void terminalTransition_pushesOnlyOnTopicSimulations_notOnTopicMentorAlerts() {
        TradeSimulation sim = new TradeSimulation(
            1L, 42L, ReviewType.SIGNAL, "MNQ", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, null, null, null, null,
            Instant.now().minusSeconds(7200)
        );
        when(simulationRepository.findByStatuses(any())).thenReturn(List.of(sim));
        when(simulationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refreshPendingSimulations();

        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/simulations"), any(Object.class));
        verify(messagingTemplate, never())
            .convertAndSend(eq("/topic/mentor-alerts"), any(Object.class));
    }

    // ── Empty path — no work, no writes ──────────────────────────────────

    @Test
    void noOpenSimulations_doesNotWriteAnything() {
        when(simulationRepository.findByStatuses(any())).thenReturn(List.of());

        service.refreshPendingSimulations();
        service.refreshPendingAuditSimulations();

        verify(simulationRepository, never()).save(any());
        verify(reviewRepository, never()).save(any(MentorSignalReviewRecord.class));
        verify(auditRepository, never()).save(any(MentorAudit.class));
    }

    // ── No-op when state unchanged ──────────────────────────────────────

    @Test
    void stablePendingEntry_withNoCandles_doesNotSaveOrPublish() {
        TradeSimulation sim = new TradeSimulation(
            3L, 55L, ReviewType.SIGNAL, "MGC", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, BigDecimal.ZERO, null, null, null,
            Instant.now().minusSeconds(60)
        );
        MentorSignalReviewRecord review = new MentorSignalReviewRecord();
        review.setId(55L);
        review.setAction("LONG");
        review.setInstrument("MGC");
        review.setAnalysisJson(null);

        when(simulationRepository.findByStatuses(any())).thenReturn(List.of(sim));
        when(reviewRepository.findById(eq(55L))).thenReturn(java.util.Optional.of(review));
        when(candleRepositoryPort.findCandles(any(), any(), any())).thenReturn(List.of());

        service.refreshPendingSimulations();

        // No candles → scheduler short-circuits → no writes.
        verify(simulationRepository, never()).save(any(TradeSimulation.class));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/simulations"), (Object) any());
    }

    // ── Directional reversal ─────────────────────────────────────────────

    @Test
    void directionalReversal_closesOlderSimulationOnly() {
        Instant now = Instant.now();
        // Older ACTIVE LONG on MNQ; newer PENDING_ENTRY SHORT on MNQ.
        TradeSimulation older = new TradeSimulation(
            10L, 100L, ReviewType.SIGNAL, "MNQ", "LONG",
            TradeSimulationStatus.ACTIVE,
            now.minusSeconds(600), null, BigDecimal.ZERO, null, null, null,
            now.minusSeconds(900)
        );
        TradeSimulation newer = new TradeSimulation(
            11L, 101L, ReviewType.SIGNAL, "MNQ", "SHORT",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, null, null, null, null,
            now.minusSeconds(300)
        );
        when(simulationRepository.findByStatuses(any())).thenReturn(List.of(older, newer));
        when(simulationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findById(any())).thenReturn(java.util.Optional.empty());
        when(candleRepositoryPort.findCandles(any(), any(), any())).thenReturn(List.of());

        service.refreshPendingSimulations();

        // Older ACTIVE sim is saved with REVERSED; newer sim is NOT closed.
        ArgumentCaptor<TradeSimulation> captor = ArgumentCaptor.forClass(TradeSimulation.class);
        verify(simulationRepository, times(1)).save(captor.capture());
        TradeSimulation saved = captor.getValue();
        assertThat(saved.id()).isEqualTo(10L);
        assertThat(saved.simulationStatus()).isEqualTo(TradeSimulationStatus.REVERSED);
    }
}

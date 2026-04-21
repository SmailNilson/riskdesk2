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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 1b tests — verify that {@link TradeSimulationService} dual-writes every
 * simulation state transition to the new {@link TradeSimulationRepositoryPort}
 * IN ADDITION to the legacy review/audit repositories. Legacy writes must not be
 * affected by new-side failures.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradeSimulationServiceDualWriteTest {

    @Mock
    private MentorSignalReviewRepositoryPort reviewRepository;

    @Mock
    private MentorAuditRepositoryPort auditRepository;

    @Mock
    private CandleRepositoryPort candleRepositoryPort;

    @Mock
    private ObjectProvider<MentorSignalReviewService> reviewServiceProvider;

    @Mock
    private ObjectProvider<SimpMessagingTemplate> messagingProvider;

    @Mock
    private TradeSimulationRepositoryPort simulationRepository;

    private TradeSimulationService service;

    @BeforeEach
    void setUp() {
        TrailingStopProperties trailingStopProperties = new TrailingStopProperties();
        trailingStopProperties.setEnabled(false);
        service = new TradeSimulationService(
            reviewRepository,
            auditRepository,
            candleRepositoryPort,
            new ObjectMapper(),
            reviewServiceProvider,
            messagingProvider,
            trailingStopProperties,
            simulationRepository
        );
    }

    @Test
    void expiredPendingEntry_dualWritesCancelledToSimulationPort() {
        MentorSignalReviewRecord review = signalReview(
            42L, "MNQ", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            Instant.now().minusSeconds(7200) // created 2h ago → expired
        );
        when(reviewRepository.findBySimulationStatuses(any())).thenReturn(List.of(review));
        when(auditRepository.findBySimulationStatuses(any())).thenReturn(List.of());
        when(simulationRepository.findByReviewId(anyLong(), any())).thenReturn(Optional.empty());
        when(simulationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refreshPendingSimulations();

        // Legacy write still happened.
        verify(reviewRepository, atLeastOnce()).save(review);
        assertThat(review.getSimulationStatus()).isEqualTo(TradeSimulationStatus.CANCELLED);

        // New-side write with matching status.
        ArgumentCaptor<TradeSimulation> captor = ArgumentCaptor.forClass(TradeSimulation.class);
        verify(simulationRepository, atLeastOnce()).save(captor.capture());
        TradeSimulation persisted = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(persisted.reviewId()).isEqualTo(42L);
        assertThat(persisted.reviewType()).isEqualTo(ReviewType.SIGNAL);
        assertThat(persisted.simulationStatus()).isEqualTo(TradeSimulationStatus.CANCELLED);
        assertThat(persisted.instrument()).isEqualTo("MNQ");
        assertThat(persisted.action()).isEqualTo("LONG");
    }

    @Test
    void backfill_writesExistingPendingEntriesEvenWithoutStateChange() {
        MentorSignalReviewRecord review = signalReview(
            77L, "MCL", "SHORT",
            TradeSimulationStatus.PENDING_ENTRY,
            Instant.now().minusSeconds(60) // still within timeout
        );
        when(reviewRepository.findBySimulationStatuses(any())).thenReturn(List.of(review));
        when(auditRepository.findBySimulationStatuses(any())).thenReturn(List.of());
        when(simulationRepository.findByReviewId(eq(77L), eq(ReviewType.SIGNAL)))
            .thenReturn(Optional.empty());
        when(simulationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refreshPendingSimulations();

        // Back-fill dual-write happened even though no state transition occurred.
        verify(simulationRepository, atLeastOnce()).save(any(TradeSimulation.class));
    }

    @Test
    void expiredPendingAudit_dualWritesToSimulationPort() {
        MentorAudit audit = new MentorAudit();
        audit.setId(99L);
        audit.setInstrument("MGC");
        audit.setAction("LONG");
        audit.setSimulationStatus(TradeSimulationStatus.PENDING_ENTRY);
        audit.setCreatedAt(Instant.now().minusSeconds(7200));

        when(reviewRepository.findBySimulationStatuses(any())).thenReturn(List.of());
        when(auditRepository.findBySimulationStatuses(any())).thenReturn(List.of(audit));
        when(simulationRepository.findByReviewId(anyLong(), any())).thenReturn(Optional.empty());
        when(simulationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refreshPendingAuditSimulations();

        // Legacy audit write still happened.
        verify(auditRepository, atLeastOnce()).save(audit);
        assertThat(audit.getSimulationStatus()).isEqualTo(TradeSimulationStatus.CANCELLED);

        // New-side write with AUDIT review type.
        ArgumentCaptor<TradeSimulation> captor = ArgumentCaptor.forClass(TradeSimulation.class);
        verify(simulationRepository, atLeastOnce()).save(captor.capture());
        TradeSimulation last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.reviewType()).isEqualTo(ReviewType.AUDIT);
        assertThat(last.reviewId()).isEqualTo(99L);
        assertThat(last.simulationStatus()).isEqualTo(TradeSimulationStatus.CANCELLED);
    }

    @Test
    void newSideFailure_doesNotBreakLegacyWrite() {
        MentorSignalReviewRecord review = signalReview(
            13L, "MNQ", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            Instant.now().minusSeconds(7200)
        );
        when(reviewRepository.findBySimulationStatuses(any())).thenReturn(List.of(review));
        when(auditRepository.findBySimulationStatuses(any())).thenReturn(List.of());
        when(simulationRepository.findByReviewId(anyLong(), any())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("boom")).when(simulationRepository).save(any());

        // Should not throw — legacy flow must survive new-side failure.
        service.refreshPendingSimulations();

        // Legacy write still happens despite simulationRepository.save() throwing.
        verify(reviewRepository, atLeastOnce()).save(review);
        assertThat(review.getSimulationStatus()).isEqualTo(TradeSimulationStatus.CANCELLED);
        // save was called (and threw) — captured at least once per transition
        verify(simulationRepository, atLeastOnce()).save(any());
    }

    @Test
    void noReviewsOrAudits_doesNotTouchSimulationPort() {
        when(reviewRepository.findBySimulationStatuses(any())).thenReturn(List.of());
        when(auditRepository.findBySimulationStatuses(any())).thenReturn(List.of());

        service.refreshPendingSimulations();
        service.refreshPendingAuditSimulations();

        verify(simulationRepository, times(0)).save(any());
        verifyNoInteractions(candleRepositoryPort);
    }

    @Test
    void backfill_skipsSaveAndPublishWhenExistingSimulationUnchanged() {
        // Review is stable (PENDING_ENTRY, still within timeout, no candles)
        Instant created = Instant.now().minusSeconds(60);
        MentorSignalReviewRecord review = signalReview(
            55L, "MGC", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            created
        );
        when(reviewRepository.findBySimulationStatuses(any())).thenReturn(List.of(review));
        when(auditRepository.findBySimulationStatuses(any())).thenReturn(List.of());
        // Existing new-side row with identical mutable fields → gate must skip save + publish.
        TradeSimulation existing = new TradeSimulation(
            7L, 55L, ReviewType.SIGNAL, "MGC", "LONG",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, BigDecimal.ZERO, null, null, null, created
        );
        when(simulationRepository.findByReviewId(eq(55L), eq(ReviewType.SIGNAL)))
            .thenReturn(Optional.of(existing));
        SimpMessagingTemplate messaging = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
        when(messagingProvider.getIfAvailable()).thenReturn(messaging);

        service.refreshPendingSimulations();

        // No new-side write and no /topic/simulations publication because nothing changed.
        verify(simulationRepository, times(0)).save(any(TradeSimulation.class));
        verifyNoInteractions(messaging);
    }

    @Test
    void backfill_writesAndPublishesWhenExistingSimulationDiffersInMutableField() {
        // Review is stable in PENDING_ENTRY, but the existing new-side row still
        // carries a stale maxDrawdownPoints — gate must treat that as a change.
        Instant created = Instant.now().minusSeconds(60);
        MentorSignalReviewRecord review = signalReview(
            66L, "MNQ", "SHORT",
            TradeSimulationStatus.PENDING_ENTRY,
            created
        );
        review.setMaxDrawdownPoints(new BigDecimal("1.25"));
        when(reviewRepository.findBySimulationStatuses(any())).thenReturn(List.of(review));
        when(auditRepository.findBySimulationStatuses(any())).thenReturn(List.of());
        TradeSimulation existing = new TradeSimulation(
            9L, 66L, ReviewType.SIGNAL, "MNQ", "SHORT",
            TradeSimulationStatus.PENDING_ENTRY,
            null, null, new BigDecimal("0.50"), null, null, null, created
        );
        when(simulationRepository.findByReviewId(eq(66L), eq(ReviewType.SIGNAL)))
            .thenReturn(Optional.of(existing));
        when(simulationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refreshPendingSimulations();

        verify(simulationRepository, atLeastOnce()).save(any(TradeSimulation.class));
    }

    private MentorSignalReviewRecord signalReview(Long id, String instrument, String action,
                                                   TradeSimulationStatus status, Instant createdAt) {
        MentorSignalReviewRecord r = new MentorSignalReviewRecord();
        r.setId(id);
        r.setInstrument(instrument);
        r.setAction(action);
        r.setSimulationStatus(status);
        r.setCreatedAt(createdAt);
        r.setMaxDrawdownPoints(BigDecimal.ZERO);
        return r;
    }
}

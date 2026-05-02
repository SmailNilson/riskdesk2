package com.riskdesk.application.service;

import com.riskdesk.application.dto.CycleEventView;
import com.riskdesk.application.dto.MomentumEventView;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import com.riskdesk.infrastructure.persistence.JpaAbsorptionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaCycleEventRepository;
import com.riskdesk.infrastructure.persistence.JpaDistributionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaIcebergEventRepository;
import com.riskdesk.infrastructure.persistence.JpaMomentumEventRepository;
import com.riskdesk.infrastructure.persistence.JpaSpoofingEventRepository;
import com.riskdesk.infrastructure.persistence.entity.CycleEventEntity;
import com.riskdesk.infrastructure.persistence.entity.MomentumEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies fetch-time filtering for Bug 1 (momentum 2h cutoff) and Bug 2 (cycle confidence ≥ 70).
 * <p>
 * Tests target the service-layer contract: which repository method is called, with what cutoff /
 * threshold, and that the configured property override is honored. The repository implementation
 * (Spring Data derived methods) is exercised end-to-end in integration tests.
 */
class OrderFlowHistoryServiceTest {

    private JpaMomentumEventRepository momentumRepo;
    private JpaCycleEventRepository cycleRepo;
    private OrderFlowProperties properties;
    private OrderFlowHistoryService service;

    @BeforeEach
    void setUp() {
        momentumRepo = mock(JpaMomentumEventRepository.class);
        cycleRepo = mock(JpaCycleEventRepository.class);
        properties = new OrderFlowProperties();

        service = new OrderFlowHistoryService(
                mock(JpaIcebergEventRepository.class),
                mock(JpaAbsorptionEventRepository.class),
                mock(JpaSpoofingEventRepository.class),
                mock(JpaDistributionEventRepository.class),
                momentumRepo,
                cycleRepo,
                properties);
    }

    // ─── Bug 1: Momentum age cutoff ───────────────────────────────────────

    @Test
    void recentMomentumBursts_appliesDefault120MinuteCutoff() {
        when(momentumRepo.findByInstrumentAndTimestampAfterOrderByTimestampDesc(
                any(), any(), any())).thenReturn(List.of());

        Instant before = Instant.now();
        service.recentMomentumBursts(Instrument.MNQ, 20);
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(momentumRepo).findByInstrumentAndTimestampAfterOrderByTimestampDesc(
                eq(Instrument.MNQ), cutoffCaptor.capture(), any(Pageable.class));

        Instant cutoff = cutoffCaptor.getValue();
        // Cutoff = now - 120 min, ±1 sec for clock drift between captures.
        assertThat(cutoff).isBetween(
                before.minus(Duration.ofMinutes(120)).minus(Duration.ofSeconds(1)),
                after.minus(Duration.ofMinutes(120)).plus(Duration.ofSeconds(1)));
    }

    @Test
    void recentMomentumBursts_respectsConfiguredOverride() {
        properties.getMomentum().setHistoryMaxAgeMinutes(30);
        when(momentumRepo.findByInstrumentAndTimestampAfterOrderByTimestampDesc(
                any(), any(), any())).thenReturn(List.of());

        Instant before = Instant.now();
        service.recentMomentumBursts(Instrument.MNQ, 20);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(momentumRepo).findByInstrumentAndTimestampAfterOrderByTimestampDesc(
                eq(Instrument.MNQ), cutoffCaptor.capture(), any(Pageable.class));

        Instant cutoff = cutoffCaptor.getValue();
        assertThat(cutoff).isAfterOrEqualTo(before.minus(Duration.ofMinutes(30)).minus(Duration.ofSeconds(1)));
        assertThat(cutoff).isBeforeOrEqualTo(Instant.now().minus(Duration.ofMinutes(30)).plus(Duration.ofSeconds(1)));
    }

    @Test
    void recentMomentumBursts_doesNotCallUnfilteredRepoMethod() {
        when(momentumRepo.findByInstrumentAndTimestampAfterOrderByTimestampDesc(
                any(), any(), any())).thenReturn(List.of());

        service.recentMomentumBursts(Instrument.MNQ, 20);

        // The pre-fix unfiltered method must not be used — that was the bug.
        verify(momentumRepo, never()).findByInstrumentOrderByTimestampDesc(any(), any());
    }

    @Test
    void recentMomentumBursts_emptyResult_returnsEmptyList() {
        when(momentumRepo.findByInstrumentAndTimestampAfterOrderByTimestampDesc(
                any(), any(), any())).thenReturn(List.of());

        List<MomentumEventView> result = service.recentMomentumBursts(Instrument.MNQ, 20);

        assertThat(result).isEmpty();
    }

    @Test
    void recentMomentumBursts_mapsEntityToView() {
        Instant ts = Instant.parse("2026-05-02T13:00:00Z");
        MomentumEventEntity row = new MomentumEventEntity(
                Instrument.MNQ, ts, "BULLISH_MOMENTUM",
                3.5, 700L, 4.0, 8.0, 1200L);
        when(momentumRepo.findByInstrumentAndTimestampAfterOrderByTimestampDesc(
                any(), any(), any())).thenReturn(List.of(row));

        List<MomentumEventView> result = service.recentMomentumBursts(Instrument.MNQ, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).instrument()).isEqualTo("MNQ");
        assertThat(result.get(0).timestamp()).isEqualTo(ts);
        assertThat(result.get(0).side()).isEqualTo("BULLISH_MOMENTUM");
    }

    // ─── Bug 2: Cycle confidence threshold ────────────────────────────────

    @Test
    void recentCycles_appliesDefault70Threshold() {
        when(cycleRepo.findByInstrumentAndConfidenceGreaterThanEqualOrderByTimestampDesc(
                any(), any(Integer.class), any())).thenReturn(List.of());

        service.recentCycles(Instrument.MNQ, 20);

        verify(cycleRepo).findByInstrumentAndConfidenceGreaterThanEqualOrderByTimestampDesc(
                eq(Instrument.MNQ), eq(70), any(Pageable.class));
    }

    @Test
    void recentCycles_respectsConfiguredOverride() {
        properties.getCycle().setMinConfidence(80);
        when(cycleRepo.findByInstrumentAndConfidenceGreaterThanEqualOrderByTimestampDesc(
                any(), any(Integer.class), any())).thenReturn(List.of());

        service.recentCycles(Instrument.MNQ, 20);

        verify(cycleRepo).findByInstrumentAndConfidenceGreaterThanEqualOrderByTimestampDesc(
                eq(Instrument.MNQ), eq(80), any(Pageable.class));
    }

    @Test
    void recentCycles_doesNotCallUnfilteredRepoMethod() {
        when(cycleRepo.findByInstrumentAndConfidenceGreaterThanEqualOrderByTimestampDesc(
                any(), any(Integer.class), any())).thenReturn(List.of());

        service.recentCycles(Instrument.MNQ, 20);

        verify(cycleRepo, never()).findByInstrumentOrderByTimestampDesc(any(), any());
    }

    @Test
    void recentCycles_mapsEntityToView() {
        Instant ts = Instant.parse("2026-05-02T13:00:00Z");
        CycleEventEntity row = new CycleEventEntity(
                Instrument.MNQ, ts, "BEARISH_CYCLE", "COMPLETE",
                27000.0, 27050.0, 27100.0, 100.0, 12.0,
                85, ts.minusSeconds(720), ts);
        when(cycleRepo.findByInstrumentAndConfidenceGreaterThanEqualOrderByTimestampDesc(
                any(), any(Integer.class), any())).thenReturn(List.of(row));

        List<CycleEventView> result = service.recentCycles(Instrument.MNQ, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).confidence()).isEqualTo(85);
        assertThat(result.get(0).cycleType()).isEqualTo("BEARISH_CYCLE");
    }
}

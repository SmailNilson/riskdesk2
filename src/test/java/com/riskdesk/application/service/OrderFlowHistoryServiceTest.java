package com.riskdesk.application.service;

import com.riskdesk.application.dto.CycleEventView;
import com.riskdesk.application.dto.MomentumEventView;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import com.riskdesk.infrastructure.persistence.JpaAbsorptionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaCycleEventRepository;
import com.riskdesk.infrastructure.persistence.JpaDistributionEventRepository;
import com.riskdesk.infrastructure.persistence.JpaFootprintBarRepository;
import com.riskdesk.infrastructure.persistence.JpaIcebergEventRepository;
import com.riskdesk.infrastructure.persistence.JpaMomentumEventRepository;
import com.riskdesk.infrastructure.persistence.JpaSpoofingEventRepository;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.infrastructure.persistence.entity.CycleEventEntity;
import com.riskdesk.infrastructure.persistence.entity.FootprintBarEntity;
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
                mock(JpaFootprintBarRepository.class),
                mock(com.riskdesk.infrastructure.persistence.JpaWallEpisodeRepository.class),
                properties,
                new com.fasterxml.jackson.databind.ObjectMapper());
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
    void recentCycles_appliesDefault55Threshold() {
        // Default recalibrated 70 -> 55 (2026-06-10): prod confidences cluster at 51-53,
        // so a 70 floor left the Smart Money Cycle panel empty.
        when(cycleRepo.findByInstrumentAndConfidenceGreaterThanEqualOrderByTimestampDesc(
                any(), any(Integer.class), any())).thenReturn(List.of());

        service.recentCycles(Instrument.MNQ, 20);

        verify(cycleRepo).findByInstrumentAndConfidenceGreaterThanEqualOrderByTimestampDesc(
                eq(Instrument.MNQ), eq(55), any(Pageable.class));
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

    // ─── Footprint profileJson parsing tolerance (diagonal-imbalance upgrade) ──

    private FootprintBarEntity barEntity(String profileJson) {
        return new FootprintBarEntity(Instrument.MNQ, "10m",
                Instant.parse("2026-05-02T13:00:00Z"), 21002.0, 60L, 20L, 40L, profileJson);
    }

    @Test
    void toBar_oldRowWithoutDiagonalFields_parsesWithFlagsFalse() {
        // Pre-upgrade JSON shape: no diagonal flags, no zones — must not break.
        String legacyJson = "["
                + "{\"price\":21000.0,\"buyVolume\":10,\"sellVolume\":5,\"delta\":5,\"imbalance\":false},"
                + "{\"price\":21002.0,\"buyVolume\":50,\"sellVolume\":15,\"delta\":35,\"imbalance\":true}"
                + "]";

        FootprintBar bar = service.toBar(barEntity(legacyJson));

        assertThat(bar.levels()).hasSize(2);
        assertThat(bar.levels().get(21002.0).imbalance()).isTrue();
        assertThat(bar.levels().get(21002.0).diagonalBuyImbalance()).isFalse();
        assertThat(bar.levels().get(21002.0).diagonalSellImbalance()).isFalse();
        assertThat(bar.stackedBuyZones()).isEmpty();
        assertThat(bar.stackedSellZones()).isEmpty();
        // unfinished flags only need volumes — computable for old rows too
        assertThat(bar.unfinishedHigh()).isTrue();  // top bucket 21002: both sides traded
        assertThat(bar.unfinishedLow()).isTrue();   // bottom bucket 21000: both sides traded
        assertThat(bar.totalDelta()).isEqualTo(40);
    }

    @Test
    void toBar_newRowWithDiagonalFields_rebuildsStackedZones() {
        // Post-upgrade JSON (2.0-point MNQ buckets): three consecutive diagonal buy flags
        String json = "["
                + "{\"price\":21000.0,\"buyVolume\":0,\"sellVolume\":5,\"delta\":-5,\"imbalance\":true,"
                + "\"diagonalBuyImbalance\":false,\"diagonalSellImbalance\":false},"
                + "{\"price\":21002.0,\"buyVolume\":30,\"sellVolume\":5,\"delta\":25,\"imbalance\":true,"
                + "\"diagonalBuyImbalance\":true,\"diagonalSellImbalance\":false},"
                + "{\"price\":21004.0,\"buyVolume\":30,\"sellVolume\":5,\"delta\":25,\"imbalance\":true,"
                + "\"diagonalBuyImbalance\":true,\"diagonalSellImbalance\":false},"
                + "{\"price\":21006.0,\"buyVolume\":30,\"sellVolume\":0,\"delta\":30,\"imbalance\":true,"
                + "\"diagonalBuyImbalance\":true,\"diagonalSellImbalance\":false}"
                + "]";

        FootprintBar bar = service.toBar(barEntity(json));

        assertThat(bar.levels().get(21002.0).diagonalBuyImbalance()).isTrue();
        assertThat(bar.stackedBuyZones()).hasSize(1);
        assertThat(bar.stackedBuyZones().get(0).fromPrice()).isEqualTo(21002.0);
        assertThat(bar.stackedBuyZones().get(0).toPrice()).isEqualTo(21006.0);
        assertThat(bar.stackedBuyZones().get(0).buckets()).isEqualTo(3);
        assertThat(bar.stackedSellZones()).isEmpty();
    }

    @Test
    void toBar_malformedJson_keepsHeadlineMetrics() {
        FootprintBar bar = service.toBar(barEntity("{not-json"));

        assertThat(bar.levels()).isEmpty();
        assertThat(bar.stackedBuyZones()).isEmpty();
        assertThat(bar.unfinishedHigh()).isFalse();
        assertThat(bar.pocPrice()).isEqualTo(21002.0);
        assertThat(bar.totalBuyVolume()).isEqualTo(60);
    }

    @Test
    void toBar_nullProfileJson_isTolerated() {
        FootprintBar bar = service.toBar(barEntity(null));

        assertThat(bar.levels()).isEmpty();
        assertThat(bar.totalDelta()).isEqualTo(40);
    }
}

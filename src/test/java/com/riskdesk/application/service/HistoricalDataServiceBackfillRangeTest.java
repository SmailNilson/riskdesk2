package com.riskdesk.application.service;

import com.riskdesk.application.service.HistoricalDataService.BackfillJob;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.marketdata.port.HistoricalDataProvider;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the deep range backfill path on {@link HistoricalDataService}.
 *
 * <p>Synchronous mode ({@code async=false}) is used throughout for determinism. The focus is the
 * idempotency contract (only-new bars persisted) and the validation guards.</p>
 */
class HistoricalDataServiceBackfillRangeTest {

    private static final Instant FROM = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant TO   = Instant.parse("2026-03-01T00:03:00Z");

    private HistoricalDataService newService(HistoricalDataProvider provider,
                                             CandleRepositoryPort candlePort,
                                             ActiveContractRegistry registry,
                                             boolean enabled) {
        HistoricalDataService service = new HistoricalDataService(provider, candlePort, registry);
        ReflectionTestUtils.setField(service, "enabled", enabled);
        ReflectionTestUtils.setField(service, "backfillRangeMaxDays", 120);
        return service;
    }

    @Test
    void backfillRange_savesOnlyTimestampsNotAlreadyPresent() {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        Candle c0 = candle("2026-03-01T00:00:00Z", "100");
        Candle c1 = candle("2026-03-01T00:01:00Z", "101");
        Candle c2 = candle("2026-03-01T00:02:00Z", "102");

        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        stubStreamRange(provider, FROM, TO, List.of(c0, c1, c2));
        when(registry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.of("202609"));
        // c1 already persisted in the window — must be skipped on save. The streaming path probes
        // existence per chunk (bounded by the chunk's own min/max), so match any window here.
        when(candlePort.findCandlesBetween(eq(Instrument.MNQ), eq("1m"), any(), any())).thenReturn(List.of(c1));
        when(candlePort.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, false);

        assertEquals("DONE", job.state());
        assertEquals(3, job.fetched());
        assertEquals(1, job.existing());
        assertEquals(2, job.saved());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Candle>> captor = ArgumentCaptor.forClass(List.class);
        verify(candlePort, times(1)).saveAll(captor.capture());
        List<Instant> savedTs = captor.getValue().stream().map(Candle::getTimestamp).toList();
        assertEquals(List.of(c0.getTimestamp(), c2.getTimestamp()), savedTs);
    }

    @Test
    void backfillRange_isNoOpWhenEverythingAlreadyPresent() {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        Candle c0 = candle("2026-03-01T00:00:00Z", "100");
        Candle c1 = candle("2026-03-01T00:01:00Z", "101");

        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        stubStreamRange(provider, FROM, TO, List.of(c0, c1));
        when(registry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.empty());
        when(candlePort.findCandlesBetween(eq(Instrument.MNQ), eq("1m"), any(), any())).thenReturn(List.of(c0, c1));

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, false);

        assertEquals("DONE", job.state());
        assertEquals(0, job.saved());
        verify(candlePort, never()).saveAll(anyList());
    }

    @Test
    void backfillRange_disabledReturnsDisabledAndTouchesNothing() {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        HistoricalDataService service = newService(provider, candlePort, registry, false);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, false);

        assertEquals("DISABLED", job.state());
        verifyNoInteractions(provider);
        verifyNoInteractions(candlePort);
    }

    @Test
    void backfillRange_rejectsUnsupportedTimeframe() {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        when(provider.supports(Instrument.MNQ, "3m")).thenReturn(false);

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "3m", FROM, TO, false);

        assertEquals("REJECTED", job.state());
        assertTrue(job.message().contains("not supported"));
        verify(provider, never()).fetchHistoryRange(any(), any(), any(), any());
        verifyNoInteractions(candlePort);
    }

    @Test
    void backfillRange_rejectsInvertedWindow() {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m", TO, FROM, false);

        assertEquals("REJECTED", job.state());
        assertTrue(job.message().contains("before"));
        verify(provider, never()).fetchHistoryRange(any(), any(), any(), any());
    }

    @Test
    void backfillRange_rejectsWindowExceedingMaxDays() {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        Instant wideFrom = Instant.parse("2025-01-01T00:00:00Z");
        Instant wideTo   = Instant.parse("2026-01-01T00:00:00Z"); // 365 days > 120

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m", wideFrom, wideTo, false);

        assertEquals("REJECTED", job.state());
        assertTrue(job.message().contains("exceeds the maximum"));
        verify(provider, never()).fetchHistoryRange(any(), any(), any(), any());
    }

    @Test
    void backfillStatus_reflectsLastJob() {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        stubStreamRange(provider, FROM, TO, List.of());
        when(registry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.empty());
        when(candlePort.findCandlesBetween(eq(Instrument.MNQ), eq("1m"), any(), any())).thenReturn(List.of());

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        assertTrue(service.backfillStatus(Instrument.MNQ, "1m").isEmpty());

        service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, false);

        Optional<BackfillJob> status = service.backfillStatus(Instrument.MNQ, "1m");
        assertTrue(status.isPresent());
        assertEquals("DONE", status.get().state());
    }

    @Test
    void backfillRange_continuousRoutesToTheContinuousProvider() {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        Candle c0 = candle("2026-03-01T00:00:00Z", "100");
        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        when(provider.fetchContinuousHistoryRange(eq(Instrument.MNQ), eq("1m"), eq(FROM), eq(TO), any()))
            .thenAnswer(inv -> {
                Consumer<List<Candle>> sink = inv.getArgument(4);
                sink.accept(List.of(c0));
                return 1;
            });
        when(registry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.empty());
        when(candlePort.findCandlesBetween(eq(Instrument.MNQ), eq("1m"), any(), any())).thenReturn(List.of());
        when(candlePort.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, false, true, false);

        assertEquals("DONE", job.state());
        assertEquals(1, job.saved());
        assertTrue(job.message().contains("continuous"));
        // The continuous path must NOT fall back to the front-month + expired-contract walk,
        // and continuous alone must never purge stored data.
        verify(provider, never()).fetchHistoryRange(any(), any(), any(), any(), any());
        verify(candlePort, never()).deleteRange(any(), any(), any(), any());
    }

    @Test
    void backfillRange_replacePurgesTheWindowBeforeRefilling() {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        Candle c0 = candle("2026-03-01T00:00:00Z", "100");
        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        stubStreamRange(provider, FROM, TO, List.of(c0));
        when(registry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.empty());
        when(candlePort.deleteRange(Instrument.MNQ, "1m", FROM, TO)).thenReturn(42);
        when(candlePort.findCandlesBetween(eq(Instrument.MNQ), eq("1m"), any(), any())).thenReturn(List.of());
        when(candlePort.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, false, false, true);

        assertEquals("DONE", job.state());
        assertEquals(1, job.saved());
        assertTrue(job.message().contains("42 purged"));
        // Purge strictly before refill — otherwise the just-saved bars would be deleted again.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(candlePort);
        inOrder.verify(candlePort).deleteRange(Instrument.MNQ, "1m", FROM, TO);
        inOrder.verify(candlePort).saveAll(anyList());
    }

    @Test
    void backfillRange_replaceWithNoFetchedData_skipsThePurgeEntirely() {
        // A dead gateway / no-op provider returns 0 chunks without throwing. The lazy purge must
        // then never fire — replace must not be able to empty a window it cannot refill.
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        stubStreamRange(provider, FROM, TO, List.of());

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, false, false, true);

        assertEquals("DONE", job.state());
        assertEquals(0, job.fetched());
        assertTrue(job.message().contains("Purge skipped"), job.message());
        verify(candlePort, never()).deleteRange(any(), any(), any(), any());
    }

    @Test
    void backfillRange_replaceWithShortfallCoverage_reportsPartial() {
        // The purge covers [from, to] but the refill walk dies early: earliest fetched bar sits
        // far above `from`. The job must say PARTIAL, not DONE — the operator has to re-run.
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        Instant wideFrom = Instant.parse("2026-03-01T00:00:00Z");
        Instant wideTo   = Instant.parse("2026-03-20T00:00:00Z");
        Candle late = candle("2026-03-15T00:00:00Z", "100"); // 14 days above wideFrom > 3-day tolerance

        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        stubStreamRange(provider, wideFrom, wideTo, List.of(late));
        when(registry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.empty());
        when(candlePort.deleteRange(Instrument.MNQ, "1m", wideFrom, wideTo)).thenReturn(7);
        when(candlePort.findCandlesBetween(eq(Instrument.MNQ), eq("1m"), any(), any())).thenReturn(List.of());
        when(candlePort.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m", wideFrom, wideTo, false, false, true);

        assertEquals("PARTIAL", job.state());
        assertTrue(job.message().contains("re-run"), job.message());
        assertEquals(1, job.saved());
    }

    @Test
    void backfillRange_continuousEndingTooRecently_isRejectedBeforeAnyWork() {
        // CONT rows in the live front-month window would blind active-month consumers — a
        // continuous window must end in the past (continuous-min-age-days guard).
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        Instant recentTo = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        ReflectionTestUtils.setField(service, "continuousMinAgeDays", 7);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m",
            recentTo.minus(10, java.time.temporal.ChronoUnit.DAYS), recentTo, false, true, false);

        assertEquals("REJECTED", job.state());
        assertTrue(job.message().contains("historical windows"), job.message());
        verifyNoInteractions(candlePort);
        verify(provider, never()).fetchContinuousHistoryRange(any(), any(), any(), any(), any());
    }

    @Test
    void backfillRange_inFlightJob_coalescesIdenticalRequest_butRejectsDifferentOne() throws Exception {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        when(provider.fetchHistoryRange(eq(Instrument.MNQ), eq("1m"), eq(FROM), eq(TO), any()))
            .thenAnswer(inv -> {
                entered.countDown();
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return 0;
            });

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob first = service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, true);
        assertEquals("RUNNING", first.state());
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "job never started");

        // Identical request → coalesced into the in-flight job.
        BackfillJob same = service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, true);
        assertEquals("RUNNING", same.state());

        // Different request (replace+continuous re-source) must NOT be silently swallowed.
        BackfillJob different = service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, true, true, true);
        assertEquals("REJECTED", different.state());
        assertTrue(different.message().contains("already running"), different.message());

        release.countDown();
        // Drain: wait for the in-flight job to finish so the executor is idle for other tests.
        for (int i = 0; i < 100 && service.backfillStatus(Instrument.MNQ, "1m").map(BackfillJob::running).orElse(false); i++) {
            Thread.sleep(20);
        }
        assertEquals("DONE", service.backfillStatus(Instrument.MNQ, "1m").map(BackfillJob::state).orElse("?"));
    }

    @Test
    void backfillRange_legacyOverloadNeverPurgesNorUsesContinuous() {
        HistoricalDataProvider provider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry registry = mock(ActiveContractRegistry.class);

        when(provider.supports(Instrument.MNQ, "1m")).thenReturn(true);
        stubStreamRange(provider, FROM, TO, List.of());
        when(registry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.empty());
        when(candlePort.findCandlesBetween(eq(Instrument.MNQ), eq("1m"), any(), any())).thenReturn(List.of());

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        BackfillJob job = service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, false);

        assertEquals("DONE", job.state());
        verify(candlePort, never()).deleteRange(any(), any(), any(), any());
        verify(provider, never()).fetchContinuousHistoryRange(any(), any(), any(), any(), any());
    }

    private static Candle candle(String ts, String price) {
        BigDecimal p = new BigDecimal(price);
        return new Candle(Instrument.MNQ, "1m", Instant.parse(ts), p, p, p, p, 1L);
    }

    /** Stubs the streaming range overload to feed {@code chunk} into the sink (one chunk). */
    @SuppressWarnings("unchecked")
    private static void stubStreamRange(HistoricalDataProvider provider, Instant from, Instant to, List<Candle> chunk) {
        when(provider.fetchHistoryRange(eq(Instrument.MNQ), eq("1m"), eq(from), eq(to), any()))
            .thenAnswer(inv -> {
                Consumer<List<Candle>> sink = inv.getArgument(4);
                if (!chunk.isEmpty()) sink.accept(chunk);
                return chunk.size();
            });
    }
}

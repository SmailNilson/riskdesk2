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
        when(provider.fetchHistoryRange(Instrument.MNQ, "1m", FROM, TO)).thenReturn(List.of(c0, c1, c2));
        when(registry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.of("202609"));
        // c1 already persisted in the window — must be skipped on save.
        when(candlePort.findCandlesBetween(Instrument.MNQ, "1m", FROM, TO)).thenReturn(List.of(c1));
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
        when(provider.fetchHistoryRange(Instrument.MNQ, "1m", FROM, TO)).thenReturn(List.of(c0, c1));
        when(registry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.empty());
        when(candlePort.findCandlesBetween(Instrument.MNQ, "1m", FROM, TO)).thenReturn(List.of(c0, c1));

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
        when(provider.fetchHistoryRange(eq(Instrument.MNQ), eq("1m"), any(), any())).thenReturn(List.of());
        when(registry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.empty());
        when(candlePort.findCandlesBetween(eq(Instrument.MNQ), eq("1m"), any(), any())).thenReturn(List.of());

        HistoricalDataService service = newService(provider, candlePort, registry, true);
        assertTrue(service.backfillStatus(Instrument.MNQ, "1m").isEmpty());

        service.startBackfillRange(Instrument.MNQ, "1m", FROM, TO, false);

        Optional<BackfillJob> status = service.backfillStatus(Instrument.MNQ, "1m");
        assertTrue(status.isPresent());
        assertEquals("DONE", status.get().state());
    }

    private static Candle candle(String ts, String price) {
        BigDecimal p = new BigDecimal(price);
        return new Candle(Instrument.MNQ, "1m", Instant.parse(ts), p, p, p, p, 1L);
    }
}

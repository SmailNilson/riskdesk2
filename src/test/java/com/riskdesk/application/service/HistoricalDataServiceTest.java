package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.marketdata.port.HistoricalDataProvider;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalDataServiceTest {

    @Test
    void refreshInstrumentContext_respectsCooldownPerInstrumentAndTimeframe() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        when(historicalProvider.supports(Instrument.MCL, "10m")).thenReturn(true);
        when(candlePort.findLatestTimestamp(Instrument.MCL, "10m"))
            .thenReturn(Optional.of(Instant.parse("2026-03-30T09:50:00Z")));
        when(historicalProvider.fetchHistory(eq(Instrument.MCL), eq("10m"), anyInt())).thenReturn(List.of(
                candle(Instrument.MCL, "10m", "2026-03-30T10:00:00Z", "62.40")
        ));
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(Optional.of("202606"));
        when(candlePort.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        HistoricalDataService service = new HistoricalDataService(historicalProvider, candlePort, contractRegistry);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "mentorRefreshTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "mentorRefreshCooldownMs", 60_000L);
        ReflectionTestUtils.setField(service, "backfillDays10m", 1);
        ReflectionTestUtils.setField(service, "backfillDays1h", 1);

        Map<String, Integer> first = service.refreshInstrumentContext(Instrument.MCL, List.of("10m"));
        Map<String, Integer> second = service.refreshInstrumentContext(Instrument.MCL, List.of("10m"));

        assertEquals(1, first.get("10m"));
        assertEquals(0, second.get("10m"));
        verify(historicalProvider, times(1)).fetchHistory(eq(Instrument.MCL), eq("10m"), anyInt());
        // Gap-fill: no DELETE, only saveAll
        verify(candlePort, never()).deleteByInstrumentAndTimeframe(any(), any());
        verify(candlePort, times(1)).saveAll(anyList());
    }

    @Test
    void refreshInstrumentContext_gapFillUsesHighWaterMark() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        Instant hwm = Instant.parse("2026-04-08T14:00:00Z");
        when(historicalProvider.supports(Instrument.MGC, "1h")).thenReturn(true);
        when(candlePort.findLatestTimestamp(Instrument.MGC, "1h")).thenReturn(Optional.of(hwm));

        // Return candles: one before HWM (should be filtered), one after (should be saved)
        when(historicalProvider.fetchHistory(eq(Instrument.MGC), eq("1h"), anyInt())).thenReturn(List.of(
            candle(Instrument.MGC, "1h", "2026-04-08T14:00:00Z", "2350.00"),  // at HWM — filtered
            candle(Instrument.MGC, "1h", "2026-04-08T15:00:00Z", "2355.00")   // after HWM — saved
        ));
        when(contractRegistry.getContractMonth(Instrument.MGC)).thenReturn(Optional.of("202606"));
        when(candlePort.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        HistoricalDataService service = new HistoricalDataService(historicalProvider, candlePort, contractRegistry);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "mentorRefreshTimeoutMs", 2000L);
        ReflectionTestUtils.setField(service, "mentorRefreshCooldownMs", 0L);
        ReflectionTestUtils.setField(service, "backfillDays1h", 1);

        Map<String, Integer> result = service.refreshInstrumentContext(Instrument.MGC, List.of("1h"));

        assertEquals(1, result.get("1h"), "Only the candle AFTER HWM should be saved");
        verify(candlePort, never()).deleteByInstrumentAndTimeframe(any(), any());
        verify(candlePort, times(1)).findLatestTimestamp(Instrument.MGC, "1h");
    }

    @Test
    void refreshInstrumentContext_fullBackfillWhenNoHwm() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        when(historicalProvider.supports(Instrument.MCL, "5m")).thenReturn(true);
        when(candlePort.findLatestTimestamp(Instrument.MCL, "5m")).thenReturn(Optional.empty());
        when(historicalProvider.fetchHistory(eq(Instrument.MCL), eq("5m"), anyInt())).thenReturn(List.of(
            candle(Instrument.MCL, "5m", "2026-04-08T10:00:00Z", "62.00"),
            candle(Instrument.MCL, "5m", "2026-04-08T10:05:00Z", "62.10")
        ));
        when(contractRegistry.getContractMonth(Instrument.MCL)).thenReturn(Optional.of("202606"));
        when(candlePort.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        HistoricalDataService service = new HistoricalDataService(historicalProvider, candlePort, contractRegistry);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "mentorRefreshTimeoutMs", 2000L);
        ReflectionTestUtils.setField(service, "mentorRefreshCooldownMs", 0L);
        ReflectionTestUtils.setField(service, "backfillDays5m", 1);

        Map<String, Integer> result = service.refreshInstrumentContext(Instrument.MCL, List.of("5m"));

        assertEquals(2, result.get("5m"), "All candles should be saved when no HWM exists");
        verify(candlePort, never()).deleteByInstrumentAndTimeframe(any(), any());
    }

    @Test
    void deepBackfillTimeframe_purgesExistingAndRefetchesFullWindow() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        when(historicalProvider.supports(Instrument.MNQ, "10m")).thenReturn(true);
        // existing pre-purge data
        when(candlePort.findCandles(eq(Instrument.MNQ), eq("10m"), any())).thenReturn(List.of(
                candle(Instrument.MNQ, "10m", "2026-05-26T01:50:00Z", "29810.75"),
                candle(Instrument.MNQ, "10m", "2026-05-26T02:00:00Z", "29812.00")
        ));
        when(historicalProvider.fetchHistory(eq(Instrument.MNQ), eq("10m"), anyInt())).thenReturn(List.of(
                candle(Instrument.MNQ, "10m", "2026-02-26T01:50:00Z", "29000.00"),
                candle(Instrument.MNQ, "10m", "2026-02-26T02:00:00Z", "29010.00"),
                candle(Instrument.MNQ, "10m", "2026-02-26T02:10:00Z", "29020.00")
        ));
        when(contractRegistry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.of("202606"));
        when(candlePort.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        HistoricalDataService service = new HistoricalDataService(historicalProvider, candlePort, contractRegistry);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "backfillDays10m", 90);

        Map<String, Object> result = service.deepBackfillTimeframe(Instrument.MNQ, "10m");

        assertEquals("ok", result.get("status"));
        assertEquals("MNQ", result.get("instrument"));
        assertEquals("10m", result.get("timeframe"));
        assertEquals(2, result.get("purged"), "Should report purged count");
        assertEquals(3, result.get("fetched"), "Should report fetched count");
        verify(candlePort, times(1)).deleteByInstrumentAndTimeframe(Instrument.MNQ, "10m");
        verify(historicalProvider, times(1)).fetchHistory(eq(Instrument.MNQ), eq("10m"), anyInt());
        verify(candlePort, times(1)).saveAll(anyList());
    }

    @Test
    void deepBackfillTimeframe_rejectsUnsupportedPair() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        when(historicalProvider.supports(Instrument.MNQ, "1m")).thenReturn(false);

        HistoricalDataService service = new HistoricalDataService(historicalProvider, candlePort, contractRegistry);
        ReflectionTestUtils.setField(service, "enabled", true);

        Map<String, Object> result = service.deepBackfillTimeframe(Instrument.MNQ, "1m");

        assertEquals("error", result.get("status"));
        assertTrue(((String) result.get("message")).contains("does not support"));
        verify(candlePort, never()).deleteByInstrumentAndTimeframe(any(), any());
        verify(historicalProvider, never()).fetchHistory(any(), any(), anyInt());
    }

    @Test
    void deepBackfillTimeframe_returnsDisabledWhenServiceDisabled() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        HistoricalDataService service = new HistoricalDataService(historicalProvider, candlePort, contractRegistry);
        ReflectionTestUtils.setField(service, "enabled", false);

        Map<String, Object> result = service.deepBackfillTimeframe(Instrument.MNQ, "10m");

        assertEquals("disabled", result.get("status"));
        verify(candlePort, never()).deleteByInstrumentAndTimeframe(any(), any());
        verify(historicalProvider, never()).fetchHistory(any(), any(), anyInt());
    }

    @Test
    void deepBackfillTimeframe_preservesExistingWhenFetchReturnsEmpty() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        when(historicalProvider.supports(Instrument.MNQ, "10m")).thenReturn(true);
        // Empty fetch (e.g. IBKR session/contract issue silently returning [])
        when(historicalProvider.fetchHistory(eq(Instrument.MNQ), eq("10m"), anyInt())).thenReturn(List.of());
        when(contractRegistry.getContractMonth(Instrument.MNQ)).thenReturn(Optional.empty());

        HistoricalDataService service = new HistoricalDataService(historicalProvider, candlePort, contractRegistry);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "backfillDays10m", 90);

        Map<String, Object> result = service.deepBackfillTimeframe(Instrument.MNQ, "10m");

        // Existing data must be preserved — never call delete or save when fetch is empty.
        assertEquals("error", result.get("status"));
        assertEquals(0, result.get("purged"));
        assertEquals(0, result.get("fetched"));
        assertTrue(((String) result.get("message")).contains("returned no candles"));
        verify(candlePort, never()).deleteByInstrumentAndTimeframe(any(), any());
        verify(candlePort, never()).saveAll(anyList());
        verify(candlePort, never()).findCandles(any(), any(), any());
    }

    @Test
    void deepBackfillTimeframe_preservesExistingWhenFetchThrows() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        when(historicalProvider.supports(Instrument.MNQ, "10m")).thenReturn(true);
        when(historicalProvider.fetchHistory(eq(Instrument.MNQ), eq("10m"), anyInt()))
                .thenThrow(new RuntimeException("IBKR pacing violation"));

        HistoricalDataService service = new HistoricalDataService(historicalProvider, candlePort, contractRegistry);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "backfillDays10m", 90);

        Map<String, Object> result = service.deepBackfillTimeframe(Instrument.MNQ, "10m");

        // Existing data must be preserved on fetch exception.
        assertEquals("error", result.get("status"));
        assertEquals(0, result.get("purged"));
        assertEquals(0, result.get("fetched"));
        assertTrue(((String) result.get("message")).contains("existing data preserved"));
        verify(candlePort, never()).deleteByInstrumentAndTimeframe(any(), any());
        verify(candlePort, never()).saveAll(anyList());
        verify(candlePort, never()).findCandles(any(), any(), any());
    }

    private static Candle candle(Instrument instrument, String timeframe, String timestamp, String close) {
        BigDecimal price = new BigDecimal(close);
        return new Candle(instrument, timeframe, Instant.parse(timestamp), price, price, price, price, 1L);
    }
}

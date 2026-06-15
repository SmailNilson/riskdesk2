package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.event.ContractRolloverEvent;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
    void onContractRollover_deepBackfillsEveryTimeframeOnTheNewContract() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);
        when(historicalProvider.supports(eq(Instrument.MNQ), anyString())).thenReturn(true);

        HistoricalDataService service = spy(new HistoricalDataService(historicalProvider, candlePort, contractRegistry));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "backfillRangeMaxDays", 200);
        ReflectionTestUtils.setField(service, "rolloverBackfillDays1m", 14);
        ReflectionTestUtils.setField(service, "rolloverBackfillDays5m", 60);
        ReflectionTestUtils.setField(service, "rolloverBackfillDays10m", 90);
        ReflectionTestUtils.setField(service, "rolloverBackfillDays1h", 200);
        ReflectionTestUtils.setField(service, "rolloverBackfillDays4h", 200);
        ReflectionTestUtils.setField(service, "rolloverBackfillDays1d", 200);
        // Stub the heavy dispatch so the test never touches IBKR or the async executor.
        doReturn(null).when(service).startBackfillRange(
            any(), anyString(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any());

        service.onContractRollover(new ContractRolloverEvent(Instrument.MNQ, "202606", "202609", Instant.now()));

        // Every supported timeframe is deep-backfilled (range), not forward gap-filled.
        for (String tf : List.of("1m", "5m", "10m", "1h", "4h", "1d")) {
            verify(service).startBackfillRange(
                eq(Instrument.MNQ), eq(tf), any(), any(), eq(true), eq(false), eq(false), isNull());
        }
        verify(historicalProvider, never()).fetchHistory(any(), any(), anyInt());
        verify(candlePort, never()).deleteByInstrumentAndTimeframe(any(), any());
    }

    @Test
    void onContractRollover_perTimeframeLookbackIsClampedToTheRangeCap() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);
        when(historicalProvider.supports(eq(Instrument.MNQ), eq("10m"))).thenReturn(true);

        HistoricalDataService service = spy(new HistoricalDataService(historicalProvider, candlePort, contractRegistry));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "backfillRangeMaxDays", 30); // tighter than the 90 below
        ReflectionTestUtils.setField(service, "rolloverBackfillDays10m", 90);
        doReturn(null).when(service).startBackfillRange(
            any(), anyString(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any());

        Instant before = Instant.now();
        service.onContractRollover(new ContractRolloverEvent(Instrument.MNQ, "202606", "202609", Instant.now()));

        var fromCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var toCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(service).startBackfillRange(eq(Instrument.MNQ), eq("10m"),
            fromCaptor.capture(), toCaptor.capture(), eq(true), eq(false), eq(false), isNull());
        long windowDays = java.time.Duration.between(fromCaptor.getValue(), toCaptor.getValue()).toDays();
        assertEquals(30L, windowDays, "lookback must be clamped to backfill-range-max-days");
        assertTrue(!toCaptor.getValue().isBefore(before), "window ends at ~now");
    }

    @Test
    void onContractRollover_doesNothingWhenHistoricalDisabled() {
        HistoricalDataProvider historicalProvider = mock(HistoricalDataProvider.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);

        HistoricalDataService service = spy(new HistoricalDataService(historicalProvider, candlePort, contractRegistry));
        ReflectionTestUtils.setField(service, "enabled", false);

        service.onContractRollover(new ContractRolloverEvent(Instrument.MNQ, "202606", "202609", Instant.now()));

        verify(service, never()).startBackfillRange(
            any(), anyString(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any());
        verify(historicalProvider, never()).supports(any(), any());
    }

    private static Candle candle(Instrument instrument, String timeframe, String timestamp, String close) {
        BigDecimal price = new BigDecimal(close);
        return new Candle(instrument, timeframe, Instant.parse(timestamp), price, price, price, price, 1L);
    }
}

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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
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
        when(historicalProvider.fetchHistory(Instrument.MCL, "10m", 500)).thenReturn(List.of(
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
        verify(historicalProvider, times(1)).fetchHistory(Instrument.MCL, "10m", 500);
        verify(candlePort, times(1)).deleteByInstrumentAndTimeframe(Instrument.MCL, "10m");
        verify(candlePort, times(1)).saveAll(anyList());
    }

    private static Candle candle(Instrument instrument, String timeframe, String timestamp, String close) {
        BigDecimal price = new BigDecimal(close);
        return new Candle(instrument, timeframe, Instant.parse(timestamp), price, price, price, price, 1L);
    }
}

package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ActiveContractCandleServiceTest {

    @Test
    void findRecentCandles_prefersActiveContractMonthWhenAvailable() {
        CandleRepositoryPort candleRepositoryPort = mock(CandleRepositoryPort.class);
        ActiveContractService activeContractService = mock(ActiveContractService.class);
        ActiveContractCandleService service = new ActiveContractCandleService(candleRepositoryPort, activeContractService);

        Candle aligned = new Candle();
        aligned.setInstrument(Instrument.MGC);
        aligned.setTimeframe("10m");
        aligned.setContractMonth("202606");

        when(activeContractService.describe(Instrument.MGC)).thenReturn(
            new ActiveContractService.ActiveContractDescriptor("MGC", "MGC 202606", "202606", "MGCM6", "MGCM6", "selected nearest tradable month 202606")
        );
        when(candleRepositoryPort.findRecentCandles(Instrument.MGC, "10m", "202606", 5)).thenReturn(List.of(aligned));

        List<Candle> result = service.findRecentCandles(Instrument.MGC, "10m", 5);

        assertThat(result).containsExactly(aligned);
        verify(candleRepositoryPort).findRecentCandles(Instrument.MGC, "10m", "202606", 5);
        verify(candleRepositoryPort, never()).findRecentCandles(Instrument.MGC, "10m", 5);
    }

    @Test
    void findCandles_fallsBackToLegacySeriesWhenContractSpecificRowsAreMissing() {
        CandleRepositoryPort candleRepositoryPort = mock(CandleRepositoryPort.class);
        ActiveContractService activeContractService = mock(ActiveContractService.class);
        ActiveContractCandleService service = new ActiveContractCandleService(candleRepositoryPort, activeContractService);
        Instant from = Instant.parse("2026-03-01T00:00:00Z");

        Candle legacy = new Candle();
        legacy.setInstrument(Instrument.MNQ);
        legacy.setTimeframe("1h");

        when(activeContractService.describe(Instrument.MNQ)).thenReturn(
            new ActiveContractService.ActiveContractDescriptor("MNQ", "MNQ 202609", "202609", "MNQU6", "MNQU6", "selected 202609 for stronger live liquidity")
        );
        when(candleRepositoryPort.findCandles(Instrument.MNQ, "1h", "202609", from)).thenReturn(List.of());
        when(candleRepositoryPort.findCandles(Instrument.MNQ, "1h", from)).thenReturn(List.of(legacy));

        List<Candle> result = service.findCandles(Instrument.MNQ, "1h", from);

        assertThat(result).containsExactly(legacy);
        verify(candleRepositoryPort).findCandles(Instrument.MNQ, "1h", "202609", from);
        verify(candleRepositoryPort).findCandles(Instrument.MNQ, "1h", from);
    }
}

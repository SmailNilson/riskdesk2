package com.riskdesk.application.service;

import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MentorIntermarketServiceTest {

    @Test
    void current_includesDxyChangeForDollarSensitiveInstrument() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<MarketDataService> marketDataServiceProvider = mock(ObjectProvider.class);
        CandleRepositoryPort candleRepositoryPort = mock(CandleRepositoryPort.class);
        MentorIntermarketService service = new MentorIntermarketService(marketDataServiceProvider, candleRepositoryPort);

        when(candleRepositoryPort.findRecentCandles(Instrument.DXY, "10m", 2)).thenReturn(List.of(
            candle("2026-03-26T00:00:00Z", "104.000"),
            candle("2026-03-25T23:50:00Z", "103.500")
        ));
        when(marketDataServiceProvider.getIfAvailable()).thenReturn(marketDataService);
        when(marketDataService.currentPrice(Instrument.DXY)).thenReturn(
            new MarketDataService.StoredPrice(new BigDecimal("104.250"), Instant.parse("2026-03-26T00:06:00Z"), "LIVE_PROVIDER")
        );

        MentorIntermarketSnapshot snapshot = service.current(Instrument.MGC);

        assertThat(snapshot.dxyPctChange()).isEqualTo(0.725);
        assertThat(snapshot.dxyTrend()).isEqualTo("BULLISH");
        assertThat(snapshot.metalsConvergenceStatus()).isEqualTo("DXY_AVAILABLE");
    }

    @Test
    void current_returnsUnavailableWhenNoDxyDataExists() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<MarketDataService> marketDataServiceProvider = mock(ObjectProvider.class);
        CandleRepositoryPort candleRepositoryPort = mock(CandleRepositoryPort.class);
        MentorIntermarketService service = new MentorIntermarketService(marketDataServiceProvider, candleRepositoryPort);

        when(candleRepositoryPort.findRecentCandles(Instrument.DXY, "10m", 2)).thenReturn(List.of());
        when(candleRepositoryPort.findRecentCandles(Instrument.DXY, "1h", 2)).thenReturn(List.of());
        when(marketDataServiceProvider.getIfAvailable()).thenReturn(marketDataService);

        MentorIntermarketSnapshot snapshot = service.current(Instrument.MNQ);

        assertThat(snapshot.dxyPctChange()).isNull();
        assertThat(snapshot.dxyTrend()).isEqualTo("UNAVAILABLE");
        assertThat(snapshot.metalsConvergenceStatus()).isEqualTo("UNAVAILABLE");
    }

    private static Candle candle(String timestamp, String close) {
        BigDecimal price = new BigDecimal(close);
        return new Candle(
            Instrument.DXY,
            "10m",
            Instant.parse(timestamp),
            price,
            price,
            price,
            price,
            1L
        );
    }
}

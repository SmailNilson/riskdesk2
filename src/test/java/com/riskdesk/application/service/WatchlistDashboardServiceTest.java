package com.riskdesk.application.service;

import com.riskdesk.domain.analysis.port.WatchlistCandleRepositoryPort;
import com.riskdesk.domain.marketdata.port.WatchlistInstrumentMarketDataPort;
import com.riskdesk.domain.model.IbkrWatchlist;
import com.riskdesk.domain.model.IbkrWatchlistInstrument;
import com.riskdesk.domain.trading.port.IbkrWatchlistRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchlistDashboardServiceTest {

    @Mock
    private IbkrWatchlistRepositoryPort watchlistRepositoryPort;

    @Mock
    private WatchlistCandleRepositoryPort watchlistCandleRepositoryPort;

    @Mock
    private WatchlistInstrumentMarketDataPort watchlistInstrumentMarketDataPort;

    @Mock
    private IndicatorService indicatorService;

    @Test
    void currentPrice_resolvesExactContractByConid() {
        IbkrWatchlistInstrument april = instrument(101L, "MGC", "MGC Apr28'26", "FUT");
        IbkrWatchlistInstrument june = instrument(202L, "MGC", "MGC Jun26'26", "FUT");
        when(watchlistRepositoryPort.findAll()).thenReturn(List.of(watchlist("A trade", april, june)));
        when(watchlistInstrumentMarketDataPort.fetchLatestPrice(june)).thenReturn(Optional.of(BigDecimal.valueOf(3350.5)));

        WatchlistDashboardService service = new WatchlistDashboardService(
            watchlistRepositoryPort,
            watchlistCandleRepositoryPort,
            watchlistInstrumentMarketDataPort,
            indicatorService
        );

        var result = service.currentPrice("conid:202");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().instrument()).isEqualTo("CONID:202");

        ArgumentCaptor<IbkrWatchlistInstrument> captor = ArgumentCaptor.forClass(IbkrWatchlistInstrument.class);
        verify(watchlistInstrumentMarketDataPort).fetchLatestPrice(captor.capture());
        assertThat(captor.getValue().getConid()).isEqualTo(202L);
        assertThat(captor.getValue().getLocalSymbol()).isEqualTo("MGC Jun26'26");
    }

    @Test
    void currentPrice_resolvesExactContractByLocalSymbolWhenConidMissing() {
        IbkrWatchlistInstrument cfd = instrument(null, "EUR", "United States dollar CFD", "CFD");
        when(watchlistRepositoryPort.findAll()).thenReturn(List.of(watchlist("Forex", cfd)));
        when(watchlistInstrumentMarketDataPort.fetchLatestPrice(cfd)).thenReturn(Optional.of(BigDecimal.valueOf(1.08)));

        WatchlistDashboardService service = new WatchlistDashboardService(
            watchlistRepositoryPort,
            watchlistCandleRepositoryPort,
            watchlistInstrumentMarketDataPort,
            indicatorService
        );

        var result = service.currentPrice("local:United States dollar CFD");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().instrument()).isEqualTo("LOCAL:UNITED STATES DOLLAR CFD");
    }

    private IbkrWatchlist watchlist(String name, IbkrWatchlistInstrument... instruments) {
        IbkrWatchlist watchlist = new IbkrWatchlist();
        watchlist.setWatchlistId(name.toLowerCase().replace(' ', '-'));
        watchlist.setName(name);
        watchlist.setReadOnly(false);
        watchlist.setImportedAt(Instant.parse("2026-03-27T00:00:00Z"));
        watchlist.setInstruments(List.of(instruments));
        return watchlist;
    }

    private IbkrWatchlistInstrument instrument(Long conid, String symbol, String localSymbol, String assetClass) {
        IbkrWatchlistInstrument instrument = new IbkrWatchlistInstrument();
        instrument.setConid(conid);
        instrument.setSymbol(symbol);
        instrument.setLocalSymbol(localSymbol);
        instrument.setAssetClass(assetClass);
        instrument.setInstrumentCode(symbol);
        return instrument;
    }
}

package com.riskdesk.application.service;

import com.riskdesk.domain.model.IbkrWatchlist;
import com.riskdesk.domain.model.IbkrWatchlistInstrument;
import com.riskdesk.domain.trading.port.IbkrWatchlistRepositoryPort;
import com.riskdesk.domain.trading.port.IbkrWatchlistSourcePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IbkrWatchlistServiceTest {

    @Mock
    private IbkrWatchlistRepositoryPort repository;

    @Mock
    private IbkrWatchlistSourcePort source;

    @Test
    void importUserWatchlists_replacesStoredSnapshotAndMapsViews() {
        IbkrWatchlist imported = watchlist("wl-1", "A trade", "MNQ", "MNQ");
        when(source.fetchUserWatchlists()).thenReturn(List.of(imported));
        when(repository.replaceAll(List.of(imported))).thenReturn(List.of(imported));

        IbkrWatchlistService service = new IbkrWatchlistService(repository, source);

        var result = service.importUserWatchlists();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("wl-1");
        assertThat(result.get(0).instruments()).hasSize(1);
        assertThat(result.get(0).instruments().get(0).instrumentCode()).isEqualTo("MNQ");
        verify(source).fetchUserWatchlists();
        verify(repository).replaceAll(List.of(imported));
    }

    @Test
    void getStoredWatchlists_sortsByName() {
        IbkrWatchlist forex = watchlist("2", "Forex", "MCL", "MCL");
        IbkrWatchlist aTrade = watchlist("1", "A trade", "MGC", "MGC");
        when(repository.findAll()).thenReturn(List.of(forex, aTrade));

        IbkrWatchlistService service = new IbkrWatchlistService(repository, source);

        var result = service.getStoredWatchlists();

        assertThat(result).extracting("name").containsExactly("A trade", "Forex");
    }

    @Test
    void importUserWatchlists_keepsOnlyATradeAndForex() {
        IbkrWatchlist aTrade = watchlist("109", "A trade", "JPY", "JPY");
        IbkrWatchlist forex = watchlist("108", "Forex", "E6", "E6");
        when(source.fetchUserWatchlists()).thenReturn(List.of(
            watchlist("1", "Favorites", "MGC", "MGC"),
            aTrade,
            watchlist("2", "Watchlist", "NG", "NG"),
            forex
        ));
        when(repository.replaceAll(List.of(aTrade, forex))).thenReturn(List.of(aTrade, forex));

        IbkrWatchlistService service = new IbkrWatchlistService(repository, source);

        var result = service.importUserWatchlists();

        assertThat(result).extracting("name").containsExactly("A trade", "Forex");
        verify(repository).replaceAll(List.of(aTrade, forex));
    }

    @Test
    void getStoredWatchlistsOrBootstrap_returnsRetainedStoredWatchlistsWithoutBootstrap() {
        IbkrWatchlist aTrade = watchlist("109", "A trade", "JPY", "JPY");
        IbkrWatchlist forex = watchlist("108", "Forex", "E6", "E6");
        when(repository.findAll()).thenReturn(List.of(
            watchlist("1", "Favorites", "MGC", "MGC"),
            forex,
            aTrade
        ));

        IbkrWatchlistService service = new IbkrWatchlistService(repository, source);

        var result = service.getStoredWatchlistsOrBootstrap();

        assertThat(result).extracting("name").containsExactly("A trade", "Forex");
        verifyNoInteractions(source);
    }

    private IbkrWatchlist watchlist(String id, String name, String symbol, String instrumentCode) {
        IbkrWatchlistInstrument instrument = new IbkrWatchlistInstrument();
        instrument.setPositionIndex(0);
        instrument.setConid(123L);
        instrument.setSymbol(symbol);
        instrument.setLocalSymbol(symbol + " JUN26");
        instrument.setName(symbol + " contract");
        instrument.setAssetClass("FUT");
        instrument.setInstrumentCode(instrumentCode);

        IbkrWatchlist watchlist = new IbkrWatchlist();
        watchlist.setWatchlistId(id);
        watchlist.setName(name);
        watchlist.setReadOnly(false);
        watchlist.setImportedAt(Instant.parse("2026-03-27T00:00:00Z"));
        watchlist.setInstruments(List.of(instrument));
        return watchlist;
    }
}

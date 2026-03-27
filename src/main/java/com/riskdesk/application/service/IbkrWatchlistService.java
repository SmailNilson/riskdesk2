package com.riskdesk.application.service;

import com.riskdesk.application.dto.IbkrWatchlistInstrumentView;
import com.riskdesk.application.dto.IbkrWatchlistView;
import com.riskdesk.domain.model.IbkrWatchlist;
import com.riskdesk.domain.model.IbkrWatchlistInstrument;
import com.riskdesk.domain.trading.port.IbkrWatchlistRepositoryPort;
import com.riskdesk.domain.trading.port.IbkrWatchlistSourcePort;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class IbkrWatchlistService {

    private static final Set<String> RETAINED_WATCHLIST_NAMES = Set.of("a trade", "forex");

    private final IbkrWatchlistRepositoryPort repository;
    private final IbkrWatchlistSourcePort source;

    public IbkrWatchlistService(IbkrWatchlistRepositoryPort repository,
                                IbkrWatchlistSourcePort source) {
        this.repository = repository;
        this.source = source;
    }

    public List<IbkrWatchlistView> getStoredWatchlists() {
        return toViews(filterRetainedWatchlists(repository.findAll()));
    }

    public List<IbkrWatchlistView> getStoredWatchlistsOrBootstrap() {
        List<IbkrWatchlist> stored = filterRetainedWatchlists(repository.findAll());
        if (!stored.isEmpty()) {
            return toViews(stored);
        }
        return importUserWatchlists();
    }

    public List<IbkrWatchlistView> importUserWatchlists() {
        return toViews(repository.replaceAll(filterRetainedWatchlists(source.fetchUserWatchlists())));
    }

    private List<IbkrWatchlist> filterRetainedWatchlists(List<IbkrWatchlist> watchlists) {
        return watchlists.stream()
            .filter(this::isRetainedWatchlist)
            .toList();
    }

    private boolean isRetainedWatchlist(IbkrWatchlist watchlist) {
        String name = watchlist.getName();
        if (name == null) {
            return false;
        }
        return RETAINED_WATCHLIST_NAMES.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    private List<IbkrWatchlistView> toViews(List<IbkrWatchlist> watchlists) {
        return watchlists.stream()
            .sorted(Comparator.comparing(IbkrWatchlist::getName, String.CASE_INSENSITIVE_ORDER))
            .map(this::toView)
            .toList();
    }

    private IbkrWatchlistView toView(IbkrWatchlist watchlist) {
        return new IbkrWatchlistView(
            watchlist.getWatchlistId(),
            watchlist.getName(),
            watchlist.isReadOnly(),
            watchlist.getImportedAt(),
            watchlist.getInstruments().stream()
                .sorted(Comparator.comparingInt(IbkrWatchlistInstrument::getPositionIndex))
                .map(this::toView)
                .toList()
        );
    }

    private IbkrWatchlistInstrumentView toView(IbkrWatchlistInstrument instrument) {
        return new IbkrWatchlistInstrumentView(
            instrument.getConid() == null ? 0L : instrument.getConid(),
            instrument.getSymbol(),
            instrument.getLocalSymbol(),
            instrument.getName(),
            instrument.getAssetClass(),
            instrument.getInstrumentCode()
        );
    }
}

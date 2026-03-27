package com.riskdesk.domain.trading.port;

import com.riskdesk.domain.model.IbkrWatchlist;

import java.util.List;

public interface IbkrWatchlistRepositoryPort {

    List<IbkrWatchlist> findAll();

    List<IbkrWatchlist> replaceAll(List<IbkrWatchlist> watchlists);
}

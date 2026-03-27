package com.riskdesk.domain.trading.port;

import com.riskdesk.domain.model.IbkrWatchlist;

import java.util.List;

public interface IbkrWatchlistSourcePort {

    List<IbkrWatchlist> fetchUserWatchlists();
}

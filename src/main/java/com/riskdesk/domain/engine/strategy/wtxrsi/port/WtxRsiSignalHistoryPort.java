package com.riskdesk.domain.engine.strategy.wtxrsi.port;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;

import java.util.List;

/**
 * Persistence port for the WTX+RSI signal history (append-only audit log).
 */
public interface WtxRsiSignalHistoryPort {

    void save(WtxRsiSignalRecord record);

    /** Newest-first. */
    List<WtxRsiSignalRecord> findRecent(String instrument, int limit);

    /** Newest-first, filtered by timeframe. */
    List<WtxRsiSignalRecord> findRecent(String instrument, String timeframe, int limit);
}

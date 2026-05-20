package com.riskdesk.domain.engine.strategy.wtx.port;

import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;

import java.util.List;

public interface WtxSignalHistoryPort {
    void save(WtxSignal signal);
    List<WtxSignal> findRecent(String instrument, int limit);
    List<WtxSignal> findRecent(String instrument, String timeframe, int limit);
}

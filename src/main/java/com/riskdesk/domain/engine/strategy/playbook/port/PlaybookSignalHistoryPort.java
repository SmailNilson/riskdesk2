package com.riskdesk.domain.engine.strategy.playbook.port;

import com.riskdesk.domain.engine.strategy.playbook.PlaybookSignal;

import java.util.List;

public interface PlaybookSignalHistoryPort {
    void save(PlaybookSignal signal);
    List<PlaybookSignal> findRecent(String instrument, int limit);
    List<PlaybookSignal> findRecent(String instrument, String timeframe, int limit);
}

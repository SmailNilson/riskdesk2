package com.riskdesk.domain.engine.strategy.playbook.port;

import com.riskdesk.domain.engine.strategy.playbook.PlaybookStrategyState;

import java.util.Optional;

public interface PlaybookStrategyStatePort {
    Optional<PlaybookStrategyState> load(String instrument, String timeframe);
    void save(PlaybookStrategyState state);
}

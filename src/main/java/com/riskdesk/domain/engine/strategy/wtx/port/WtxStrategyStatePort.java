package com.riskdesk.domain.engine.strategy.wtx.port;

import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;

import java.util.Optional;

public interface WtxStrategyStatePort {
    Optional<WtxStrategyState> load(String instrument, String timeframe);
    void save(WtxStrategyState state);
}

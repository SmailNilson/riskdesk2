package com.riskdesk.domain.engine.strategy.wtxrsi.port;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link WtxRsiStrategyState}. Implementations live in
 * {@code infrastructure/persistence/}. The domain only depends on this
 * interface so unit tests can inject in-memory stand-ins.
 */
public interface WtxRsiStrategyStatePort {

    Optional<WtxRsiStrategyState> load(String instrument, String timeframe);

    void save(WtxRsiStrategyState state);

    /** Used by the close-all-positions scheduler. */
    List<WtxRsiStrategyState> findAllOpen();
}

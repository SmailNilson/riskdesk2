package com.riskdesk.domain.trading.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Position;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for position persistence.
 * Application services depend on this interface rather than on Spring Data repositories directly,
 * enabling hexagonal architecture and testability.
 */
public interface PositionRepositoryPort {

    Position save(Position position);

    Optional<Position> findById(Long id);

    List<Position> findOpenPositions();

    List<Position> findOpenPositionsByInstrument(Instrument instrument);

    List<Position> findClosedPositions();

    BigDecimal totalUnrealizedPnL();

    BigDecimal todayRealizedPnL();

    long openPositionCount();
}

package com.riskdesk.domain.simulation.port;

import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for persisting {@link TradeSimulation} aggregates.
 *
 * <p>Phase 1a foundation. No existing code path writes to this port yet; a
 * follow-up PR (P1b) will wire {@code TradeSimulationService} to dual-write.
 */
public interface TradeSimulationRepositoryPort {

    Optional<TradeSimulation> findByReviewId(long reviewId, ReviewType type);

    List<TradeSimulation> findByInstrument(String instrument, int limit);

    List<TradeSimulation> findByStatuses(Collection<TradeSimulationStatus> statuses);

    List<TradeSimulation> findRecent(int limit);

    TradeSimulation save(TradeSimulation sim);
}

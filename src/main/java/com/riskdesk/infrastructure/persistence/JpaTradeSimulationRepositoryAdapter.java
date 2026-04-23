package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import com.riskdesk.infrastructure.persistence.entity.TradeSimulationEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for {@link TradeSimulationRepositoryPort} — Phase 1a foundation.
 *
 * <p>Not yet wired into any write path. See
 * {@code docs/ARCHITECTURE_PRINCIPLES.md} § "Simulation Decoupling Rule".
 */
@Component
public class JpaTradeSimulationRepositoryAdapter implements TradeSimulationRepositoryPort {

    private final JpaTradeSimulationRepository repository;

    public JpaTradeSimulationRepositoryAdapter(JpaTradeSimulationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<TradeSimulation> findByReviewId(long reviewId, ReviewType type) {
        return repository.findByReviewIdAndReviewType(reviewId, type)
            .map(TradeSimulationEntityMapper::toDomain);
    }

    @Override
    public List<TradeSimulation> findByInstrument(String instrument, int limit) {
        if (instrument == null || instrument.isBlank() || limit <= 0) {
            return List.of();
        }
        return repository
            .findByInstrumentOrderByCreatedAtDesc(instrument, PageRequest.of(0, limit))
            .stream()
            .map(TradeSimulationEntityMapper::toDomain)
            .toList();
    }

    @Override
    public List<TradeSimulation> findByStatuses(Collection<TradeSimulationStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return repository.findBySimulationStatusIn(statuses).stream()
            .map(TradeSimulationEntityMapper::toDomain)
            .toList();
    }

    @Override
    public List<TradeSimulation> findRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
            .stream()
            .map(TradeSimulationEntityMapper::toDomain)
            .toList();
    }

    @Override
    public TradeSimulation save(TradeSimulation sim) {
        TradeSimulationEntity saved = repository.saveAndFlush(
            TradeSimulationEntityMapper.toEntity(sim)
        );
        return TradeSimulationEntityMapper.toDomain(saved);
    }
}

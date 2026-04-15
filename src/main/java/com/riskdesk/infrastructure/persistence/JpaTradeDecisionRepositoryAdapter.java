package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.decision.model.TradeDecision;
import com.riskdesk.domain.decision.port.TradeDecisionRepositoryPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter implementing the domain-side {@link TradeDecisionRepositoryPort}.
 * Delegates to {@link TradeDecisionJpaRepository} and handles entity ↔ domain mapping.
 */
@Component
public class JpaTradeDecisionRepositoryAdapter implements TradeDecisionRepositoryPort {

    private final TradeDecisionJpaRepository jpa;

    public JpaTradeDecisionRepositoryAdapter(TradeDecisionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public TradeDecision save(TradeDecision decision) {
        return TradeDecisionEntityMapper.toDomain(jpa.save(TradeDecisionEntityMapper.toEntity(decision)));
    }

    @Override
    public Optional<TradeDecision> findById(Long id) {
        return jpa.findById(id).map(TradeDecisionEntityMapper::toDomain);
    }

    @Override
    public List<TradeDecision> findRecent(int limit) {
        return jpa.findAll(
            PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        ).stream().map(TradeDecisionEntityMapper::toDomain).toList();
    }

    @Override
    public List<TradeDecision> findRecentByInstrument(String instrument, int limit) {
        // Simple in-memory filter: findAll sorted then filter. Replace with a proper @Query if
        // this becomes a hot path (currently invoked only by the REST controller for UI).
        return jpa.findAll(
            PageRequest.of(0, limit * 4, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        ).stream()
            .filter(e -> instrument.equalsIgnoreCase(e.getInstrument()))
            .limit(limit)
            .map(TradeDecisionEntityMapper::toDomain)
            .toList();
    }

    @Override
    public List<TradeDecision> findThread(String instrument, String timeframe,
                                          String direction, String zoneName) {
        return jpa.findByInstrumentAndTimeframeAndDirectionAndZoneNameOrderByRevisionAsc(
                instrument, timeframe, direction, zoneName).stream()
            .map(TradeDecisionEntityMapper::toDomain)
            .toList();
    }
}

package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.infrastructure.persistence.entity.TradeSimulationEntity;

final class TradeSimulationEntityMapper {

    private TradeSimulationEntityMapper() {
    }

    static TradeSimulationEntity toEntity(TradeSimulation sim) {
        TradeSimulationEntity entity = new TradeSimulationEntity();
        entity.setId(sim.id());
        entity.setReviewId(sim.reviewId());
        entity.setReviewType(sim.reviewType());
        entity.setInstrument(sim.instrument());
        entity.setAction(sim.action());
        entity.setSimulationStatus(sim.simulationStatus());
        entity.setActivationTime(sim.activationTime());
        entity.setResolutionTime(sim.resolutionTime());
        entity.setMaxDrawdownPoints(sim.maxDrawdownPoints());
        entity.setTrailingStopResult(sim.trailingStopResult());
        entity.setTrailingExitPrice(sim.trailingExitPrice());
        entity.setBestFavorablePrice(sim.bestFavorablePrice());
        entity.setCreatedAt(sim.createdAt());
        return entity;
    }

    static TradeSimulation toDomain(TradeSimulationEntity entity) {
        return new TradeSimulation(
            entity.getId(),
            entity.getReviewId(),
            entity.getReviewType(),
            entity.getInstrument(),
            entity.getAction(),
            entity.getSimulationStatus(),
            entity.getActivationTime(),
            entity.getResolutionTime(),
            entity.getMaxDrawdownPoints(),
            entity.getTrailingStopResult(),
            entity.getTrailingExitPrice(),
            entity.getBestFavorablePrice(),
            entity.getCreatedAt()
        );
    }
}

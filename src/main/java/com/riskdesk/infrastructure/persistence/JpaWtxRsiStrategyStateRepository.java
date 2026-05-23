package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.WtxRsiStrategyStateEntity;
import com.riskdesk.infrastructure.persistence.entity.WtxRsiStrategyStateId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JpaWtxRsiStrategyStateRepository
        extends JpaRepository<WtxRsiStrategyStateEntity, WtxRsiStrategyStateId> {

    Optional<WtxRsiStrategyStateEntity> findByInstrumentAndTimeframe(String instrument, String timeframe);

    List<WtxRsiStrategyStateEntity> findByCurrentDirectionNot(String currentDirection);
}

package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.WtxStrategyStateEntity;
import com.riskdesk.infrastructure.persistence.entity.WtxStrategyStateId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaWtxStrategyStateRepository
        extends JpaRepository<WtxStrategyStateEntity, WtxStrategyStateId> {

    Optional<WtxStrategyStateEntity> findByInstrumentAndTimeframe(String instrument, String timeframe);
}

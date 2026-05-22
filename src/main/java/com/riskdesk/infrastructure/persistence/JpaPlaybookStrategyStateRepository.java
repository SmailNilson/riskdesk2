package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.PlaybookStrategyStateEntity;
import com.riskdesk.infrastructure.persistence.entity.PlaybookStrategyStateId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaPlaybookStrategyStateRepository
        extends JpaRepository<PlaybookStrategyStateEntity, PlaybookStrategyStateId> {

    Optional<PlaybookStrategyStateEntity> findByInstrumentAndTimeframe(String instrument, String timeframe);
}

package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.PlaybookAutomationStateEntity;
import com.riskdesk.infrastructure.persistence.entity.PlaybookAutomationStateId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaPlaybookAutomationStateRepository
        extends JpaRepository<PlaybookAutomationStateEntity, PlaybookAutomationStateId> {
    Optional<PlaybookAutomationStateEntity> findByInstrumentAndTimeframe(String instrument, String timeframe);
}

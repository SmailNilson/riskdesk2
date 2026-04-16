package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.AlertEvaluatorCandleGuardEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AlertEvaluatorCandleGuardJpaRepository
        extends JpaRepository<AlertEvaluatorCandleGuardEntity, String> {

    List<AlertEvaluatorCandleGuardEntity> findByUpdatedAtAfter(Instant since);
}

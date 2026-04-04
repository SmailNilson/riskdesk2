package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.AlertEvaluatorStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AlertEvaluatorStateJpaRepository extends JpaRepository<AlertEvaluatorStateEntity, String> {

    List<AlertEvaluatorStateEntity> findByUpdatedAtAfter(Instant since);
}

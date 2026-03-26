package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.infrastructure.persistence.entity.MentorAuditEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MentorAuditJpaRepository extends JpaRepository<MentorAuditEntity, Long> {

    Optional<MentorAuditEntity> findBySourceRef(String sourceRef);

    List<MentorAuditEntity> findBySourceRefStartingWithOrderByCreatedAtDesc(String prefix, Pageable pageable);

    // Picks up both explicit PENDING_ENTRY/ACTIVE AND legacy rows where simulationStatus was never set (null)
    // Only includes successful audits (success=true) to avoid processing failed/errored reviews
    @Query("SELECT e FROM MentorAuditEntity e WHERE e.simulationStatus IN :statuses OR (e.simulationStatus IS NULL AND e.success = true)")
    List<MentorAuditEntity> findPendingSimulations(@Param("statuses") List<TradeSimulationStatus> statuses);
}

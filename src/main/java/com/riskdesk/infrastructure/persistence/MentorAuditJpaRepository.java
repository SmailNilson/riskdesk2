package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.MentorAuditEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MentorAuditJpaRepository extends JpaRepository<MentorAuditEntity, Long> {

    Optional<MentorAuditEntity> findBySourceRef(String sourceRef);

    List<MentorAuditEntity> findBySourceRefStartingWithOrderByCreatedAtDesc(String prefix, Pageable pageable);
}

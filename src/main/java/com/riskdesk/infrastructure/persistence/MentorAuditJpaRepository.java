package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.MentorAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MentorAuditJpaRepository extends JpaRepository<MentorAuditEntity, Long> {
}

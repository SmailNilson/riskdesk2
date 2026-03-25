package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.MentorAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MentorAuditRepository extends JpaRepository<MentorAudit, Long> {
}

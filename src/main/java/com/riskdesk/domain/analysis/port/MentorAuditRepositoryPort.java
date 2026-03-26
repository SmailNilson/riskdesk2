package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.model.MentorAudit;

import java.util.List;
import java.util.Optional;

public interface MentorAuditRepositoryPort {

    MentorAudit save(MentorAudit audit);

    Optional<MentorAudit> findBySourceRef(String sourceRef);

    List<MentorAudit> findRecentBySourceRefPrefix(String prefix, int limit);
}

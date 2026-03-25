package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.model.MentorAudit;

public interface MentorAuditRepositoryPort {

    MentorAudit save(MentorAudit audit);
}

package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.MentorAudit;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaMentorAuditRepositoryAdapter implements MentorAuditRepositoryPort {

    private final MentorAuditJpaRepository repository;

    public JpaMentorAuditRepositoryAdapter(MentorAuditJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public MentorAudit save(MentorAudit audit) {
        return MentorAuditEntityMapper.toDomain(repository.save(MentorAuditEntityMapper.toEntity(audit)));
    }

    @Override
    public Optional<MentorAudit> findBySourceRef(String sourceRef) {
        return repository.findBySourceRef(sourceRef).map(MentorAuditEntityMapper::toDomain);
    }
}

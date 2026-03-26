package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.infrastructure.persistence.entity.MentorAuditEntity;

final class MentorAuditEntityMapper {

    private MentorAuditEntityMapper() {
    }

    static MentorAuditEntity toEntity(MentorAudit audit) {
        MentorAuditEntity entity = new MentorAuditEntity();
        entity.setId(audit.getId());
        entity.setSourceRef(audit.getSourceRef());
        entity.setCreatedAt(audit.getCreatedAt());
        entity.setInstrument(audit.getInstrument());
        entity.setTimeframe(audit.getTimeframe());
        entity.setAction(audit.getAction());
        entity.setModel(audit.getModel());
        entity.setPayloadJson(audit.getPayloadJson());
        entity.setResponseJson(audit.getResponseJson());
        entity.setVerdict(audit.getVerdict());
        entity.setSuccess(audit.isSuccess());
        entity.setErrorMessage(audit.getErrorMessage());
        entity.setSemanticText(audit.getSemanticText());
        entity.setSimulationStatus(audit.getSimulationStatus());
        entity.setActivationTime(audit.getActivationTime());
        entity.setResolutionTime(audit.getResolutionTime());
        entity.setMaxDrawdownPoints(audit.getMaxDrawdownPoints());
        return entity;
    }

    static MentorAudit toDomain(MentorAuditEntity entity) {
        MentorAudit audit = new MentorAudit();
        audit.setId(entity.getId());
        audit.setSourceRef(entity.getSourceRef());
        audit.setCreatedAt(entity.getCreatedAt());
        audit.setInstrument(entity.getInstrument());
        audit.setTimeframe(entity.getTimeframe());
        audit.setAction(entity.getAction());
        audit.setModel(entity.getModel());
        audit.setPayloadJson(entity.getPayloadJson());
        audit.setResponseJson(entity.getResponseJson());
        audit.setVerdict(entity.getVerdict());
        audit.setSuccess(entity.isSuccess());
        audit.setErrorMessage(entity.getErrorMessage());
        audit.setSemanticText(entity.getSemanticText());
        audit.setSimulationStatus(entity.getSimulationStatus());
        audit.setActivationTime(entity.getActivationTime());
        audit.setResolutionTime(entity.getResolutionTime());
        audit.setMaxDrawdownPoints(entity.getMaxDrawdownPoints());
        return audit;
    }
}

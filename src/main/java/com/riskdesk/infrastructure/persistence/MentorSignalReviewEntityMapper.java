package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.infrastructure.persistence.entity.MentorSignalReviewEntity;

final class MentorSignalReviewEntityMapper {

    private MentorSignalReviewEntityMapper() {
    }

    static MentorSignalReviewEntity toEntity(MentorSignalReviewRecord review) {
        MentorSignalReviewEntity entity = new MentorSignalReviewEntity();
        entity.setId(review.getId());
        entity.setAlertKey(review.getAlertKey());
        entity.setRevision(review.getRevision());
        entity.setTriggerType(review.getTriggerType());
        entity.setStatus(review.getStatus());
        entity.setSeverity(review.getSeverity());
        entity.setCategory(review.getCategory());
        entity.setMessage(review.getMessage());
        entity.setInstrument(review.getInstrument());
        entity.setTimeframe(review.getTimeframe());
        entity.setAction(review.getAction());
        entity.setAlertTimestamp(review.getAlertTimestamp());
        entity.setCreatedAt(review.getCreatedAt());
        entity.setSelectedTimezone(review.getSelectedTimezone());
        entity.setCompletedAt(review.getCompletedAt());
        entity.setSnapshotJson(review.getSnapshotJson());
        entity.setAnalysisJson(review.getAnalysisJson());
        entity.setVerdict(review.getVerdict());
        entity.setErrorMessage(review.getErrorMessage());
        entity.setOpusAnnotation(review.getOpusAnnotation());
        entity.setExecutionEligibilityStatus(review.getExecutionEligibilityStatus());
        entity.setExecutionEligibilityReason(review.getExecutionEligibilityReason());
        entity.setSimulationStatus(review.getSimulationStatus());
        entity.setActivationTime(review.getActivationTime());
        entity.setResolutionTime(review.getResolutionTime());
        entity.setMaxDrawdownPoints(review.getMaxDrawdownPoints());
        entity.setTrailingStopResult(review.getTrailingStopResult());
        entity.setTrailingExitPrice(review.getTrailingExitPrice());
        entity.setBestFavorablePrice(review.getBestFavorablePrice());
        entity.setSourceType(review.getSourceType());
        entity.setTriggerPrice(review.getTriggerPrice());
        return entity;
    }

    static MentorSignalReviewRecord toDomain(MentorSignalReviewEntity entity) {
        MentorSignalReviewRecord review = new MentorSignalReviewRecord();
        review.setId(entity.getId());
        review.setAlertKey(entity.getAlertKey());
        review.setRevision(entity.getRevision());
        review.setTriggerType(entity.getTriggerType());
        review.setStatus(entity.getStatus());
        review.setSeverity(entity.getSeverity());
        review.setCategory(entity.getCategory());
        review.setMessage(entity.getMessage());
        review.setInstrument(entity.getInstrument());
        review.setTimeframe(entity.getTimeframe());
        review.setAction(entity.getAction());
        review.setAlertTimestamp(entity.getAlertTimestamp());
        review.setCreatedAt(entity.getCreatedAt());
        review.setSelectedTimezone(entity.getSelectedTimezone());
        review.setCompletedAt(entity.getCompletedAt());
        review.setSnapshotJson(entity.getSnapshotJson());
        review.setAnalysisJson(entity.getAnalysisJson());
        review.setVerdict(entity.getVerdict());
        review.setErrorMessage(entity.getErrorMessage());
        review.setOpusAnnotation(entity.getOpusAnnotation());
        review.setExecutionEligibilityStatus(entity.getExecutionEligibilityStatus());
        review.setExecutionEligibilityReason(entity.getExecutionEligibilityReason());
        review.setSimulationStatus(entity.getSimulationStatus());
        review.setActivationTime(entity.getActivationTime());
        review.setResolutionTime(entity.getResolutionTime());
        review.setMaxDrawdownPoints(entity.getMaxDrawdownPoints());
        review.setTrailingStopResult(entity.getTrailingStopResult());
        review.setTrailingExitPrice(entity.getTrailingExitPrice());
        review.setBestFavorablePrice(entity.getBestFavorablePrice());
        review.setSourceType(entity.getSourceType());
        review.setTriggerPrice(entity.getTriggerPrice());
        return review;
    }
}

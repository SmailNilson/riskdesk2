package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.playbook.automation.PlaybookDecision;
import com.riskdesk.infrastructure.persistence.entity.PlaybookDecisionEntity;

final class PlaybookDecisionMapper {
    private PlaybookDecisionMapper() {
    }

    static PlaybookDecisionEntity toEntity(PlaybookDecision d) {
        PlaybookDecisionEntity e = new PlaybookDecisionEntity();
        e.setId(d.id());
        e.setDecisionKey(d.decisionKey());
        e.setInstrument(d.instrument());
        e.setTimeframe(d.timeframe());
        e.setSetupIdentity(d.setupIdentity());
        e.setSetupType(d.setupType());
        e.setZoneName(d.zoneName());
        e.setDirection(d.direction());
        e.setChecklistScore(d.checklistScore());
        e.setVerdict(d.verdict());
        e.setEntryPrice(d.entryPrice());
        e.setStopLoss(d.stopLoss());
        e.setTakeProfit1(d.takeProfit1());
        e.setTakeProfit2(d.takeProfit2());
        e.setRrRatio(d.rrRatio());
        e.setRiskPercent(d.riskPercent());
        e.setLateEntry(d.lateEntry());
        e.setPriceSource(d.priceSource());
        e.setPriceTimestamp(d.priceTimestamp());
        e.setEvaluatedCandleTs(d.evaluatedCandleTs());
        e.setCreatedAt(d.createdAt());
        e.setRoutingOutcome(d.routingOutcome());
        e.setRoutingErrorMessage(d.routingErrorMessage());
        e.setExecutionId(d.executionId());
        return e;
    }

    static PlaybookDecision toDomain(PlaybookDecisionEntity e) {
        return new PlaybookDecision(
            e.getId(),
            e.getDecisionKey(),
            e.getInstrument(),
            e.getTimeframe(),
            e.getSetupIdentity(),
            e.getSetupType(),
            e.getZoneName(),
            e.getDirection(),
            e.getChecklistScore(),
            e.getVerdict(),
            e.getEntryPrice(),
            e.getStopLoss(),
            e.getTakeProfit1(),
            e.getTakeProfit2(),
            e.getRrRatio(),
            e.getRiskPercent(),
            e.isLateEntry(),
            e.getPriceSource(),
            e.getPriceTimestamp(),
            e.getEvaluatedCandleTs(),
            e.getCreatedAt(),
            e.getRoutingOutcome(),
            e.getRoutingErrorMessage(),
            e.getExecutionId()
        );
    }
}

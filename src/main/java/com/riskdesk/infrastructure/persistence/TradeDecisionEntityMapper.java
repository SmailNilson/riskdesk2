package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.decision.model.TradeDecision;
import com.riskdesk.infrastructure.persistence.entity.TradeDecisionEntity;

/**
 * Bidirectional mapper between the immutable domain record {@link TradeDecision} and the
 * mutable JPA entity {@link TradeDecisionEntity}.
 *
 * <p>Package-private: only the repository adapter needs it.
 */
final class TradeDecisionEntityMapper {

    private TradeDecisionEntityMapper() {}

    static TradeDecisionEntity toEntity(TradeDecision d) {
        TradeDecisionEntity e = new TradeDecisionEntity();
        e.setId(d.id());
        e.setRevision(d.revision());
        e.setCreatedAt(d.createdAt());
        e.setInstrument(d.instrument());
        e.setTimeframe(d.timeframe());
        e.setDirection(d.direction());
        e.setSetupType(d.setupType());
        e.setZoneName(d.zoneName());
        e.setEligibility(d.eligibility());
        e.setSizePercent(d.sizePercent());
        e.setVerdict(d.verdict());
        e.setAgentVerdictsJson(d.agentVerdictsJson());
        e.setWarningsJson(d.warningsJson());
        e.setEntryPrice(d.entryPrice());
        e.setStopLoss(d.stopLoss());
        e.setTakeProfit1(d.takeProfit1());
        e.setTakeProfit2(d.takeProfit2());
        e.setRrRatio(d.rrRatio());
        e.setNarrative(d.narrative());
        e.setNarrativeModel(d.narrativeModel());
        e.setNarrativeLatencyMs(d.narrativeLatencyMs());
        e.setStatus(d.status());
        e.setErrorMessage(d.errorMessage());
        return e;
    }

    static TradeDecision toDomain(TradeDecisionEntity e) {
        return new TradeDecision(
            e.getId(),
            e.getRevision(),
            e.getCreatedAt(),
            e.getInstrument(),
            e.getTimeframe(),
            e.getDirection(),
            e.getSetupType(),
            e.getZoneName(),
            e.getEligibility(),
            e.getSizePercent(),
            e.getVerdict(),
            e.getAgentVerdictsJson(),
            e.getWarningsJson(),
            e.getEntryPrice(),
            e.getStopLoss(),
            e.getTakeProfit1(),
            e.getTakeProfit2(),
            e.getRrRatio(),
            e.getNarrative(),
            e.getNarrativeModel(),
            e.getNarrativeLatencyMs(),
            e.getStatus(),
            e.getErrorMessage()
        );
    }
}

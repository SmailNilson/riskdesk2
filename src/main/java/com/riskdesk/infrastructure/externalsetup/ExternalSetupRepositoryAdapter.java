package com.riskdesk.infrastructure.externalsetup;

import com.riskdesk.domain.externalsetup.ExternalSetup;
import com.riskdesk.domain.externalsetup.ExternalSetupStatus;
import com.riskdesk.domain.externalsetup.port.ExternalSetupRepositoryPort;
import com.riskdesk.domain.model.Instrument;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class ExternalSetupRepositoryAdapter implements ExternalSetupRepositoryPort {

    private final ExternalSetupJpaRepository jpa;

    public ExternalSetupRepositoryAdapter(ExternalSetupJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public ExternalSetup save(ExternalSetup setup) {
        ExternalSetupEntity entity = toEntity(setup);
        ExternalSetupEntity saved = jpa.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<ExternalSetup> findById(Long id) {
        return jpa.findById(id).map(ExternalSetupRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<ExternalSetup> findBySetupKey(String setupKey) {
        return jpa.findBySetupKey(setupKey).map(ExternalSetupRepositoryAdapter::toDomain);
    }

    @Override
    public List<ExternalSetup> findByStatuses(List<ExternalSetupStatus> statuses, int limit) {
        return jpa.findByStatuses(statuses, PageRequest.of(0, limit)).stream()
            .map(ExternalSetupRepositoryAdapter::toDomain)
            .toList();
    }

    @Override
    public List<ExternalSetup> findPendingExpiredAt(Instant clock, int limit) {
        return jpa.findPendingExpiredAt(clock, PageRequest.of(0, limit)).stream()
            .map(ExternalSetupRepositoryAdapter::toDomain)
            .toList();
    }

    @Override
    public List<ExternalSetup> findRecentByInstrument(Instrument instrument, Instant since, int limit) {
        return jpa.findRecentByInstrument(instrument, since, PageRequest.of(0, limit)).stream()
            .map(ExternalSetupRepositoryAdapter::toDomain)
            .toList();
    }

    static ExternalSetupEntity toEntity(ExternalSetup s) {
        ExternalSetupEntity e = new ExternalSetupEntity();
        e.setId(s.getId());
        e.setSetupKey(s.getSetupKey());
        e.setInstrument(s.getInstrument());
        e.setDirection(s.getDirection());
        e.setEntry(s.getEntry());
        e.setStopLoss(s.getStopLoss());
        e.setTakeProfit1(s.getTakeProfit1());
        e.setTakeProfit2(s.getTakeProfit2());
        e.setConfidence(s.getConfidence());
        e.setTriggerLabel(s.getTriggerLabel());
        e.setPayloadJson(s.getPayloadJson());
        e.setSource(s.getSource());
        e.setSourceRef(s.getSourceRef());
        e.setStatus(s.getStatus());
        e.setSubmittedAt(s.getSubmittedAt());
        e.setExpiresAt(s.getExpiresAt());
        e.setValidatedAt(s.getValidatedAt());
        e.setValidatedBy(s.getValidatedBy());
        e.setRejectionReason(s.getRejectionReason());
        e.setTradeExecutionId(s.getTradeExecutionId());
        e.setExecutedAtPrice(s.getExecutedAtPrice());
        e.setAutoExecuted(s.isAutoExecuted());
        return e;
    }

    static ExternalSetup toDomain(ExternalSetupEntity e) {
        ExternalSetup s = new ExternalSetup();
        s.setId(e.getId());
        s.setSetupKey(e.getSetupKey());
        s.setInstrument(e.getInstrument());
        s.setDirection(e.getDirection());
        s.setEntry(e.getEntry());
        s.setStopLoss(e.getStopLoss());
        s.setTakeProfit1(e.getTakeProfit1());
        s.setTakeProfit2(e.getTakeProfit2());
        s.setConfidence(e.getConfidence());
        s.setTriggerLabel(e.getTriggerLabel());
        s.setPayloadJson(e.getPayloadJson());
        s.setSource(e.getSource());
        s.setSourceRef(e.getSourceRef());
        s.setStatus(e.getStatus());
        s.setSubmittedAt(e.getSubmittedAt());
        s.setExpiresAt(e.getExpiresAt());
        s.setValidatedAt(e.getValidatedAt());
        s.setValidatedBy(e.getValidatedBy());
        s.setRejectionReason(e.getRejectionReason());
        s.setTradeExecutionId(e.getTradeExecutionId());
        s.setExecutedAtPrice(e.getExecutedAtPrice());
        s.setAutoExecuted(e.isAutoExecuted());
        return s;
    }
}

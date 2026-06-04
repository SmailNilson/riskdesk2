package com.riskdesk.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.engine.strategy.wtx.WtxEnrichmentSnapshot;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignalType;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxSignalHistoryPort;
import com.riskdesk.infrastructure.persistence.entity.WtxSignalHistoryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class JpaWtxSignalHistoryAdapter implements WtxSignalHistoryPort {

    private static final Logger log = LoggerFactory.getLogger(JpaWtxSignalHistoryAdapter.class);

    private final JpaWtxSignalHistoryRepository repository;
    private final ObjectMapper objectMapper;

    public JpaWtxSignalHistoryAdapter(JpaWtxSignalHistoryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(WtxSignal signal) {
        WtxSignalHistoryEntity e = new WtxSignalHistoryEntity();
        e.setInstrument(signal.instrument());
        e.setTimeframe(signal.timeframe());
        e.setSignalType(signal.signalType().name());
        e.setDirection(signal.direction());
        e.setWt1Value(signal.wt1Value());
        e.setWt2Value(signal.wt2Value());
        e.setCanTrade(signal.canTrade());
        e.setActionTaken(signal.suggestedAction().name());
        e.setEnrichmentJson(serializeEnrichment(signal.enrichment()));
        e.setRoutingOutcome(signal.routingOutcome() != null ? signal.routingOutcome().name() : null);
        e.setRoutingErrorMessage(signal.routingErrorMessage());
        e.setPrice(signal.price());
        e.setSignalTs(signal.signalTs());
        e.setCreatedAt(Instant.now());
        repository.save(e);
    }

    @Override
    public List<WtxSignal> findRecent(String instrument, int limit) {
        return repository
                .findByInstrumentOrderBySignalTsDesc(instrument, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<WtxSignal> findRecent(String instrument, String timeframe, int limit) {
        return repository
                .findByInstrumentAndTimeframeOrderBySignalTsDesc(instrument, timeframe, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private String serializeEnrichment(WtxEnrichmentSnapshot enrichment) {
        if (enrichment == null) return null;
        try {
            return objectMapper.writeValueAsString(enrichment);
        } catch (JsonProcessingException ex) {
            log.warn("WTX: failed to serialize enrichment snapshot", ex);
            return null;
        }
    }

    private WtxSignal toDomain(WtxSignalHistoryEntity e) {
        WtxEnrichmentSnapshot enrichment = deserializeEnrichment(e.getEnrichmentJson());
        return new WtxSignal(
                e.getInstrument(),
                e.getTimeframe(),
                WtxSignalType.valueOf(e.getSignalType()),
                e.getDirection(),
                e.getWt1Value(),
                e.getWt2Value(),
                e.isCanTrade(),
                com.riskdesk.domain.engine.strategy.wtx.WtxAction.valueOf(e.getActionTaken()),
                enrichment != null ? enrichment : WtxEnrichmentSnapshot.empty(),
                e.getSignalTs(),
                parseRoutingOutcome(e.getRoutingOutcome()),
                e.getRoutingErrorMessage(),
                e.getPrice()
        );
    }

    private WtxRoutingOutcome parseRoutingOutcome(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return WtxRoutingOutcome.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private WtxEnrichmentSnapshot deserializeEnrichment(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, WtxEnrichmentSnapshot.class);
        } catch (JsonProcessingException ex) {
            log.warn("WTX: failed to deserialize enrichment snapshot", ex);
            return null;
        }
    }
}

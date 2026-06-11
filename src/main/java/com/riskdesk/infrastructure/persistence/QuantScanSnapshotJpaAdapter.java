package com.riskdesk.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.scanlog.QuantScanSnapshot;
import com.riskdesk.domain.quant.scanlog.QuantScanSnapshotPort;
import com.riskdesk.infrastructure.persistence.entity.QuantScanSnapshotEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JPA adapter for the per-scan Quant flow log. */
@Component
public class QuantScanSnapshotJpaAdapter implements QuantScanSnapshotPort {

    private static final TypeReference<LinkedHashMap<String, String>> GATES_TYPE = new TypeReference<>() {};

    private final JpaQuantScanSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    public QuantScanSnapshotJpaAdapter(JpaQuantScanSnapshotRepository repository,
                                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(QuantScanSnapshot snapshot) {
        repository.save(toEntity(snapshot));
    }

    @Override
    public List<QuantScanSnapshot> findRange(Instrument instrument, Instant from, Instant to, int limit) {
        return repository
            .findByInstrumentAndScannedAtBetweenOrderByScannedAtDesc(
                instrument, from, to, PageRequest.of(0, Math.max(1, limit)))
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    @Transactional
    public int purgeBefore(Instant cutoff) {
        return repository.deleteByScannedAtBefore(cutoff);
    }

    QuantScanSnapshotEntity toEntity(QuantScanSnapshot s) {
        return new QuantScanSnapshotEntity(
            s.instrument(), s.scannedAt(),
            s.price(), s.priceSource(),
            s.delta(), s.buyRatioPct(), s.deltaSource(),
            s.absFreshTotal(), s.absBull8Count(), s.absBear8Count(),
            s.absMaxScore(), s.dominantSide(),
            s.distType(), s.distConf(),
            s.cycleType(), s.cyclePhase(),
            s.score(), s.longScore(),
            s.patternType(), s.patternLabel(),
            s.patternConfidence(), s.patternActionShort(),
            writeGates(s.gateResults()));
    }

    QuantScanSnapshot toDomain(QuantScanSnapshotEntity e) {
        return new QuantScanSnapshot(
            e.getInstrument(), e.getScannedAt(),
            e.getPrice(), e.getPriceSource(),
            e.getDelta(), e.getBuyRatioPct(), e.getDeltaSource(),
            e.getAbsFreshTotal(), e.getAbsBull8Count(), e.getAbsBear8Count(),
            e.getAbsMaxScore(), e.getDominantSide(),
            e.getDistType(), e.getDistConf(),
            e.getCycleType(), e.getCyclePhase(),
            e.getScore(), e.getLongScore(),
            e.getPatternType(), e.getPatternLabel(),
            e.getPatternConfidence(), e.getPatternActionShort(),
            readGates(e.getGatesJson()));
    }

    private String writeGates(Map<String, String> gates) {
        try {
            return objectMapper.writeValueAsString(gates == null ? Map.of() : gates);
        } catch (JsonProcessingException e) {
            // A map of strings cannot realistically fail to serialize; degrade
            // to an empty object rather than dropping the whole row.
            return "{}";
        }
    }

    private Map<String, String> readGates(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, GATES_TYPE);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}

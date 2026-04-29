package com.riskdesk.infrastructure.quant.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskdesk.domain.quant.port.QuantStatePort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.DistEntry;
import com.riskdesk.domain.quant.model.QuantState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JPA-backed implementation of {@link QuantStatePort}. Lists are serialised
 * as JSON in TEXT columns; each save rewrites the full row.
 */
@Component
public class QuantStateJpaAdapter implements QuantStatePort {

    private static final Logger log = LoggerFactory.getLogger(QuantStateJpaAdapter.class);

    private final QuantStateJpaRepository repository;
    private final ObjectMapper mapper;

    public QuantStateJpaAdapter(QuantStateJpaRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public QuantState load(Instrument instrument) {
        Optional<QuantStateEntity> row = repository.findById(instrument.name());
        return row.map(this::toDomain).orElse(null);
    }

    @Override
    @Transactional
    public void save(Instrument instrument, QuantState state) {
        QuantStateEntity entity = repository.findById(instrument.name())
            .orElseGet(() -> new QuantStateEntity(instrument.name()));
        entity.setSessionDate(state.sessionDate());
        entity.setMonitorStartPx(state.monitorStartPx());
        entity.setDeltaHistoryJson(serialiseDoubles(state.deltaHistory()));
        entity.setDistOnlyHistoryJson(serialiseDistEntries(state.distOnlyHistory()));
        entity.setAccuOnlyHistoryJson(serialiseDistEntries(state.accuOnlyHistory()));
        entity.setAbsBullScansJson(serialiseInstants(state.absBullScans30m()));
        repository.save(entity);
    }

    private QuantState toDomain(QuantStateEntity e) {
        return new QuantState(
            e.getSessionDate(),
            e.getMonitorStartPx(),
            parseDoubles(e.getDeltaHistoryJson()),
            parseDistEntries(e.getDistOnlyHistoryJson()),
            parseDistEntries(e.getAccuOnlyHistoryJson()),
            parseInstants(e.getAbsBullScansJson())
        );
    }

    // ── Serialisation helpers ───────────────────────────────────────────────

    private String serialiseDoubles(List<Double> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception ex) {
            log.warn("failed to serialise quant deltas: {}", ex.toString());
            return "[]";
        }
    }

    private List<Double> parseDoubles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<Double>>() {});
        } catch (Exception ex) {
            log.warn("failed to parse quant deltas: {}", ex.toString());
            return List.of();
        }
    }

    private String serialiseDistEntries(List<DistEntry> entries) {
        try {
            ArrayNode arr = mapper.createArrayNode();
            for (DistEntry e : entries) {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", e.type());
                node.put("conf", e.conf());
                node.put("ts", e.ts() != null ? e.ts().toString() : null);
                arr.add(node);
            }
            return mapper.writeValueAsString(arr);
        } catch (Exception ex) {
            log.warn("failed to serialise dist entries: {}", ex.toString());
            return "[]";
        }
    }

    private List<DistEntry> parseDistEntries(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(json);
            List<DistEntry> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                ObjectNode node = (ObjectNode) arr.get(i);
                String type = node.path("type").asText("DIST");
                double conf = node.path("conf").asDouble(0.0);
                String tsStr = node.path("ts").asText(null);
                Instant ts = (tsStr == null || tsStr.isBlank() || "null".equals(tsStr))
                    ? null : Instant.parse(tsStr);
                out.add(new DistEntry(type, conf, ts));
            }
            return out;
        } catch (Exception ex) {
            log.warn("failed to parse dist entries: {}", ex.toString());
            return List.of();
        }
    }

    private String serialiseInstants(List<Instant> values) {
        try {
            ArrayNode arr = mapper.createArrayNode();
            for (Instant t : values) arr.add(t.toString());
            return mapper.writeValueAsString(arr);
        } catch (Exception ex) {
            log.warn("failed to serialise instants: {}", ex.toString());
            return "[]";
        }
    }

    private List<Instant> parseInstants(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> raw = mapper.readValue(json, new TypeReference<List<String>>() {});
            List<Instant> out = new ArrayList<>(raw.size());
            for (String s : raw) out.add(Instant.parse(s));
            return out;
        } catch (Exception ex) {
            log.warn("failed to parse instants: {}", ex.toString());
            return List.of();
        }
    }
}

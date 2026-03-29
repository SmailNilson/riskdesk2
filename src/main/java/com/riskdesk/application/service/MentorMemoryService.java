package com.riskdesk.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class MentorMemoryService {

    private static final Logger log = LoggerFactory.getLogger(MentorMemoryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final GeminiEmbeddingClient embeddingClient;
    private final MentorProperties properties;
    private final ObjectMapper objectMapper;

    private volatile boolean schemaInitialized = false;
    private volatile boolean vectorColumnAvailable = false;

    public MentorMemoryService(JdbcTemplate jdbcTemplate,
                               GeminiEmbeddingClient embeddingClient,
                               MentorProperties properties,
                               ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    void initialize() {
        ensureSchemaInitialized();
    }

    public List<MentorSimilarAudit> findSimilar(JsonNode payload) {
        if (!properties.isMemoryEnabled() || !properties.isEmbeddingsEnabled()) {
            return List.of();
        }

        ensureSchemaInitialized();

        try {
            String semanticQuery = buildSemanticQuery(payload);
            String queryModel = properties.getEmbeddingsModel();
            List<Double> embedding = embeddingClient.embed(semanticQuery, queryModel);
            return vectorColumnAvailable
                ? findWithPgVector(embedding, queryModel)
                : findWithFallback(embedding, queryModel);
        } catch (Exception e) {
            log.warn("Mentor memory similarity search unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    public void indexAudit(MentorAudit audit) {
        if (!properties.isMemoryEnabled() || !properties.isEmbeddingsEnabled()) {
            return;
        }
        ensureSchemaInitialized();
        if (audit.getId() == null || audit.getSemanticText() == null || audit.getSemanticText().isBlank()) {
            return;
        }

        try {
            String embeddingModel = properties.getEmbeddingsModel();
            List<Double> embedding = embeddingClient.embed(audit.getSemanticText(), embeddingModel);
            indexAudit(audit, embedding, embeddingModel);
        } catch (Exception e) {
            log.warn("Mentor memory index skipped for audit {}: {}", audit.getId(), e.getMessage());
        }
    }

    public void indexAudit(MentorAudit audit, List<Double> embedding, String embeddingModel) {
        if (!properties.isMemoryEnabled() || !properties.isEmbeddingsEnabled()) {
            return;
        }
        ensureSchemaInitialized();
        if (audit.getId() == null || audit.getSemanticText() == null || audit.getSemanticText().isBlank() || embedding == null || embedding.isEmpty()) {
            return;
        }

        try {
            String embeddingJson = objectMapper.writeValueAsString(embedding);
            jdbcTemplate.update("""
                    INSERT INTO mentor_audit_memories (
                        audit_id, created_at, instrument, timeframe, action, verdict,
                        semantic_text, embedding_model, embedding_dimensions, embedding_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (audit_id) DO UPDATE SET
                        created_at = EXCLUDED.created_at,
                        instrument = EXCLUDED.instrument,
                        timeframe = EXCLUDED.timeframe,
                        action = EXCLUDED.action,
                        verdict = EXCLUDED.verdict,
                        semantic_text = EXCLUDED.semantic_text,
                        embedding_model = EXCLUDED.embedding_model,
                        embedding_dimensions = EXCLUDED.embedding_dimensions,
                        embedding_json = EXCLUDED.embedding_json
                    """,
                audit.getId(),
                Timestamp.from(audit.getCreatedAt()),
                audit.getInstrument(),
                audit.getTimeframe(),
                audit.getAction(),
                audit.getVerdict(),
                audit.getSemanticText(),
                embeddingModel,
                embedding.size(),
                embeddingJson
            );

            if (vectorColumnAvailable && embedding.size() == properties.getEmbeddingDimensions()) {
                jdbcTemplate.update(
                    "UPDATE mentor_audit_memories SET embedding = CAST(? AS vector) WHERE audit_id = ?",
                    toVectorLiteral(embedding),
                    audit.getId()
                );
            }
        } catch (Exception e) {
            log.warn("Mentor memory index skipped for audit {}: {}", audit.getId(), e.getMessage());
        }
    }

    public String currentStorageMode() {
        ensureSchemaInitialized();
        return vectorColumnAvailable ? "pgvector" : "json_fallback";
    }

    private synchronized void ensureSchemaInitialized() {
        if (schemaInitialized) {
            return;
        }

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS mentor_audit_memories (
                audit_id BIGINT PRIMARY KEY REFERENCES mentor_audits(id) ON DELETE CASCADE,
                created_at TIMESTAMPTZ NOT NULL,
                instrument VARCHAR(32),
                timeframe VARCHAR(16),
                action VARCHAR(16),
                verdict VARCHAR(128),
                semantic_text TEXT NOT NULL,
                embedding_model VARCHAR(64) NOT NULL,
                embedding_dimensions INTEGER NOT NULL,
                embedding_json TEXT NOT NULL
            )
            """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS mentor_audit_memories_created_at_idx ON mentor_audit_memories(created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS mentor_audit_memories_instrument_idx ON mentor_audit_memories(instrument)");

        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbcTemplate.execute(
                "ALTER TABLE mentor_audit_memories ADD COLUMN IF NOT EXISTS embedding vector(" + properties.getEmbeddingDimensions() + ")"
            );
            vectorColumnAvailable = true;
        } catch (Exception e) {
            vectorColumnAvailable = false;
            log.warn("pgvector extension unavailable, mentor memory will use JSON fallback: {}", e.getMessage());
        }

        schemaInitialized = true;
    }

    private List<MentorSimilarAudit> findWithPgVector(List<Double> embedding, String queryModel) {
        String vector = toVectorLiteral(embedding);
        return jdbcTemplate.query(
            """
                SELECT audit_id, created_at, instrument, timeframe, action, verdict, semantic_text,
                       1 - (embedding <=> CAST(? AS vector)) AS similarity
                FROM mentor_audit_memories
                WHERE embedding IS NOT NULL
                  AND embedding_model = ?
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """,
            ps -> {
                ps.setString(1, vector);
                ps.setString(2, queryModel);
                ps.setString(3, vector);
                ps.setInt(4, properties.getMemoryTopK());
            },
            (rs, rowNum) -> new MentorSimilarAudit(
                rs.getLong("audit_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("instrument"),
                rs.getString("timeframe"),
                rs.getString("action"),
                rs.getString("verdict"),
                Math.max(0.0, rs.getDouble("similarity")),
                summarize(rs.getString("semantic_text"))
            )
        );
    }

    private List<MentorSimilarAudit> findWithFallback(List<Double> targetEmbedding, String queryModel) {
        List<MentorSimilarAudit> matches = new ArrayList<>();
        jdbcTemplate.query(
            """
                SELECT audit_id, created_at, instrument, timeframe, action, verdict, semantic_text, embedding_json
                FROM mentor_audit_memories
                WHERE embedding_model = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
            ps -> {
                ps.setString(1, queryModel);
                ps.setInt(2, properties.getMemorySearchWindow());
            },
            rs -> {
                List<Double> candidate = parseEmbedding(rs.getString("embedding_json"));
                double similarity = cosineSimilarity(targetEmbedding, candidate);
                matches.add(new MentorSimilarAudit(
                    rs.getLong("audit_id"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("instrument"),
                    rs.getString("timeframe"),
                    rs.getString("action"),
                    rs.getString("verdict"),
                    similarity,
                    summarize(rs.getString("semantic_text"))
                ));
            }
        );
        return matches.stream()
            .sorted(Comparator.comparingDouble(MentorSimilarAudit::similarity).reversed())
            .limit(properties.getMemoryTopK())
            .toList();
    }

    private String buildSemanticQuery(JsonNode payload) {
        return String.join(" | ",
            payload.path("metadata").path("asset").asText(""),
            payload.path("metadata").path("timeframe_focus").asText(""),
            payload.path("metadata").path("market_session").asText(""),
            payload.path("trade_intention").path("action").asText(""),
            payload.path("market_structure_the_king").path("trend_H1").asText(""),
            payload.path("market_structure_the_king").path("trend_focus").asText(""),
            payload.path("market_structure_the_king").path("last_event").asText(""),
            payload.path("momentum_and_flow_the_trigger").path("money_flow_state").asText(""),
            payload.path("risk_and_emotional_check").path("reward_to_risk_ratio").asText("")
        );
    }

    private List<Double> parseEmbedding(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0.0;
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String toVectorLiteral(List<Double> embedding) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding.get(i));
        }
        builder.append(']');
        return builder.toString();
    }

    private String summarize(String semanticText) {
        if (semanticText == null) {
            return "";
        }
        return semanticText.length() <= 180 ? semanticText : semanticText.substring(0, 180) + "...";
    }
}

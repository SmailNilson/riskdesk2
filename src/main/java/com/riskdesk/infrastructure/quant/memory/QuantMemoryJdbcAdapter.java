package com.riskdesk.infrastructure.quant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.memory.MemoryRecord;
import com.riskdesk.domain.quant.memory.QuantMemoryPort;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * pgvector-backed implementation of {@link QuantMemoryPort}, mirroring the
 * pattern used by {@code MentorMemoryService}: raw {@link JdbcTemplate} +
 * pgvector cosine similarity (the {@code <=>} operator). The schema is
 * created lazily on first use.
 */
@Component
public class QuantMemoryJdbcAdapter implements QuantMemoryPort {

    private static final Logger log = LoggerFactory.getLogger(QuantMemoryJdbcAdapter.class);

    /** Embedding dimensionality — matches gemini-embedding-001 default 768 truncation. */
    private static final int EMBEDDING_DIMS = 768;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private volatile boolean schemaInitialized = false;
    private volatile boolean vectorAvailable = false;

    public QuantMemoryJdbcAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    void initialise() {
        ensureSchema();
    }

    @Override
    public void save(MemoryRecord record) {
        if (record == null || record.embedding() == null || record.embedding().length == 0) return;
        ensureSchema();
        try {
            String embeddingJson = mapper.writeValueAsString(record.embedding());
            String pgVector = vectorAvailable ? toPgVector(record.embedding()) : null;
            String sql = vectorAvailable
                ? """
                  INSERT INTO quant_memory (
                    ts, instrument, score, pattern, outcome,
                    pts_result, notes, semantic_text, embedding_json, embedding
                  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)
                  """
                : """
                  INSERT INTO quant_memory (
                    ts, instrument, score, pattern, outcome,
                    pts_result, notes, semantic_text, embedding_json
                  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                  """;
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql);
                ps.setTimestamp(1, Timestamp.from(record.ts() == null ? Instant.now() : record.ts()));
                ps.setString(2, record.instrument().name());
                ps.setInt(3, record.score());
                ps.setString(4, record.pattern() == null ? "INDETERMINE" : record.pattern().name());
                ps.setString(5, record.outcome() == null ? "" : record.outcome());
                ps.setDouble(6, record.ptsResult());
                ps.setString(7, record.notes() == null ? "" : record.notes());
                ps.setString(8, record.semanticText() == null ? "" : record.semanticText());
                ps.setString(9, embeddingJson);
                if (pgVector != null) ps.setString(10, pgVector);
                return ps;
            });
        } catch (Exception ex) {
            log.warn("quant memory save failed: {}", ex.toString());
        }
    }

    @Override
    public List<MemoryRecord> findSimilar(Instrument instrument, float[] queryEmbedding, int topK) {
        ensureSchema();
        if (queryEmbedding == null || queryEmbedding.length == 0) return List.of();
        if (!vectorAvailable) return List.of();
        try {
            String pgQuery = toPgVector(queryEmbedding);
            return jdbc.query(
                """
                SELECT id, ts, instrument, score, pattern, outcome, pts_result, notes, semantic_text
                FROM quant_memory
                WHERE instrument = ?
                  AND embedding IS NOT NULL
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """,
                ps -> {
                    ps.setString(1, instrument.name());
                    ps.setString(2, pgQuery);
                    ps.setInt(3, topK);
                },
                (rs, rowNum) -> new MemoryRecord(
                    rs.getLong("id"),
                    rs.getTimestamp("ts").toInstant(),
                    Instrument.valueOf(rs.getString("instrument")),
                    rs.getInt("score"),
                    parsePattern(rs.getString("pattern")),
                    rs.getString("outcome"),
                    rs.getDouble("pts_result"),
                    rs.getString("notes"),
                    rs.getString("semantic_text"),
                    null
                )
            );
        } catch (Exception ex) {
            log.warn("quant memory similarity search failed: {}", ex.toString());
            return List.of();
        }
    }

    private static OrderFlowPattern parsePattern(String raw) {
        if (raw == null) return OrderFlowPattern.INDETERMINE;
        try {
            return OrderFlowPattern.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return OrderFlowPattern.INDETERMINE;
        }
    }

    private static String toPgVector(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) return;
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS quant_memory (
                  id BIGSERIAL PRIMARY KEY,
                  ts TIMESTAMPTZ NOT NULL,
                  instrument VARCHAR(10) NOT NULL,
                  score INT NOT NULL,
                  pattern VARCHAR(40) NOT NULL,
                  outcome VARCHAR(40),
                  pts_result DOUBLE PRECISION,
                  notes TEXT,
                  semantic_text TEXT,
                  embedding_json JSONB
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_quant_memory_instrument_ts "
                + "ON quant_memory (instrument, ts DESC)");
            try {
                jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
                jdbc.execute("ALTER TABLE quant_memory ADD COLUMN IF NOT EXISTS embedding vector("
                    + EMBEDDING_DIMS + ")");
                vectorAvailable = true;
                log.info("quant memory: pgvector column ready ({} dims)", EMBEDDING_DIMS);
            } catch (Exception ex) {
                log.warn("quant memory: pgvector unavailable — RAG will be disabled ({})", ex.getMessage());
                vectorAvailable = false;
            }
            schemaInitialized = true;
        } catch (Exception ex) {
            log.warn("quant memory: schema init failed — adapter will return empty results ({})", ex.getMessage());
        }
    }
}

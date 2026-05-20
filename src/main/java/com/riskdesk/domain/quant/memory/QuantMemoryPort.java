package com.riskdesk.domain.quant.memory;

import com.riskdesk.domain.model.Instrument;

import java.util.List;

/**
 * Output port for the long-term memory store of past Quant setups.
 * Implementations persist {@link MemoryRecord}s in PostgreSQL with pgvector
 * and expose a top-K cosine similarity search.
 */
public interface QuantMemoryPort {

    /** Persists or updates a memory record (idempotent on the row id). */
    void save(MemoryRecord record);

    /**
     * Returns the {@code topK} most semantically similar records for the instrument.
     * Empty list if the store is unavailable or has no rows.
     */
    List<MemoryRecord> findSimilar(Instrument instrument, float[] queryEmbedding, int topK);
}

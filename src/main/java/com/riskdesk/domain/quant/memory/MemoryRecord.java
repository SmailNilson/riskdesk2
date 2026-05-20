package com.riskdesk.domain.quant.memory;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;

import java.time.Instant;

/**
 * A persisted past quant setup with its eventual outcome. Used by the
 * advisor's RAG step: the application embeds the current snapshot text, asks
 * pgvector for the top-K nearest situations and quotes them in the prompt.
 *
 * <p>The {@code embedding} array is intentionally domain-typed as {@code float[]}
 * — pgvector adapters handle persistence, but the domain stays pure JDK.</p>
 *
 * @param ts            when the setup was observed (UTC)
 * @param instrument    futures instrument
 * @param score         gate score (0-7) at the time
 * @param pattern       order-flow pattern at the time
 * @param outcome       short label: WIN / LOSS / MISS / NEUTRAL or free-form
 * @param ptsResult     points won (positive) or lost (negative)
 * @param notes         free-form comment (e.g. extracted from the trade journal)
 * @param semanticText  the text used to compute the embedding (kept for diagnostics)
 * @param embedding     vector representation of {@code semanticText}
 */
public record MemoryRecord(
    Long id,
    Instant ts,
    Instrument instrument,
    int score,
    OrderFlowPattern pattern,
    String outcome,
    double ptsResult,
    String notes,
    String semanticText,
    float[] embedding
) {
}

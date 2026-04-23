package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Detected smart-money cycle — the chained sequence of three order-flow phases:
 * <ol>
 *   <li>Distribution (or accumulation) — stable-price absorption streak</li>
 *   <li>Momentum burst in the matching direction</li>
 *   <li>Mirror absorption (accumulation, or distribution) at the opposite end</li>
 * </ol>
 * <p>
 * A complete cycle is a signature of institutional positioning → directional
 * move → profit-taking (or the mirror for bullish cycles).
 */
public record SmartMoneyCycleSignal(
    Instrument instrument,
    CycleType cycleType,
    CyclePhase currentPhase,
    /** Price at the start of phase 1 (distribution/accumulation). */
    double priceAtPhase1,
    /** Price at the start of phase 2 (momentum burst). Nullable if phase 2 not reached. */
    Double priceAtPhase2,
    /** Price at the start of phase 3 (mirror). Nullable if phase 3 not reached. */
    Double priceAtPhase3,
    /** Absolute price move from phase 1 to phase 3 (points). */
    double totalPriceMove,
    double totalDurationMinutes,
    /** 0-100. Partial cycles score lower. */
    int confidence,
    Instant startedAt,
    /** Nullable if cycle still in progress. */
    Instant completedAt
) {
    public enum CycleType {
        /** DISTRIBUTION → BEARISH_MOMENTUM → ACCUMULATION : smart money sold at top, shorted the move, covered at bottom. */
        BEARISH_CYCLE,
        /** ACCUMULATION → BULLISH_MOMENTUM → DISTRIBUTION : mirror pattern. */
        BULLISH_CYCLE
    }

    public enum CyclePhase {
        PHASE_1,
        PHASE_2,
        PHASE_3,
        COMPLETE
    }
}

package com.riskdesk.domain.analysis.model;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Final output of the tri-layer scoring engine.
 * <p>
 * {@code primary == NEUTRAL} ⇒ stand-aside; the UI must not surface an "Arm"
 * button in that case.
 * <p>
 * Immutable; safe to publish across threads, persist verbatim for replay,
 * and embed in WebSocket payloads.
 */
public record DirectionalBias(
    Instrument instrument,
    Timeframe timeframe,
    Instant decisionTimestamp,
    Direction primary,
    int confidence,                 // 0-100, post-penalty
    StructureScore structure,
    OrderFlowScore orderFlow,
    MomentumScore momentum,
    List<Factor> bullishFactors,
    List<Factor> bearishFactors,
    List<Contradiction> contradictions,
    /** When primary == NEUTRAL, why we stand aside. */
    String standAsideReason
) {

    public DirectionalBias {
        Objects.requireNonNull(instrument);
        Objects.requireNonNull(timeframe);
        Objects.requireNonNull(decisionTimestamp);
        Objects.requireNonNull(primary);
        Objects.requireNonNull(structure);
        Objects.requireNonNull(orderFlow);
        Objects.requireNonNull(momentum);
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("confidence out of [0,100]: " + confidence);
        }
        bullishFactors    = List.copyOf(Objects.requireNonNull(bullishFactors));
        bearishFactors    = List.copyOf(Objects.requireNonNull(bearishFactors));
        contradictions    = List.copyOf(Objects.requireNonNull(contradictions));
        if (primary == Direction.NEUTRAL && (standAsideReason == null || standAsideReason.isBlank())) {
            throw new IllegalArgumentException("standAsideReason required when primary=NEUTRAL");
        }
    }

    public boolean isStandAside() {
        return primary == Direction.NEUTRAL;
    }

    public static DirectionalBias standAside(Instrument inst, Timeframe tf, Instant decisionAt,
                                              StructureScore s, OrderFlowScore of, MomentumScore m,
                                              List<Contradiction> contradictions, String reason) {
        return new DirectionalBias(inst, tf, decisionAt, Direction.NEUTRAL, 0,
            s, of, m, List.of(), List.of(), contradictions, reason);
    }
}

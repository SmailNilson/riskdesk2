package com.riskdesk.domain.quant.setup;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.structure.IndicatorsSnapshot;
import com.riskdesk.domain.quant.structure.StrategyVotes;

import java.time.Instant;

/**
 * Immutable context bundle passed to each {@link SetupGate}.
 * All fields except {@code instrument} and {@code snapshot} may be null
 * (degraded-mode / missing upstream data).
 */
public record SetupEvaluationContext(
    Instrument instrument,
    QuantSnapshot snapshot,
    IndicatorsSnapshot indicators,
    StrategyVotes strategyVotes,
    Instant evaluatedAt
) {
    public SetupEvaluationContext {
        if (instrument == null) throw new IllegalArgumentException("instrument must not be null");
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        if (evaluatedAt == null) evaluatedAt = Instant.now();
    }
}

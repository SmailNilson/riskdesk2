package com.riskdesk.domain.analysis.model;

import java.util.List;
import java.util.Objects;

/** Layer 3 score (Indicator momentum). Signed, in [-100, +100]. */
public record MomentumScore(double value, List<ScoreComponent> components) {

    public MomentumScore {
        if (value < -100.0 || value > 100.0) {
            throw new IllegalArgumentException("MomentumScore.value out of [-100,+100]: " + value);
        }
        components = List.copyOf(Objects.requireNonNull(components, "components"));
    }

    public static MomentumScore neutral() {
        return new MomentumScore(0.0, List.of());
    }
}

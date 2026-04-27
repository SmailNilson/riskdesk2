package com.riskdesk.domain.analysis.model;

import java.util.List;
import java.util.Objects;

/**
 * Layer 1 score (Structure / SMC). Signed, in [-100, +100].
 * <p>
 * The {@code components} list is for transparency / UI — it lists every input
 * that contributed to the value with its individual weight. Always non-null,
 * possibly empty.
 */
public record StructureScore(double value, List<ScoreComponent> components) {

    public StructureScore {
        if (value < -100.0 || value > 100.0) {
            throw new IllegalArgumentException("StructureScore.value out of [-100,+100]: " + value);
        }
        components = List.copyOf(Objects.requireNonNull(components, "components"));
    }

    public static StructureScore neutral() {
        return new StructureScore(0.0, List.of());
    }
}

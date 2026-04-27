package com.riskdesk.domain.analysis.model;

import java.util.List;
import java.util.Objects;

/** Layer 2 score (Order Flow). Signed, in [-100, +100]. */
public record OrderFlowScore(double value, List<ScoreComponent> components) {

    public OrderFlowScore {
        if (value < -100.0 || value > 100.0) {
            throw new IllegalArgumentException("OrderFlowScore.value out of [-100,+100]: " + value);
        }
        components = List.copyOf(Objects.requireNonNull(components, "components"));
    }

    public static OrderFlowScore neutral() {
        return new OrderFlowScore(0.0, List.of());
    }
}

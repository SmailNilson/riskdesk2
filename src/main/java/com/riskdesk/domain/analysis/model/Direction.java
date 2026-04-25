package com.riskdesk.domain.analysis.model;

/**
 * Trade direction emitted by the tri-layer scoring engine.
 * <p>
 * {@link #NEUTRAL} is a first-class value, not an absence of decision —
 * the engine emits it explicitly when the weighted score lies inside the
 * stand-aside band or when contradictions exceed the tolerance.
 */
public enum Direction {
    LONG,
    SHORT,
    NEUTRAL;

    public Direction opposite() {
        return switch (this) {
            case LONG -> SHORT;
            case SHORT -> LONG;
            case NEUTRAL -> NEUTRAL;
        };
    }

    public boolean isActionable() {
        return this != NEUTRAL;
    }
}

package com.riskdesk.domain.orderflow.perfectsetup;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.List;

/**
 * Immutable result of one {@link PerfectSetupDetector} evaluation: the current
 * state, the chosen direction, the per-axis checklist, and the concrete trade
 * plan (entry zone / stop / targets / R:R) when armed.
 *
 * <p>{@link #timestamp} carries dual meaning by state: for an armed setup it is
 * the <em>arm time</em> (drives the TTL); for a terminal state it is the
 * <em>terminal time</em> (drives the cooldown). The detector manages this so the
 * application layer only needs to feed the previous signal back in.</p>
 */
public record PerfectSetupSignal(
    Instrument instrument,
    PerfectSetupDirection direction,
    PerfectSetupState state,
    int score,
    int maxScore,
    List<PerfectSetupAxis.Result> axes,
    Double entryLow,
    Double entryHigh,
    Double stop,
    Double tp1,
    Double tp2,
    Double riskReward,
    Double triggerLevel,
    Double invalidationLevel,
    String reasoning,
    Instant timestamp
) {
    public PerfectSetupSignal {
        if (instrument == null) throw new IllegalArgumentException("instrument is required");
        if (direction == null) direction = PerfectSetupDirection.NONE;
        if (state == null) state = PerfectSetupState.IDLE;
        axes = axes == null ? List.of() : List.copyOf(axes);
        reasoning = reasoning == null ? "" : reasoning;
        if (timestamp == null) throw new IllegalArgumentException("timestamp is required");
    }

    public boolean isArmed() {
        return state.isArmed();
    }
}

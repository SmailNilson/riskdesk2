package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Lifecycle of a single order-book wall (a level flagged at ≥ wall-threshold ×
 * average level size — the DOM "WALL" marker), from first appearance to outcome.
 * Produced by {@link com.riskdesk.domain.orderflow.service.WallTracker}.
 *
 * <p>An episode is <em>active</em> while the wall is still flagged in the book
 * ({@code endedAt} and {@code outcome} are null) and <em>closed</em> once the wall
 * has been gone past the grace period, with an outcome explaining how it ended.</p>
 *
 * @param instrument       futures instrument
 * @param side             which side of the book the wall rested on
 * @param price            wall price level
 * @param initialSize      resting size when first flagged
 * @param maxSize          largest resting size observed while flagged
 * @param lastSize         resting size the last time the level was flagged
 * @param firstSeenAt      when the wall was first flagged
 * @param lastSeenAt       last time the level was still flagged as a wall
 * @param endedAt          when the episode was finalized (null while active)
 * @param durationSeconds  lifetime as a wall (firstSeenAt → lastSeenAt; for active
 *                         episodes, firstSeenAt → now)
 * @param outcome          how the wall ended (null while active)
 * @param endDistanceTicks distance (ticks) between the wall price and the same-side
 *                         best price — at finalization for closed episodes, live for
 *                         active ones. Negative = price traded through the level.
 */
public record WallEpisode(
    Instrument instrument,
    WallEvent.WallSide side,
    double price,
    long initialSize,
    long maxSize,
    long lastSize,
    Instant firstSeenAt,
    Instant lastSeenAt,
    Instant endedAt,
    double durationSeconds,
    Outcome outcome,
    double endDistanceTicks
) {

    /** How a wall episode ended. */
    public enum Outcome {
        /** Price reached the level before it vanished — the wall was traded into/eaten. */
        CONSUMED,
        /** The order was cancelled while price was still away — spoof suspect. */
        PULLED,
        /** Size still resting at the level; it merely dropped below the relative threshold. */
        FADED,
        /** The level scrolled beyond the visible book — outcome unknowable from L2 alone. */
        OUT_OF_RANGE
    }
}

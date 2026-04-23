package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.SpoofingSignal;
import com.riskdesk.domain.orderflow.model.SpoofingSignal.SpoofSide;
import com.riskdesk.domain.orderflow.model.WallEvent;
import com.riskdesk.domain.orderflow.model.WallEvent.WallEventType;
import com.riskdesk.domain.orderflow.model.WallEvent.WallSide;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure domain service for spoofing detection (UC-OF-005).
 * <p>
 * Spoofing: a large order (wall) is placed in the order book to intimidate other participants,
 * then pulled before it can be executed. Detection pairs APPEARED/DISAPPEARED wall events for
 * the same side and price, then scores based on size, duration, and price interaction.
 * <p>
 * Stateless — each call processes a window of recent wall events. No Spring, no I/O.
 */
public final class SpoofingDetector {

    private static final double MAX_DURATION_SECONDS = 10.0;
    private static final double SIZE_MULTIPLE_THRESHOLD = 3.0;
    private static final double SCORE_THRESHOLD = 1.0;
    private static final double MIN_DURATION_FOR_CALC = 0.5;

    /**
     * Evaluate recent wall events for spoofing patterns.
     * <p>
     * Pairs each APPEARED event with the earliest subsequent DISAPPEARED event for the same
     * side + price. If the wall lasted less than 10 seconds and was at least 3x average level
     * size, it is a candidate. Score factors in size ratio, inverse duration, and whether price
     * crossed the spoofed level.
     *
     * @param instrument      the futures instrument
     * @param recentWallEvents wall events in chronological order
     * @param currentPrice    current market price (for price-crossed check)
     * @param avgLevelSize    average order size at a single book level
     * @param now             current timestamp
     * @return list of spoofing signals with score > 1.0
     */
    public List<SpoofingSignal> evaluate(
            Instrument instrument,
            List<WallEvent> recentWallEvents,
            double currentPrice,
            double avgLevelSize,
            Instant now) {

        if (recentWallEvents == null || recentWallEvents.isEmpty() || avgLevelSize <= 0.0) {
            return List.of();
        }

        List<SpoofingSignal> signals = new ArrayList<>();

        // Pair APPEARED with earliest matching DISAPPEARED
        for (int i = 0; i < recentWallEvents.size(); i++) {
            WallEvent appeared = recentWallEvents.get(i);
            if (appeared.type() != WallEventType.APPEARED) {
                continue;
            }

            // Find the first matching DISAPPEARED event after this APPEARED
            WallEvent disappeared = findMatchingDisappearance(recentWallEvents, appeared, i + 1);
            if (disappeared == null) {
                continue;
            }

            double durationSeconds = Duration.between(appeared.timestamp(), disappeared.timestamp()).toMillis() / 1000.0;

            // Filter: must disappear within 10 seconds AND be large relative to average
            if (durationSeconds >= MAX_DURATION_SECONDS) {
                continue;
            }
            if (appeared.size() < SIZE_MULTIPLE_THRESHOLD * avgLevelSize) {
                continue;
            }

            // Check if price crossed through the wall level after the wall disappeared
            boolean priceCrossed = hasPriceCrossed(appeared.side(), appeared.price(), currentPrice);

            // Score: (wallSize / avgSize) * (1 / max(duration, 0.5)) * priceCrossedMultiplier
            double sizeRatio = appeared.size() / avgLevelSize;
            double durationFactor = 1.0 / Math.max(durationSeconds, MIN_DURATION_FOR_CALC);
            double priceCrossedMultiplier = priceCrossed ? 2.0 : 1.0;
            double spoofScore = sizeRatio * durationFactor * priceCrossedMultiplier;

            if (spoofScore <= SCORE_THRESHOLD) {
                continue;
            }

            SpoofSide spoofSide = appeared.side() == WallSide.BID
                    ? SpoofSide.BID_SPOOF
                    : SpoofSide.ASK_SPOOF;

            signals.add(new SpoofingSignal(
                    instrument,
                    spoofSide,
                    appeared.price(),
                    appeared.size(),
                    durationSeconds,
                    priceCrossed,
                    spoofScore,
                    now
            ));
        }

        return List.copyOf(signals);
    }

    /**
     * Find the first DISAPPEARED event matching the given APPEARED event (same side + price).
     */
    private WallEvent findMatchingDisappearance(List<WallEvent> events, WallEvent appeared, int startIndex) {
        for (int j = startIndex; j < events.size(); j++) {
            WallEvent candidate = events.get(j);
            if (candidate.type() == WallEventType.DISAPPEARED
                    && candidate.side() == appeared.side()
                    && Double.compare(candidate.price(), appeared.price()) == 0) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Check if the current price has crossed through the wall price level.
     * A bid wall at price P is crossed if the current price has dropped below P.
     * An ask wall at price P is crossed if the current price has risen above P.
     */
    private boolean hasPriceCrossed(WallSide side, double wallPrice, double currentPrice) {
        return switch (side) {
            case BID -> currentPrice < wallPrice;
            case ASK -> currentPrice > wallPrice;
        };
    }
}

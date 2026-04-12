package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.IcebergSignal;
import com.riskdesk.domain.orderflow.model.IcebergSignal.IcebergSide;
import com.riskdesk.domain.orderflow.model.WallEvent;
import com.riskdesk.domain.orderflow.model.WallEvent.WallEventType;
import com.riskdesk.domain.orderflow.model.WallEvent.WallSide;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure domain service that detects iceberg orders from wall event patterns.
 * NOT a Spring bean — instantiated by application or infrastructure services.
 *
 * <p>An iceberg is a large resting order that hides its true size by only
 * showing a portion in the visible book. As portions are filled, the order
 * "recharges" — a new chunk appears at (approximately) the same price level.</p>
 *
 * <p>Detection logic: group wall events by (side, price level with 1-tick tolerance),
 * then look for APPEARED -> DISAPPEARED -> APPEARED cycles. If 2+ recharge cycles
 * occur within 60 seconds, flag as iceberg.</p>
 */
public class IcebergDetector {

    private static final double RECHARGE_WINDOW_SECONDS = 60.0;
    private static final int MIN_RECHARGE_COUNT = 2;
    private static final double BASE_SCORE_PER_RECHARGE = 25.0;
    private static final double LARGE_SIZE_BONUS = 20.0;
    private static final long LARGE_SIZE_THRESHOLD = 200;
    private static final double MAX_SCORE = 100.0;

    /**
     * Evaluates a list of recent wall events for iceberg patterns.
     *
     * @param instrument   the instrument being analyzed
     * @param recentEvents wall events ordered chronologically (oldest first)
     * @param tickSize     the instrument's tick size for price tolerance grouping
     * @param now          current time for recency filtering
     * @return list of detected iceberg signals (may be empty)
     */
    public List<IcebergSignal> evaluate(Instrument instrument, List<WallEvent> recentEvents,
                                         double tickSize, Instant now) {
        if (recentEvents == null || recentEvents.size() < 3) {
            return List.of(); // need at least APPEARED, DISAPPEARED, APPEARED
        }

        // Group events by (side, rounded price level)
        Map<String, List<WallEvent>> grouped = groupBySideAndPrice(recentEvents, tickSize);

        List<IcebergSignal> signals = new ArrayList<>();

        for (Map.Entry<String, List<WallEvent>> entry : grouped.entrySet()) {
            List<WallEvent> events = entry.getValue();
            IcebergSignal signal = detectRechargePattern(instrument, events, tickSize, now);
            if (signal != null) {
                signals.add(signal);
            }
        }

        return signals;
    }

    /**
     * Groups wall events by composite key: side + rounded price level.
     * Uses 1-tick tolerance for grouping (prices within 1 tick are the same level).
     */
    private Map<String, List<WallEvent>> groupBySideAndPrice(List<WallEvent> events, double tickSize) {
        Map<String, List<WallEvent>> grouped = new LinkedHashMap<>();

        for (WallEvent event : events) {
            double roundedPrice = Math.round(event.price() / tickSize) * tickSize;
            String key = event.side().name() + "|" + roundedPrice;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        }

        return grouped;
    }

    /**
     * Looks for APPEARED -> DISAPPEARED -> APPEARED cycles at the same level.
     * Returns an IcebergSignal if 2+ recharges within the time window.
     */
    private IcebergSignal detectRechargePattern(Instrument instrument, List<WallEvent> events,
                                                  double tickSize, Instant now) {
        if (events.size() < 3) return null;

        int rechargeCount = 0;
        long totalRechargeSize = 0;
        Instant firstAppeared = null;
        Instant lastEvent = null;
        WallSide side = events.get(0).side();
        double priceLevel = Math.round(events.get(0).price() / tickSize) * tickSize;

        // Track state machine: expecting APPEARED first, then alternate DISAPPEARED/APPEARED
        boolean lastWasAppeared = false;

        for (WallEvent event : events) {
            // Filter to recent window
            if (Duration.between(event.timestamp(), now).getSeconds() > RECHARGE_WINDOW_SECONDS) {
                continue;
            }

            if (firstAppeared == null && event.type() == WallEventType.APPEARED) {
                firstAppeared = event.timestamp();
                lastWasAppeared = true;
                totalRechargeSize += event.size();
                lastEvent = event.timestamp();
                continue;
            }

            if (firstAppeared == null) {
                continue; // Skip until we see the first APPEARED
            }

            if (lastWasAppeared && event.type() == WallEventType.DISAPPEARED) {
                lastWasAppeared = false;
                lastEvent = event.timestamp();
            } else if (!lastWasAppeared && event.type() == WallEventType.APPEARED) {
                // This is a recharge: level disappeared then reappeared
                rechargeCount++;
                totalRechargeSize += event.size();
                lastWasAppeared = true;
                lastEvent = event.timestamp();
            }
        }

        if (rechargeCount < MIN_RECHARGE_COUNT || firstAppeared == null || lastEvent == null) {
            return null;
        }

        double durationSeconds = Duration.between(firstAppeared, lastEvent).toMillis() / 1000.0;
        long avgRechargeSize = totalRechargeSize / (rechargeCount + 1); // +1 for initial appearance

        // Score: base per recharge + bonus for large sizes
        double score = Math.min(MAX_SCORE,
            rechargeCount * BASE_SCORE_PER_RECHARGE
            + (avgRechargeSize > LARGE_SIZE_THRESHOLD ? LARGE_SIZE_BONUS : 0));

        IcebergSide icebergSide = (side == WallSide.BID)
            ? IcebergSide.BID_ICEBERG
            : IcebergSide.ASK_ICEBERG;

        return new IcebergSignal(
            instrument, icebergSide, priceLevel,
            rechargeCount, avgRechargeSize, durationSeconds, score, now
        );
    }
}

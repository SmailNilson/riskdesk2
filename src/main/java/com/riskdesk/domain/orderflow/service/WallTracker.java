package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthLevel;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.WallEpisode;
import com.riskdesk.domain.orderflow.model.WallEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure domain service that traces order-book walls (levels flagged at
 * ≥ threshold × average level size) from appearance to outcome. NOT a Spring
 * bean — instantiated per instrument by the application layer.
 *
 * <p>Works off full {@link DepthMetrics} ladder snapshots rather than the raw
 * {@link WallEvent} stream: wall flags are tracked per book <em>index</em> at the
 * source, so a book shift makes the same resting order flicker APPEARED/DISAPPEARED.
 * Keying episodes by price (tick-rounded) and tolerating a grace period absorbs
 * that noise.</p>
 *
 * <p>Outcome classification when a wall stays gone past the grace period:</p>
 * <ul>
 *   <li>{@code CONSUMED} — same-side best price ended within {@code consumedProximityTicks}
 *       of the level (or through it): price reached the wall before it vanished.</li>
 *   <li>{@code OUT_OF_RANGE} — the level scrolled beyond the visible ladder; unknowable.</li>
 *   <li>{@code PULLED} — remaining size ≤ {@code pulledRemnantRatio × maxSize} while price
 *       was still away: the order was cancelled — spoof suspect.</li>
 *   <li>{@code FADED} — size still resting; the level just fell below the relative
 *       threshold (e.g. the side's average grew).</li>
 * </ul>
 *
 * <p>Thread safety: methods are synchronized; a single caller thread is expected.</p>
 */
public class WallTracker {

    /**
     * Tracker thresholds.
     *
     * @param graceSeconds           how long a wall may be missing from the book before
     *                               its episode is finalized (re-flag within grace = same episode)
     * @param minLifetimeSeconds     episodes flagged for less than this are dropped as flicker
     * @param consumedProximityTicks end distance (ticks) at or below which the wall counts
     *                               as CONSUMED
     * @param pulledRemnantRatio     end size below this fraction of max size = PULLED
     * @param minSize                absolute size floor to open an episode (0 = relative only)
     */
    public record Config(
        double graceSeconds,
        double minLifetimeSeconds,
        double consumedProximityTicks,
        double pulledRemnantRatio,
        long minSize
    ) {}

    /** Open episodes kept per side — a deeply abnormal book cannot grow state unbounded. */
    private static final int MAX_OPEN_PER_SIDE = 32;

    private final Instrument instrument;
    private final double tickSize;
    private final Config config;
    private final Map<Long, OpenEpisode> openBids = new LinkedHashMap<>();
    private final Map<Long, OpenEpisode> openAsks = new LinkedHashMap<>();

    private static final class OpenEpisode {
        double price;
        long initialSize;
        long maxSize;
        long lastSize;
        Instant firstSeenAt;
        Instant lastSeenAt;
    }

    public WallTracker(Instrument instrument, double tickSize, Config config) {
        if (tickSize <= 0) {
            throw new IllegalArgumentException("tickSize must be positive, got: " + tickSize);
        }
        this.instrument = instrument;
        this.tickSize = tickSize;
        this.config = config;
    }

    /**
     * Feeds one depth snapshot: refreshes/opens episodes for currently flagged walls,
     * finalizes episodes whose wall has been gone past the grace period.
     *
     * @return episodes closed by this snapshot (flicker shorter than the minimum
     *         lifetime is silently dropped)
     */
    public synchronized List<WallEpisode> onSnapshot(DepthMetrics depth, Instant now) {
        upsertSide(openBids, depth.bids(), now);
        upsertSide(openAsks, depth.asks(), now);

        List<WallEpisode> closed = new ArrayList<>();
        finalizeSide(openBids, WallEvent.WallSide.BID, depth, now, closed);
        finalizeSide(openAsks, WallEvent.WallSide.ASK, depth, now, closed);
        return closed;
    }

    /** Episodes still open, with live duration and distance-from-best. Newest last. */
    public synchronized List<WallEpisode> activeEpisodes(DepthMetrics depth, Instant now) {
        List<WallEpisode> out = new ArrayList<>(openBids.size() + openAsks.size());
        for (OpenEpisode e : openBids.values()) {
            out.add(toEpisode(e, WallEvent.WallSide.BID, null, null,
                seconds(e.firstSeenAt, now), distanceTicks(e.price, WallEvent.WallSide.BID, depth)));
        }
        for (OpenEpisode e : openAsks.values()) {
            out.add(toEpisode(e, WallEvent.WallSide.ASK, null, null,
                seconds(e.firstSeenAt, now), distanceTicks(e.price, WallEvent.WallSide.ASK, depth)));
        }
        return out;
    }

    private void upsertSide(Map<Long, OpenEpisode> open, List<DepthLevel> ladder, Instant now) {
        if (ladder == null) return;
        for (DepthLevel level : ladder) {
            if (!level.wall() || level.size() < config.minSize()) continue;
            long key = priceKey(level.price());
            OpenEpisode existing = open.get(key);
            if (existing != null) {
                existing.lastSize = level.size();
                existing.maxSize = Math.max(existing.maxSize, level.size());
                existing.lastSeenAt = now;
            } else if (open.size() < MAX_OPEN_PER_SIDE) {
                OpenEpisode e = new OpenEpisode();
                e.price = level.price();
                e.initialSize = level.size();
                e.maxSize = level.size();
                e.lastSize = level.size();
                e.firstSeenAt = now;
                e.lastSeenAt = now;
                open.put(key, e);
            }
        }
    }

    private void finalizeSide(Map<Long, OpenEpisode> open, WallEvent.WallSide side,
                              DepthMetrics depth, Instant now, List<WallEpisode> closed) {
        Iterator<OpenEpisode> it = open.values().iterator();
        while (it.hasNext()) {
            OpenEpisode e = it.next();
            if (seconds(e.lastSeenAt, now) <= config.graceSeconds()) continue;
            it.remove();

            double lifetime = seconds(e.firstSeenAt, e.lastSeenAt);
            if (lifetime < config.minLifetimeSeconds()) continue; // book flicker — not a wall story

            double distance = distanceTicks(e.price, side, depth);
            closed.add(toEpisode(e, side, now, classify(e, side, depth, distance), lifetime, distance));
        }
    }

    private WallEpisode.Outcome classify(OpenEpisode e, WallEvent.WallSide side,
                                         DepthMetrics depth, double distanceTicks) {
        double best = side == WallEvent.WallSide.BID ? depth.bestBid() : depth.bestAsk();
        if (best <= 0) return WallEpisode.Outcome.OUT_OF_RANGE; // side empty/unavailable

        if (distanceTicks <= config.consumedProximityTicks()) return WallEpisode.Outcome.CONSUMED;

        List<DepthLevel> ladder = side == WallEvent.WallSide.BID ? depth.bids() : depth.asks();
        if (beyondVisibleLadder(e.price, side, ladder)) return WallEpisode.Outcome.OUT_OF_RANGE;

        long restingSize = restingSizeAt(e.price, ladder);
        if (restingSize <= config.pulledRemnantRatio() * e.maxSize) return WallEpisode.Outcome.PULLED;
        return WallEpisode.Outcome.FADED;
    }

    /** Distance from the wall to the same-side best, in ticks. Negative = traded through. */
    private double distanceTicks(double price, WallEvent.WallSide side, DepthMetrics depth) {
        double best = side == WallEvent.WallSide.BID ? depth.bestBid() : depth.bestAsk();
        if (best <= 0) return Double.NaN;
        double points = side == WallEvent.WallSide.BID ? best - price : price - best;
        return points / tickSize;
    }

    /** True when the price sits deeper than the worst visible level on its side. */
    private boolean beyondVisibleLadder(double price, WallEvent.WallSide side, List<DepthLevel> ladder) {
        if (ladder == null || ladder.isEmpty()) return true;
        double deepest = ladder.get(ladder.size() - 1).price();
        double halfTick = tickSize / 2.0;
        return side == WallEvent.WallSide.BID ? price < deepest - halfTick : price > deepest + halfTick;
    }

    private long restingSizeAt(double price, List<DepthLevel> ladder) {
        if (ladder == null) return 0;
        long key = priceKey(price);
        for (DepthLevel level : ladder) {
            if (priceKey(level.price()) == key) return level.size();
        }
        return 0;
    }

    private WallEpisode toEpisode(OpenEpisode e, WallEvent.WallSide side, Instant endedAt,
                                  WallEpisode.Outcome outcome, double durationSeconds,
                                  double distanceTicks) {
        return new WallEpisode(
            instrument, side, e.price, e.initialSize, e.maxSize, e.lastSize,
            e.firstSeenAt, e.lastSeenAt, endedAt, durationSeconds, outcome, distanceTicks
        );
    }

    private long priceKey(double price) {
        return Math.round(price / tickSize);
    }

    private static double seconds(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / 1000.0;
    }
}

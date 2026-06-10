package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthLevel;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.WallEpisode;
import com.riskdesk.domain.orderflow.model.WallEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wall lifecycle tracking (UC-OF-012): episodes open on flagged ladder levels,
 * survive grace-period flicker, and close with the right outcome. All snapshots
 * use pinned instants — no wall-clock dependence.
 */
class WallTrackerTest {

    private static final double TICK = 0.25; // MNQ
    private static final Instant T0 = Instant.parse("2026-06-10T14:30:00Z");

    private WallTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new WallTracker(Instrument.MNQ, TICK,
            new WallTracker.Config(
                /* graceSeconds */ 5.0,
                /* minLifetimeSeconds */ 3.0,
                /* consumedProximityTicks */ 1.0,
                /* pulledRemnantRatio */ 0.25,
                /* minSize */ 0));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static DepthLevel level(double price, long size, boolean wall) {
        return new DepthLevel(price, size, wall);
    }

    /** Book with best bid 21000.00 / best ask 21000.25, walls injected per side. */
    private static DepthMetrics book(List<DepthLevel> bids, List<DepthLevel> asks, Instant ts) {
        double bestBid = bids.isEmpty() ? 0 : bids.get(0).price();
        double bestAsk = asks.isEmpty() ? 0 : asks.get(0).price();
        return new DepthMetrics(Instrument.MNQ, 100, 100, 0.0,
            bestBid, bestAsk, bestAsk - bestBid, (bestAsk - bestBid) / TICK,
            null, null, bids, asks, ts);
    }

    /** Plain 5-level bid ladder from bestBid going down, sizes 4, with an optional wall level. */
    private static List<DepthLevel> bidLadder(double bestBid, Double wallPrice, long wallSize) {
        java.util.List<DepthLevel> out = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            double p = bestBid - i * TICK;
            boolean isWall = wallPrice != null && Math.abs(p - wallPrice) < TICK / 2;
            out.add(level(p, isWall ? wallSize : 4, isWall));
        }
        return out;
    }

    private static List<DepthLevel> askLadder(double bestAsk) {
        java.util.List<DepthLevel> out = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            out.add(level(bestAsk + i * TICK, 4, false));
        }
        return out;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Test
    void flaggedLevelOpensActiveEpisode_withLiveDistanceAndAge() {
        DepthMetrics d = book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0);
        assertThat(tracker.onSnapshot(d, T0)).isEmpty();

        List<WallEpisode> active = tracker.activeEpisodes(d, T0.plusSeconds(4));
        assertThat(active).hasSize(1);
        WallEpisode ep = active.get(0);
        assertThat(ep.side()).isEqualTo(WallEvent.WallSide.BID);
        assertThat(ep.price()).isEqualTo(20999.50);
        assertThat(ep.maxSize()).isEqualTo(30);
        assertThat(ep.outcome()).isNull();
        assertThat(ep.endedAt()).isNull();
        assertThat(ep.durationSeconds()).isEqualTo(4.0);
        assertThat(ep.endDistanceTicks()).isEqualTo(2.0); // 0.50 pt / 0.25 tick
    }

    @Test
    void wallGrowth_tracksMaxAndLastSize() {
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0), T0);
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 55), askLadder(21000.25), T0.plusSeconds(2)), T0.plusSeconds(2));
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 40), askLadder(21000.25), T0.plusSeconds(4)), T0.plusSeconds(4));

        WallEpisode ep = tracker.activeEpisodes(
            book(bidLadder(21000.00, 20999.50, 40), askLadder(21000.25), T0.plusSeconds(4)),
            T0.plusSeconds(4)).get(0);
        assertThat(ep.initialSize()).isEqualTo(30);
        assertThat(ep.maxSize()).isEqualTo(55);
        assertThat(ep.lastSize()).isEqualTo(40);
    }

    @Test
    void flickerWithinGrace_resumesSameEpisode() {
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0), T0);
        // Wall vanishes for 3s (book shift flicker), then re-flags — within the 5s grace.
        tracker.onSnapshot(book(bidLadder(21000.00, null, 0), askLadder(21000.25), T0.plusSeconds(3)), T0.plusSeconds(3));
        List<WallEpisode> closed = tracker.onSnapshot(
            book(bidLadder(21000.00, 20999.50, 28), askLadder(21000.25), T0.plusSeconds(4)), T0.plusSeconds(4));

        assertThat(closed).isEmpty();
        List<WallEpisode> active = tracker.activeEpisodes(
            book(bidLadder(21000.00, 20999.50, 28), askLadder(21000.25), T0.plusSeconds(4)), T0.plusSeconds(4));
        assertThat(active).hasSize(1);
        assertThat(active.get(0).firstSeenAt()).isEqualTo(T0); // same episode, not a new one
    }

    @Test
    void shortLivedFlicker_isDroppedSilently() {
        // Wall flagged for 2s only (< minLifetime 3s), then gone past grace.
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0), T0);
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0.plusSeconds(2)), T0.plusSeconds(2));
        List<WallEpisode> closed = tracker.onSnapshot(
            book(bidLadder(21000.00, null, 0), askLadder(21000.25), T0.plusSeconds(10)), T0.plusSeconds(10));

        assertThat(closed).isEmpty();
        assertThat(tracker.activeEpisodes(book(bidLadder(21000.00, null, 0), askLadder(21000.25),
            T0.plusSeconds(10)), T0.plusSeconds(10))).isEmpty();
    }

    // ── Outcome classification ──────────────────────────────────────────────

    @Test
    void priceReachesWall_thenGone_classifiedConsumed() {
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0), T0);
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0.plusSeconds(4)), T0.plusSeconds(4));

        // Price sold down: best bid is now AT the old wall price, wall flag gone (eaten).
        DepthMetrics after = book(bidLadder(20999.50, null, 0), askLadder(20999.75), T0.plusSeconds(10));
        List<WallEpisode> closed = tracker.onSnapshot(after, T0.plusSeconds(10));

        assertThat(closed).hasSize(1);
        WallEpisode ep = closed.get(0);
        assertThat(ep.outcome()).isEqualTo(WallEpisode.Outcome.CONSUMED);
        assertThat(ep.endDistanceTicks()).isEqualTo(0.0);
        assertThat(ep.durationSeconds()).isEqualTo(4.0);
        assertThat(ep.endedAt()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void wallCancelledWhilePriceFar_classifiedPulled() {
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0), T0);
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0.plusSeconds(4)), T0.plusSeconds(4));

        // Price never moved; the level now rests at the normal size 4 (< 25% of 30).
        DepthMetrics after = book(bidLadder(21000.00, null, 0), askLadder(21000.25), T0.plusSeconds(10));
        List<WallEpisode> closed = tracker.onSnapshot(after, T0.plusSeconds(10));

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).outcome()).isEqualTo(WallEpisode.Outcome.PULLED);
    }

    @Test
    void sizeStillResting_belowRelativeThreshold_classifiedFaded() {
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0), T0);
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0.plusSeconds(4)), T0.plusSeconds(4));

        // Level still holds 12 contracts (> 25% of max 30) — just not flagged anymore.
        List<DepthLevel> bids = List.of(
            level(21000.00, 4, false), level(20999.75, 4, false), level(20999.50, 12, false),
            level(20999.25, 4, false), level(20999.00, 4, false));
        List<WallEpisode> closed = tracker.onSnapshot(
            book(bids, askLadder(21000.25), T0.plusSeconds(10)), T0.plusSeconds(10));

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).outcome()).isEqualTo(WallEpisode.Outcome.FADED);
    }

    @Test
    void priceMovesAway_wallScrollsOutOfBook_classifiedOutOfRange() {
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0), T0);
        tracker.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0.plusSeconds(4)), T0.plusSeconds(4));

        // Price rallied 10 points: the wall at 20999.50 is far below the visible 5 levels.
        DepthMetrics after = book(bidLadder(21010.00, null, 0), askLadder(21010.25), T0.plusSeconds(10));
        List<WallEpisode> closed = tracker.onSnapshot(after, T0.plusSeconds(10));

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).outcome()).isEqualTo(WallEpisode.Outcome.OUT_OF_RANGE);
    }

    @Test
    void askWall_tradedThrough_classifiedConsumed_withNegativeDistance() {
        List<DepthLevel> asksWithWall = List.of(
            level(21000.25, 4, false), level(21000.50, 35, true), level(21000.75, 4, false),
            level(21001.00, 4, false), level(21001.25, 4, false));
        tracker.onSnapshot(book(bidLadder(21000.00, null, 0), asksWithWall, T0), T0);
        tracker.onSnapshot(book(bidLadder(21000.00, null, 0), asksWithWall, T0.plusSeconds(4)), T0.plusSeconds(4));

        // Price broke through: best ask is now ABOVE the old wall price.
        DepthMetrics after = book(bidLadder(21001.00, null, 0), askLadder(21001.25), T0.plusSeconds(10));
        List<WallEpisode> closed = tracker.onSnapshot(after, T0.plusSeconds(10));

        assertThat(closed).hasSize(1);
        WallEpisode ep = closed.get(0);
        assertThat(ep.side()).isEqualTo(WallEvent.WallSide.ASK);
        assertThat(ep.outcome()).isEqualTo(WallEpisode.Outcome.CONSUMED);
        assertThat(ep.endDistanceTicks()).isEqualTo(-3.0); // 21000.50 - 21001.25 = -0.75 pt
    }

    @Test
    void minSizeFloor_ignoresSmallFlaggedLevels() {
        WallTracker strict = new WallTracker(Instrument.MNQ, TICK,
            new WallTracker.Config(5.0, 3.0, 1.0, 0.25, /* minSize */ 50));
        strict.onSnapshot(book(bidLadder(21000.00, 20999.50, 30), askLadder(21000.25), T0), T0);

        assertThat(strict.activeEpisodes(book(bidLadder(21000.00, 20999.50, 30),
            askLadder(21000.25), T0), T0)).isEmpty();
    }
}

package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthFlowMetrics;
import com.riskdesk.domain.orderflow.model.DepthLevel;
import com.riskdesk.domain.orderflow.model.DepthMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure domain analyzer turning successive {@link DepthMetrics} snapshots into the
 * continuous flow signals of {@link DepthFlowMetrics}. NOT a Spring bean —
 * instantiated per instrument by the application layer (same pattern as
 * {@link WallTracker}). It never touches the live order book; it only consumes
 * the immutable 500ms snapshots, so the hardened MutableOrderBook hot path is
 * left untouched.
 *
 * <h2>1. OFI — Cont, Kukanov &amp; Stoikov (2014)</h2>
 * Per snapshot transition (prev → curr) the best-level order flow imbalance event is
 * <pre>
 *   e = (Pb_c &gt;= Pb_p ? qb_c : 0) - (Pb_c &lt;= Pb_p ? qb_p : 0)
 *     - (Pa_c &lt;= Pa_p ? qa_c : 0) + (Pa_c &gt;= Pa_p ? qa_p : 0)
 * </pre>
 * accumulated over 1s and 10s windows plus a 60s exponentially-decayed running flow.
 * The 10s sum is z-scored against a trailing 5-min distribution of 10s sums.
 * <b>Honest caveat:</b> the canonical R² (~65%, 10s horizon, S&amp;P constituents) is
 * <em>contemporaneous</em> — OFI explains the move happening now far better than the
 * next one. Use it as an entry-timing gauge, not a standalone signal.
 *
 * <h2>2. Queue imbalance + micro-price — Gould &amp; Bonart / Stoikov</h2>
 * I = (qb - qa)/(qb + qa) at the best levels, EMA-smoothed with a ~3s time constant
 * (alpha derived from the actual inter-snapshot dt). Only flagged meaningful when
 * qb+qa ≥ min-queue-mass — on a thin book (MNQ overnight) a 1-lot vs 3-lot "imbalance"
 * is noise. Micro-price = (Pb·qa + Pa·qb)/(qb+qa), exposed as a tick offset from mid.
 *
 * <h2>3. Liquidity vacuum</h2>
 * Rolling 5-min baseline (mean) of each side's total visible depth. One side below
 * {@code vacuumDepletionRatio} of its baseline for ≥ {@code vacuumPersistenceSeconds}
 * while the other holds ≥ {@code vacuumHoldRatio} → VACUUM_BID / VACUUM_ASK; both
 * below {@code thinRatio} → THIN.
 *
 * <h2>4. Pull/stack net flow</h2>
 * For each tick-rounded price level present in both snapshots (same side), the resting
 * size change beyond {@code noiseFloorContracts} is summed over a 10s window into
 * bidPulled/bidStacked/askPulled/askStacked. <b>Approximation:</b> we lack per-level
 * trade attribution, so a size decrease may be a cancel (pull) or an execution; the
 * raw size change is the documented proxy. Levels entering/leaving the visible ladder
 * are ignored (book shifts, not flow).
 *
 * <p><b>Feed gaps:</b> when the gap between successive evaluations exceeds
 * {@code staleGapSeconds} the analyzer resets all internal state — flow must never be
 * computed across a frozen-feed gap. The application layer additionally skips
 * snapshots whose own timestamp is stale.</p>
 *
 * <p>Thread safety: methods are synchronized; a single scheduler thread is expected.</p>
 */
public class DepthFlowAnalyzer {

    /**
     * Analyzer thresholds (see class doc for the semantics of each signal).
     *
     * @param staleGapSeconds          evaluation gap beyond which all state resets
     * @param minQueueMass             best-level qb+qa below this → imbalance flagged not meaningful
     * @param imbalanceEmaSeconds      EMA time constant for queue imbalance smoothing
     * @param noiseFloorContracts      per-level size change at/below this is book noise
     * @param vacuumDepletionRatio     side depth / baseline below this → vacuum candidate
     * @param vacuumHoldRatio          other side must hold at/above this for a vacuum
     * @param thinRatio                both sides below this → THIN
     * @param vacuumPersistenceSeconds vacuum candidate must persist this long before flagging
     * @param ofiZFlagThreshold        |ofiZ10s| at/above this sets {@code ofiExtreme}
     * @param baselineWindowSeconds    rolling window for depth baselines and the OFI z distribution
     */
    public record Config(
        double staleGapSeconds,
        long minQueueMass,
        double imbalanceEmaSeconds,
        long noiseFloorContracts,
        double vacuumDepletionRatio,
        double vacuumHoldRatio,
        double thinRatio,
        double vacuumPersistenceSeconds,
        double ofiZFlagThreshold,
        double baselineWindowSeconds
    ) {}

    private static final double OFI_WINDOW_SHORT_SEC = 1.0;
    private static final double OFI_WINDOW_LONG_SEC = 10.0;
    private static final double OFI_EMA_TAU_SEC = 60.0;
    private static final double PULL_STACK_WINDOW_SEC = 10.0;
    /** Baseline / z-distribution samples required before ratios and z-scores are trusted. */
    private static final int MIN_BASELINE_SAMPLES = 10;
    private static final int MIN_Z_SAMPLES = 20;

    private final Instrument instrument;
    private final double tickSize;
    private final Config config;

    // --- state (all cleared by reset()) -------------------------------------
    private DepthMetrics prev;
    private Instant lastEvalAt;
    /** (epochMillis, e) OFI events, pruned to the 10s window. */
    private final Deque<double[]> ofiEvents = new ArrayDeque<>();
    private double ofiEma60s;
    /** (epochMillis, ofi10s) samples, pruned to the baseline window. */
    private final Deque<double[]> ofiZSamples = new ArrayDeque<>();
    private Double imbalanceEma;
    /** (epochMillis, totalBid, totalAsk) samples, pruned to the baseline window. */
    private final Deque<double[]> depthSamples = new ArrayDeque<>();
    private DepthFlowMetrics.VacuumState vacuumCandidate;
    private Instant vacuumCandidateSince;
    /** (epochMillis, bidPulled, bidStacked, askPulled, askStacked), pruned to 10s. */
    private final Deque<long[]> pullStackEvents = new ArrayDeque<>();

    public DepthFlowAnalyzer(Instrument instrument, double tickSize, Config config) {
        if (tickSize <= 0) {
            throw new IllegalArgumentException("tickSize must be positive, got: " + tickSize);
        }
        this.instrument = instrument;
        this.tickSize = tickSize;
        this.config = config;
    }

    /** Drops all rolling state — called on feed gaps; never compute flow across one. */
    public synchronized void reset() {
        prev = null;
        lastEvalAt = null;
        ofiEvents.clear();
        ofiEma60s = 0.0;
        ofiZSamples.clear();
        imbalanceEma = null;
        depthSamples.clear();
        vacuumCandidate = null;
        vacuumCandidateSince = null;
        pullStackEvents.clear();
    }

    /**
     * Feeds one snapshot. Returns empty on the priming snapshot (no transition yet)
     * or right after a gap-triggered reset; otherwise the full metric set.
     */
    public synchronized Optional<DepthFlowMetrics> onSnapshot(DepthMetrics curr, Instant now) {
        if (curr == null || now == null) return Optional.empty();

        if (lastEvalAt != null && secondsBetween(lastEvalAt, now) > config.staleGapSeconds()) {
            reset(); // gap — the book "movie" is not continuous, all windows are poisoned
        }

        if (prev == null) {
            prime(curr, now);
            return Optional.empty();
        }

        double dt = secondsBetween(lastEvalAt, now);
        if (dt <= 0) {
            // out-of-order / duplicate tick of the scheduler — refresh the snapshot only
            prev = curr;
            return Optional.empty();
        }

        // 1. OFI ---------------------------------------------------------------
        double ofiEvent = computeOfiEvent(prev, curr);
        long nowMs = now.toEpochMilli();
        ofiEvents.addLast(new double[]{nowMs, ofiEvent});
        pruneBefore(ofiEvents, nowMs - (long) (OFI_WINDOW_LONG_SEC * 1000));
        double ofi1s = sumSince(ofiEvents, nowMs - (long) (OFI_WINDOW_SHORT_SEC * 1000));
        double ofi10s = sumSince(ofiEvents, Long.MIN_VALUE);
        ofiEma60s = ofiEma60s * Math.exp(-dt / OFI_EMA_TAU_SEC) + ofiEvent;
        ofiZSamples.addLast(new double[]{nowMs, ofi10s});
        pruneBefore(ofiZSamples, nowMs - (long) (config.baselineWindowSeconds() * 1000));
        double ofiZ = zScore(ofiZSamples, ofi10s);
        boolean ofiExtreme = Math.abs(ofiZ) >= config.ofiZFlagThreshold();

        // 2. Queue imbalance + micro-price --------------------------------------
        long qb = bestSize(curr.bids());
        long qa = bestSize(curr.asks());
        long mass = qb + qa;
        boolean imbalanceValid = mass >= config.minQueueMass() && curr.bestBid() > 0 && curr.bestAsk() > 0;
        double rawImbalance = mass > 0 ? (double) (qb - qa) / mass : 0.0;
        double alpha = 1.0 - Math.exp(-dt / config.imbalanceEmaSeconds());
        imbalanceEma = imbalanceEma == null ? rawImbalance : imbalanceEma + alpha * (rawImbalance - imbalanceEma);
        double microOffsetTicks = 0.0;
        if (mass > 0 && curr.bestBid() > 0 && curr.bestAsk() > 0) {
            double micro = (curr.bestBid() * qa + curr.bestAsk() * qb) / (double) mass;
            double mid = (curr.bestBid() + curr.bestAsk()) / 2.0;
            microOffsetTicks = (micro - mid) / tickSize;
        }

        // 3. Liquidity vacuum ----------------------------------------------------
        depthSamples.addLast(new double[]{nowMs, curr.totalBidSize(), curr.totalAskSize()});
        pruneBefore(depthSamples, nowMs - (long) (config.baselineWindowSeconds() * 1000));
        double bidBaseline = mean(depthSamples, 1);
        double askBaseline = mean(depthSamples, 2);
        boolean baselineWarm = depthSamples.size() >= MIN_BASELINE_SAMPLES;
        double bidRatio = baselineWarm && bidBaseline > 0 ? curr.totalBidSize() / bidBaseline : 1.0;
        double askRatio = baselineWarm && askBaseline > 0 ? curr.totalAskSize() / askBaseline : 1.0;
        DepthFlowMetrics.VacuumState vacuumState = evaluateVacuum(bidRatio, askRatio, now, baselineWarm);

        // 4. Pull/stack net flow ---------------------------------------------------
        long[] flow = computePullStack(prev, curr); // {bidPulled, bidStacked, askPulled, askStacked}
        pullStackEvents.addLast(new long[]{nowMs, flow[0], flow[1], flow[2], flow[3]});
        prunePullStack(pullStackEvents, nowMs - (long) (PULL_STACK_WINDOW_SEC * 1000));
        long bidPulled = sumColumn(pullStackEvents, 1);
        long bidStacked = sumColumn(pullStackEvents, 2);
        long askPulled = sumColumn(pullStackEvents, 3);
        long askStacked = sumColumn(pullStackEvents, 4);
        double baselineDepth = bidBaseline + askBaseline;
        double pullStackScore = baselineWarm && baselineDepth > 0
            ? ((bidStacked - bidPulled) - (askStacked - askPulled)) / baselineDepth
            : 0.0;

        prev = curr;
        lastEvalAt = now;

        return Optional.of(new DepthFlowMetrics(
            instrument,
            ofi1s, ofi10s, ofiEma60s, ofiZ, ofiExtreme,
            imbalanceEma, imbalanceValid, microOffsetTicks,
            vacuumState, bidRatio, askRatio,
            bidPulled, bidStacked, askPulled, askStacked, pullStackScore,
            now
        ));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void prime(DepthMetrics curr, Instant now) {
        prev = curr;
        lastEvalAt = now;
        long nowMs = now.toEpochMilli();
        depthSamples.addLast(new double[]{nowMs, curr.totalBidSize(), curr.totalAskSize()});
    }

    /**
     * Cont-Kukanov-Stoikov best-level OFI event. Price comparisons use tick-rounded
     * keys so floating-point representation of e.g. 21000.25 never misorders levels.
     * Returns 0 when either snapshot misses a side (no defined best-level transition).
     */
    private double computeOfiEvent(DepthMetrics p, DepthMetrics c) {
        if (p.bestBid() <= 0 || p.bestAsk() <= 0 || c.bestBid() <= 0 || c.bestAsk() <= 0) return 0.0;
        long pbPrev = priceKey(p.bestBid());
        long pbCurr = priceKey(c.bestBid());
        long paPrev = priceKey(p.bestAsk());
        long paCurr = priceKey(c.bestAsk());
        long qbPrev = bestSize(p.bids());
        long qbCurr = bestSize(c.bids());
        long qaPrev = bestSize(p.asks());
        long qaCurr = bestSize(c.asks());

        double e = 0.0;
        if (pbCurr >= pbPrev) e += qbCurr;
        if (pbCurr <= pbPrev) e -= qbPrev;
        if (paCurr <= paPrev) e -= qaCurr;
        if (paCurr >= paPrev) e += qaPrev;
        return e;
    }

    private DepthFlowMetrics.VacuumState evaluateVacuum(double bidRatio, double askRatio,
                                                        Instant now, boolean baselineWarm) {
        if (!baselineWarm) {
            vacuumCandidate = null;
            vacuumCandidateSince = null;
            return DepthFlowMetrics.VacuumState.NORMAL;
        }

        DepthFlowMetrics.VacuumState candidate = null;
        if (bidRatio < config.vacuumDepletionRatio() && askRatio >= config.vacuumHoldRatio()) {
            candidate = DepthFlowMetrics.VacuumState.VACUUM_BID;
        } else if (askRatio < config.vacuumDepletionRatio() && bidRatio >= config.vacuumHoldRatio()) {
            candidate = DepthFlowMetrics.VacuumState.VACUUM_ASK;
        }

        if (candidate == null) {
            vacuumCandidate = null;
            vacuumCandidateSince = null;
            return bidRatio < config.thinRatio() && askRatio < config.thinRatio()
                ? DepthFlowMetrics.VacuumState.THIN
                : DepthFlowMetrics.VacuumState.NORMAL;
        }

        if (candidate != vacuumCandidate) {
            vacuumCandidate = candidate;
            vacuumCandidateSince = now;
        }
        if (secondsBetween(vacuumCandidateSince, now) >= config.vacuumPersistenceSeconds()) {
            return candidate;
        }
        // candidate pending the persistence window — not yet a vacuum
        return bidRatio < config.thinRatio() && askRatio < config.thinRatio()
            ? DepthFlowMetrics.VacuumState.THIN
            : DepthFlowMetrics.VacuumState.NORMAL;
    }

    /** {bidPulled, bidStacked, askPulled, askStacked} for one prev→curr transition. */
    private long[] computePullStack(DepthMetrics p, DepthMetrics c) {
        long[] bid = sideFlow(p.bids(), c.bids());
        long[] ask = sideFlow(p.asks(), c.asks());
        return new long[]{bid[0], bid[1], ask[0], ask[1]};
    }

    /** {pulled, stacked} from levels present in BOTH ladders (same tick-rounded price). */
    private long[] sideFlow(List<DepthLevel> prevLadder, List<DepthLevel> currLadder) {
        if (prevLadder == null || currLadder == null || prevLadder.isEmpty() || currLadder.isEmpty()) {
            return new long[]{0, 0};
        }
        Map<Long, Long> prevSizes = new HashMap<>();
        for (DepthLevel level : prevLadder) {
            prevSizes.put(priceKey(level.price()), level.size());
        }
        long pulled = 0;
        long stacked = 0;
        for (DepthLevel level : currLadder) {
            Long before = prevSizes.get(priceKey(level.price()));
            if (before == null) continue; // entered the visible ladder — book shift, not flow
            long delta = level.size() - before;
            if (delta > config.noiseFloorContracts()) stacked += delta;
            else if (-delta > config.noiseFloorContracts()) pulled += -delta;
        }
        return new long[]{pulled, stacked};
    }

    private double zScore(Deque<double[]> samples, double current) {
        if (samples.size() < MIN_Z_SAMPLES) return 0.0;
        double sum = 0;
        for (double[] s : samples) sum += s[1];
        double meanVal = sum / samples.size();
        double sq = 0;
        for (double[] s : samples) {
            double d = s[1] - meanVal;
            sq += d * d;
        }
        double std = Math.sqrt(sq / samples.size());
        return std > 1e-9 ? (current - meanVal) / std : 0.0;
    }

    private static long bestSize(List<DepthLevel> ladder) {
        return ladder == null || ladder.isEmpty() ? 0 : ladder.get(0).size();
    }

    private long priceKey(double price) {
        return Math.round(price / tickSize);
    }

    private static void pruneBefore(Deque<double[]> deque, long cutoffMs) {
        while (!deque.isEmpty() && deque.peekFirst()[0] < cutoffMs) deque.pollFirst();
    }

    private static void prunePullStack(Deque<long[]> deque, long cutoffMs) {
        while (!deque.isEmpty() && deque.peekFirst()[0] < cutoffMs) deque.pollFirst();
    }

    private static double sumSince(Deque<double[]> deque, long sinceMs) {
        double sum = 0;
        for (double[] s : deque) {
            if (s[0] >= sinceMs) sum += s[1];
        }
        return sum;
    }

    private static long sumColumn(Deque<long[]> deque, int col) {
        long sum = 0;
        for (long[] s : deque) sum += s[col];
        return sum;
    }

    private static double mean(Deque<double[]> deque, int col) {
        if (deque.isEmpty()) return 0;
        double sum = 0;
        for (double[] s : deque) sum += s[col];
        return sum / deque.size();
    }

    private static double secondsBetween(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / 1000.0;
    }
}

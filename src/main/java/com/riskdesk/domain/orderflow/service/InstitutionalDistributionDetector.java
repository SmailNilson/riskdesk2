package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionSide;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.DistributionSignal.DistributionType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Detects institutional distribution / accumulation setups from a stream of
 * {@link AbsorptionSignal} events.
 * <p>
 * A distribution setup is characterized by N consecutive BEARISH_ABSORPTION events
 * with an average score above threshold — smart money selling passively into
 * aggressive retail buying. The mirror (BULLISH_ABSORPTION streak) is accumulation.
 * <p>
 * MNQ-tuned defaults (PR #264 follow-up): minCount 3, minAvgScore 2.5,
 * windowTtl 10 min, maxInterEventGap 20s, cooldown 8 min.
 * <p>
 * <b>Stateful — one instance per instrument.</b> Not thread-safe; expected to be
 * called from a single scheduler thread. No Spring, no I/O.
 */
public final class InstitutionalDistributionDetector {

    /** MNQ-tuned default: 3 consecutive same-side absorption events required. */
    public static final int DEFAULT_MIN_CONSECUTIVE_COUNT = 3;
    /** MNQ-tuned default: mean absorption score must exceed 2.5. */
    public static final double DEFAULT_MIN_AVG_SCORE = 2.5;
    /** MNQ-tuned default: sliding window duration. Events older than this are evicted. */
    public static final Duration DEFAULT_WINDOW_TTL = Duration.ofMinutes(10);
    /**
     * MNQ-tuned default: maximum gap between two events in the same streak.
     * A longer gap breaks the streak — 20s is tight enough to keep HFT-driven clusters
     * coherent while rejecting disconnected absorptions.
     */
    public static final Duration DEFAULT_MAX_INTER_EVENT_GAP = Duration.ofSeconds(20);
    /**
     * MNQ-tuned default: cooldown after firing, independent of the window TTL.
     * 8 min allows a second distribution wave (common on MNQ sell-offs) to be detected
     * while still preventing immediate re-fire noise.
     */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(8);

    private final Instrument instrument;
    private final int minConsecutiveCount;
    private final double minAvgScore;
    private final Duration windowTtl;
    private final Duration maxInterEventGap;
    private final Duration cooldown;

    private final Deque<AbsorptionSignal> bearishWindow = new ArrayDeque<>();
    private final Deque<AbsorptionSignal> bullishWindow = new ArrayDeque<>();

    /** When the last signal was emitted for the BEARISH (distribution) side. */
    private Instant lastBearishFireAt = null;
    /** When the last signal was emitted for the BULLISH (accumulation) side. */
    private Instant lastBullishFireAt = null;

    public InstitutionalDistributionDetector(Instrument instrument) {
        this(instrument, DEFAULT_MIN_CONSECUTIVE_COUNT, DEFAULT_MIN_AVG_SCORE,
             DEFAULT_WINDOW_TTL, DEFAULT_MAX_INTER_EVENT_GAP, DEFAULT_COOLDOWN);
    }

    /** Backward-compatible 5-param constructor: cooldown defaults to windowTtl. */
    public InstitutionalDistributionDetector(Instrument instrument,
                                              int minConsecutiveCount,
                                              double minAvgScore,
                                              Duration windowTtl,
                                              Duration maxInterEventGap) {
        this(instrument, minConsecutiveCount, minAvgScore, windowTtl, maxInterEventGap, windowTtl);
    }

    public InstitutionalDistributionDetector(Instrument instrument,
                                              int minConsecutiveCount,
                                              double minAvgScore,
                                              Duration windowTtl,
                                              Duration maxInterEventGap,
                                              Duration cooldown) {
        if (instrument == null) throw new IllegalArgumentException("instrument required");
        if (minConsecutiveCount < 2) throw new IllegalArgumentException("minConsecutiveCount >= 2");
        if (minAvgScore <= 0.0) throw new IllegalArgumentException("minAvgScore > 0");
        this.instrument = instrument;
        this.minConsecutiveCount = minConsecutiveCount;
        this.minAvgScore = minAvgScore;
        this.windowTtl = windowTtl;
        this.maxInterEventGap = maxInterEventGap;
        this.cooldown = cooldown;
    }

    /**
     * Feed an absorption signal and evaluate whether a distribution setup fires.
     *
     * @param signal          absorption signal observed
     * @param currentMidPrice current market mid-price (used as priceAtDetection)
     * @param resistanceLevel optional nearby resistance/support (nullable)
     * @param now             current timestamp
     * @return a distribution signal if threshold crossed, empty otherwise
     */
    public Optional<DistributionSignal> onAbsorption(AbsorptionSignal signal,
                                                     double currentMidPrice,
                                                     Double resistanceLevel,
                                                     Instant now) {
        if (signal == null || signal.side() == null) return Optional.empty();

        // Route to the matching side window, clear the opposite
        if (signal.side() == AbsorptionSide.BEARISH_ABSORPTION) {
            bullishWindow.clear();
            evictStale(bearishWindow, now);
            appendWithGapCheck(bearishWindow, signal);
            return maybeFire(bearishWindow, DistributionType.DISTRIBUTION,
                             currentMidPrice, resistanceLevel, now);
        } else {
            bearishWindow.clear();
            evictStale(bullishWindow, now);
            appendWithGapCheck(bullishWindow, signal);
            return maybeFire(bullishWindow, DistributionType.ACCUMULATION,
                             currentMidPrice, resistanceLevel, now);
        }
    }

    private void appendWithGapCheck(Deque<AbsorptionSignal> window, AbsorptionSignal signal) {
        if (!window.isEmpty()) {
            AbsorptionSignal last = window.peekLast();
            Duration gap = Duration.between(last.timestamp(), signal.timestamp());
            if (gap.compareTo(maxInterEventGap) > 0) {
                window.clear();
            }
        }
        window.addLast(signal);
    }

    private void evictStale(Deque<AbsorptionSignal> window, Instant now) {
        Instant cutoff = now.minus(windowTtl);
        while (!window.isEmpty() && window.peekFirst().timestamp().isBefore(cutoff)) {
            window.pollFirst();
        }
    }

    private Optional<DistributionSignal> maybeFire(Deque<AbsorptionSignal> window,
                                                    DistributionType type,
                                                    double currentMidPrice,
                                                    Double resistanceLevel,
                                                    Instant now) {
        if (window.size() < minConsecutiveCount) return Optional.empty();

        double sum = 0.0;
        long cumDelta = 0;
        long totalVolume = 0;
        for (AbsorptionSignal s : window) {
            sum += s.absorptionScore();
            cumDelta += s.aggressiveDelta();
            totalVolume += s.totalVolume();
        }
        double avg = sum / window.size();
        if (avg < minAvgScore) return Optional.empty();

        // Cooldown: independent of window TTL — allows shorter re-fire window
        Instant lastFire = type == DistributionType.DISTRIBUTION ? lastBearishFireAt : lastBullishFireAt;
        if (lastFire != null && Duration.between(lastFire, now).compareTo(cooldown) < 0) {
            return Optional.empty();
        }

        AbsorptionSignal first = window.peekFirst();
        AbsorptionSignal last = window.peekLast();
        double durationSeconds = Duration.between(first.timestamp(), last.timestamp()).toMillis() / 1000.0;
        int confidence = computeConfidence(window.size(), avg, durationSeconds, cumDelta, totalVolume);

        if (type == DistributionType.DISTRIBUTION) {
            lastBearishFireAt = now;
        } else {
            lastBullishFireAt = now;
        }

        return Optional.of(new DistributionSignal(
            instrument,
            type,
            window.size(),
            avg,
            durationSeconds,
            currentMidPrice,
            resistanceLevel,
            confidence,
            first.timestamp(),
            now
        ));
    }

    /**
     * Confidence 0-95 based on count above threshold, average score, duration,
     * and delta intensity (directional conviction of the streak).
     *
     * <p><b>Recalibrated — 2026-06 production audit + 90-day event study.</b>
     * The previous formula had two structural defects:</p>
     * <ul>
     *   <li><b>Tautological floor.</b> Base was clamped to
     *       {@code min(80, max(50, …))} — a FLOOR of 50, identical to the
     *       GateEvaluator's old veto base tier (50). Every DISTRIBUTION event
     *       therefore armed the structural veto by construction; confidence
     *       carried zero discriminating power at the gate.</li>
     *   <li><b>Saturating score bonus.</b>
     *       {@code min(20, ((avg-minAvg)/minAvg)*20)} maxed out at
     *       {@code avg = 2×minAvg ≈ 5.0}, while real averages span 2.5→50+
     *       (the CLASSIC and DIVERGENCE score formulas are heterogeneous).
     *       Confidence ended up dominated by count/duration, not absorption
     *       strength. The event study confirms the mis-scoring: conf≥70
     *       DISTRIBUTION events were INVERTED at 5 m (price pops UP — 44.6%
     *       hit vs 47.7% baseline) while conf&lt;70 carried a small positive
     *       edge at 15–30 m.</li>
     * </ul>
     *
     * <p>New components:</p>
     * <ul>
     *   <li>Base: {@code 35 + min(15, (count - minConsecutive) × 5)} — floor 35,
     *       meaningfully below the 60 veto base tier</li>
     *   <li>Score bonus: {@code min(25, 25 × log1p(max(0, avg - minAvgScore)) / log1p(20))}
     *       — log compression discriminates across the real 2.5→50+ range
     *       instead of saturating at 5.0</li>
     *   <li>Duration bonus: up to +30 for sustained patterns (10+ min), unchanged</li>
     *   <li>Intensity bonus: up to +15 for high |cumDelta| / totalVolume ratio, unchanged</li>
     *   <li>Total capped at 95</li>
     * </ul>
     *
     * <p>Net effect: a minimal streak (3 events scraping the score floor,
     * short duration) lands ~40-50 — below the veto tier; only sustained,
     * high-score streaks reach 70+.</p>
     */
    private int computeConfidence(int count, double avgScore, double durationSeconds,
                                   long cumDelta, long totalVolume) {
        double base = 35.0 + Math.min(15.0, (count - minConsecutiveCount) * 5.0);

        double scoreBonus = Math.min(25.0,
            25.0 * Math.log1p(Math.max(0.0, avgScore - minAvgScore)) / Math.log1p(20.0));

        double durationMinutes = durationSeconds / 60.0;
        double durationBonus = Math.min(30.0, (durationMinutes / 10.0) * 30.0);
        durationBonus = Math.max(0.0, durationBonus);

        double deltaIntensity = totalVolume > 0 ? Math.abs(cumDelta) / (double) totalVolume : 0.0;
        double intensityBonus = Math.min(15.0, deltaIntensity * 15.0);

        int confidence = (int) Math.round(base + scoreBonus + durationBonus + intensityBonus);
        return Math.max(0, Math.min(95, confidence));
    }

    /** Reset all state (e.g. after session boundary). */
    public void reset() {
        bearishWindow.clear();
        bullishWindow.clear();
        lastBearishFireAt = null;
        lastBullishFireAt = null;
    }
}

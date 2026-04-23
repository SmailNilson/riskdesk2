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
 * <b>Stateful — one instance per instrument.</b> Not thread-safe; expected to be
 * called from a single scheduler thread. No Spring, no I/O.
 */
public final class InstitutionalDistributionDetector {

    /** Default: 5 consecutive same-side absorption events required. */
    public static final int DEFAULT_MIN_CONSECUTIVE_COUNT = 5;
    /** Default: mean absorption score must exceed 3.0. */
    public static final double DEFAULT_MIN_AVG_SCORE = 3.0;
    /** Default: sliding window duration. Events older than this are evicted. */
    public static final Duration DEFAULT_WINDOW_TTL = Duration.ofMinutes(15);
    /**
     * Maximum gap between two events in the same streak. A longer gap breaks the
     * streak (one missed 5s tick cycle is fine; beyond 30s we consider the pattern
     * interrupted).
     */
    public static final Duration DEFAULT_MAX_INTER_EVENT_GAP = Duration.ofSeconds(30);

    private final Instrument instrument;
    private final int minConsecutiveCount;
    private final double minAvgScore;
    private final Duration windowTtl;
    private final Duration maxInterEventGap;

    private final Deque<AbsorptionSignal> bearishWindow = new ArrayDeque<>();
    private final Deque<AbsorptionSignal> bullishWindow = new ArrayDeque<>();

    /** When the last signal was emitted for the BEARISH (distribution) side. */
    private Instant lastBearishFireAt = null;
    /** When the last signal was emitted for the BULLISH (accumulation) side. */
    private Instant lastBullishFireAt = null;

    public InstitutionalDistributionDetector(Instrument instrument) {
        this(instrument, DEFAULT_MIN_CONSECUTIVE_COUNT, DEFAULT_MIN_AVG_SCORE,
             DEFAULT_WINDOW_TTL, DEFAULT_MAX_INTER_EVENT_GAP);
    }

    public InstitutionalDistributionDetector(Instrument instrument,
                                              int minConsecutiveCount,
                                              double minAvgScore,
                                              Duration windowTtl,
                                              Duration maxInterEventGap) {
        if (instrument == null) throw new IllegalArgumentException("instrument required");
        if (minConsecutiveCount < 2) throw new IllegalArgumentException("minConsecutiveCount >= 2");
        if (minAvgScore <= 0.0) throw new IllegalArgumentException("minAvgScore > 0");
        this.instrument = instrument;
        this.minConsecutiveCount = minConsecutiveCount;
        this.minAvgScore = minAvgScore;
        this.windowTtl = windowTtl;
        this.maxInterEventGap = maxInterEventGap;
    }

    /**
     * Feed an absorption signal and evaluate whether a distribution setup fires.
     *
     * @param signal         absorption signal observed
     * @param currentMidPrice current market mid-price (used as priceAtDetection)
     * @param resistanceLevel optional nearby resistance/support (nullable)
     * @param now            current timestamp
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
        double max = 0.0;
        for (AbsorptionSignal s : window) {
            sum += s.absorptionScore();
            if (s.absorptionScore() > max) max = s.absorptionScore();
        }
        double avg = sum / window.size();
        if (avg < minAvgScore) return Optional.empty();

        // Cooldown: don't re-fire for the same side inside the TTL window
        Instant lastFire = type == DistributionType.DISTRIBUTION ? lastBearishFireAt : lastBullishFireAt;
        if (lastFire != null && Duration.between(lastFire, now).compareTo(windowTtl) < 0) {
            return Optional.empty();
        }

        AbsorptionSignal first = window.peekFirst();
        AbsorptionSignal last = window.peekLast();
        double durationSeconds = Duration.between(first.timestamp(), last.timestamp()).toMillis() / 1000.0;
        int confidence = computeConfidence(window.size(), avg, durationSeconds);

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
     * Confidence 0-100 based on count above threshold, average score, and duration.
     * <ul>
     *   <li>Base: 50 at threshold, climbs with extra consecutive events</li>
     *   <li>Score bonus: up to +20 for average score well above threshold</li>
     *   <li>Duration bonus: up to +30 for sustained patterns (10+ min)</li>
     * </ul>
     */
    private int computeConfidence(int count, double avgScore, double durationSeconds) {
        double base = 50.0 + ((double) (count - minConsecutiveCount) / minConsecutiveCount) * 30.0;
        base = Math.min(80.0, Math.max(50.0, base));

        double scoreBonus = Math.min(20.0, ((avgScore - minAvgScore) / minAvgScore) * 20.0);
        scoreBonus = Math.max(0.0, scoreBonus);

        double durationMinutes = durationSeconds / 60.0;
        double durationBonus = Math.min(30.0, (durationMinutes / 10.0) * 30.0);
        durationBonus = Math.max(0.0, durationBonus);

        int confidence = (int) Math.round(base + scoreBonus + durationBonus);
        return Math.max(0, Math.min(100, confidence));
    }

    /** Reset all state (e.g. after session boundary). */
    public void reset() {
        bearishWindow.clear();
        bullishWindow.clear();
        lastBearishFireAt = null;
        lastBullishFireAt = null;
    }
}

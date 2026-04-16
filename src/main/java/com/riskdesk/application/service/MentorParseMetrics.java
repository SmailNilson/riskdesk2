package com.riskdesk.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight in-process counters tracking how {@link MentorAnalysisService}
 * consumes Gemini replies.
 *
 * <p>Motivation: a prod audit showed ~96% of tradable SIGNAL reviews silently
 * fell back to "Structured mentor response unavailable" because the thinking
 * model's reasoning trace was eating the {@code maxOutputTokens} budget. The
 * UI displayed these as real "Trade Non-Conforme" rejections. Without a
 * visible failure rate, that regression can reappear any time the Gemini
 * response schema grows or the {@code thinkingBudget} property drifts.
 *
 * <p>Counters are kept in memory (no Micrometer dep). The {@code
 * MentorController} exposes a snapshot endpoint so operators can read the
 * current ratio without tailing logs.
 *
 * <p>Kept deliberately minimal:
 * <ul>
 *   <li>{@link #recordSuccess()} — strict JSON parse succeeded.</li>
 *   <li>{@link #recordRecovered()} — partial JSON repair salvaged some fields.</li>
 *   <li>{@link #recordFailure()} — could not consume the reply at all.</li>
 *   <li>{@link #recordTruncatedButValidated()} — recovered response whose raw text
 *       carried an {@code ELIGIBLE} / {@code Trade Validé} verdict that we then
 *       had to force to {@code MENTOR_UNAVAILABLE} because the trade plan was
 *       lost in the truncation. This is the "missed trade" counter: it lets
 *       operators see how many tradeable setups the system is throwing away
 *       because of response-size pressure, independently of the overall parse-
 *       failure ratio.</li>
 * </ul>
 */
@Component
public class MentorParseMetrics {

    private static final Logger log = LoggerFactory.getLogger(MentorParseMetrics.class);
    // Alert log line if the technical-failure ratio breaches this; keep it
    // quiet otherwise to avoid log spam in healthy states.
    private static final double WARN_RATIO_THRESHOLD = 0.10;
    // Don't alert on tiny samples — one failure out of two shouldn't trip.
    private static final long MIN_SAMPLES_FOR_WARN = 20L;

    private final AtomicLong success = new AtomicLong();
    private final AtomicLong recovered = new AtomicLong();
    private final AtomicLong failure = new AtomicLong();
    // "Missed trade" counter: recovered responses where Gemini had explicitly
    // validated the trade (ELIGIBLE / Trade Validé) but the plan was lost in
    // the truncation, forcing us to mark them MENTOR_UNAVAILABLE anyway.
    private final AtomicLong truncatedButValidated = new AtomicLong();
    // Flips true on the first call that crosses the warn threshold, reset to
    // false once the ratio drops back below it. Ensures we log one line per
    // breach transition instead of one line per call while the ratio sits
    // above threshold.
    private final AtomicBoolean breachLogged = new AtomicBoolean(false);

    public void recordSuccess() {
        success.incrementAndGet();
    }

    public void recordRecovered() {
        recovered.incrementAndGet();
        checkFailureRatio();
    }

    public void recordFailure() {
        failure.incrementAndGet();
        checkFailureRatio();
    }

    /**
     * Records a recovered response that carried an explicit positive verdict
     * ({@code ELIGIBLE} / {@code Trade Validé}) in its raw text but had to be
     * forced to {@code MENTOR_UNAVAILABLE} because the trade plan couldn't
     * be reconstructed. Surfaces as {@code missedTrades} in the snapshot.
     * Always paired with {@link #recordRecovered()} — callers must not call
     * this instead of it.
     */
    public void recordTruncatedButValidated() {
        truncatedButValidated.incrementAndGet();
    }

    public Snapshot snapshot() {
        long s = success.get();
        long r = recovered.get();
        long f = failure.get();
        long missed = truncatedButValidated.get();
        long total = s + r + f;
        double techFailureRatio = total == 0 ? 0.0 : (double) (r + f) / (double) total;
        return new Snapshot(s, r, f, total, techFailureRatio, missed);
    }

    private void checkFailureRatio() {
        Snapshot snap = snapshot();
        if (snap.total() < MIN_SAMPLES_FOR_WARN) {
            return;
        }
        boolean breached = snap.techFailureRatio() >= WARN_RATIO_THRESHOLD;
        if (breached && breachLogged.compareAndSet(false, true)) {
            log.warn(
                "Mentor parse-failure ratio breached threshold: {}% (success={}, recovered={}, failure={}, total={}). "
                + "Likely cause: Gemini response truncation (thinkingBudget too low) or schema drift. "
                + "Inspect /api/mentor/diagnostics/parse-stats for the live snapshot.",
                String.format("%.1f", snap.techFailureRatio() * 100.0),
                snap.success(), snap.recovered(), snap.failure(), snap.total()
            );
        } else if (!breached && breachLogged.compareAndSet(true, false)) {
            log.info(
                "Mentor parse-failure ratio recovered below threshold: {}% (success={}, recovered={}, failure={}, total={}).",
                String.format("%.1f", snap.techFailureRatio() * 100.0),
                snap.success(), snap.recovered(), snap.failure(), snap.total()
            );
        }
    }

    public record Snapshot(
        long success,
        long recovered,
        long failure,
        long total,
        double techFailureRatio,
        long missedTrades
    ) {}
}

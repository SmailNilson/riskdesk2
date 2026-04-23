package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.DistributionSignal.DistributionType;
import com.riskdesk.domain.orderflow.model.MomentumSignal;
import com.riskdesk.domain.orderflow.model.MomentumSignal.MomentumSide;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal.CyclePhase;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal.CycleType;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Meta-detector that chains the three order-flow phases into a smart-money cycle.
 * <p>
 * State machine:
 * <pre>
 *   NONE
 *     ─ DistributionSignal (DISTRIBUTION | ACCUMULATION) ──▶ PHASE_1
 *         ─ MomentumSignal (same direction, within momentumWindow) ─▶ PHASE_2
 *             ─ DistributionSignal (mirror, within mirrorWindow) ───▶ PHASE_3 → fires COMPLETE
 *         (otherwise timeout → NONE)
 *     (timeout → NONE)
 * </pre>
 * <p>
 * <b>Stateful — one instance per instrument.</b> Not thread-safe.
 */
public final class DistributionCycleDetector {

    /** Max gap between phase 1 and phase 2. */
    public static final Duration DEFAULT_MOMENTUM_WINDOW = Duration.ofMinutes(15);
    /** Max gap between phase 2 and phase 3. */
    public static final Duration DEFAULT_MIRROR_WINDOW = Duration.ofMinutes(30);
    /** Cooldown after a complete cycle. */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(5);

    private enum State { NONE, PHASE_1, PHASE_2 }

    private final Instrument instrument;
    private final Duration momentumWindow;
    private final Duration mirrorWindow;
    private final Duration cooldown;

    private State state = State.NONE;
    private CycleType pendingCycleType = null;
    private Instant phase1Time = null;
    private Double phase1Price = null;
    private double phase1Confidence = 0.0;
    private Instant phase2Time = null;
    private Double phase2Price = null;
    private double phase2Score = 0.0;
    private Instant lastCompletedAt = null;

    public DistributionCycleDetector(Instrument instrument) {
        this(instrument, DEFAULT_MOMENTUM_WINDOW, DEFAULT_MIRROR_WINDOW, DEFAULT_COOLDOWN);
    }

    public DistributionCycleDetector(Instrument instrument,
                                      Duration momentumWindow,
                                      Duration mirrorWindow,
                                      Duration cooldown) {
        if (instrument == null) throw new IllegalArgumentException("instrument required");
        this.instrument = instrument;
        this.momentumWindow = momentumWindow;
        this.mirrorWindow = mirrorWindow;
        this.cooldown = cooldown;
    }

    /** Feed a distribution/accumulation signal. May advance or reset the state machine. */
    public Optional<SmartMoneyCycleSignal> onDistribution(DistributionSignal signal, Instant now) {
        if (signal == null) return Optional.empty();
        if (inCooldown(now)) return Optional.empty();

        switch (state) {
            case NONE -> {
                enterPhase1(signal, now);
                return emitPartial(signal.priceAtDetection(), now, CyclePhase.PHASE_1);
            }
            case PHASE_1 -> {
                // Still in phase 1 — replace with a stronger or newer setup on the same side
                if (matchesPhase1Direction(signal)) {
                    enterPhase1(signal, now);
                    return Optional.empty();
                } else {
                    // Opposite direction before momentum → reset to new phase 1
                    enterPhase1(signal, now);
                    return Optional.empty();
                }
            }
            case PHASE_2 -> {
                // Expecting a MIRROR distribution to close phase 3
                if (isMirrorOf(pendingCycleType, signal)) {
                    SmartMoneyCycleSignal completed = buildComplete(signal, now);
                    resetInternal();
                    lastCompletedAt = now;
                    return Optional.of(completed);
                } else {
                    // Same-side distribution during phase 2 — invalidate cycle, restart as new phase 1
                    enterPhase1(signal, now);
                    return Optional.empty();
                }
            }
            default -> { return Optional.empty(); }
        }
    }

    /** Feed a momentum burst. Advances PHASE_1 → PHASE_2 when directionally consistent. */
    public Optional<SmartMoneyCycleSignal> onMomentum(MomentumSignal signal, Instant now) {
        if (signal == null) return Optional.empty();
        if (state != State.PHASE_1) return Optional.empty();
        if (phase1Time != null && Duration.between(phase1Time, now).compareTo(momentumWindow) > 0) {
            // Phase 1 expired
            resetInternal();
            return Optional.empty();
        }
        if (!momentumConsistentWithCycle(pendingCycleType, signal)) {
            return Optional.empty();
        }
        state = State.PHASE_2;
        phase2Time = now;
        phase2Price = averagePrice(signal.priceMovePoints(), phase1Price);
        phase2Score = signal.momentumScore();
        return emitPartial(signal.priceMovePoints() != 0.0
                ? phase1Price + (signal.priceMovePoints() / 2.0)
                : phase1Price, now, CyclePhase.PHASE_2);
    }

    /**
     * Called periodically to expire stale phases.
     * Returns a terminal partial signal if a phase timed out (for visibility), otherwise empty.
     */
    public Optional<SmartMoneyCycleSignal> tick(Instant now) {
        if (state == State.PHASE_1 && phase1Time != null
            && Duration.between(phase1Time, now).compareTo(momentumWindow) > 0) {
            resetInternal();
            return Optional.empty();
        }
        if (state == State.PHASE_2 && phase2Time != null
            && Duration.between(phase2Time, now).compareTo(mirrorWindow) > 0) {
            resetInternal();
            return Optional.empty();
        }
        return Optional.empty();
    }

    public void reset() { resetInternal(); lastCompletedAt = null; }

    // ---------- helpers ----------

    private void enterPhase1(DistributionSignal signal, Instant now) {
        state = State.PHASE_1;
        pendingCycleType = (signal.type() == DistributionType.DISTRIBUTION)
            ? CycleType.BEARISH_CYCLE
            : CycleType.BULLISH_CYCLE;
        phase1Time = now;
        phase1Price = signal.priceAtDetection();
        phase1Confidence = signal.confidenceScore();
        phase2Time = null;
        phase2Price = null;
        phase2Score = 0.0;
    }

    private boolean matchesPhase1Direction(DistributionSignal signal) {
        if (pendingCycleType == null) return false;
        return (pendingCycleType == CycleType.BEARISH_CYCLE && signal.type() == DistributionType.DISTRIBUTION)
            || (pendingCycleType == CycleType.BULLISH_CYCLE && signal.type() == DistributionType.ACCUMULATION);
    }

    private boolean momentumConsistentWithCycle(CycleType cycle, MomentumSignal signal) {
        if (cycle == null) return false;
        return (cycle == CycleType.BEARISH_CYCLE && signal.side() == MomentumSide.BEARISH_MOMENTUM)
            || (cycle == CycleType.BULLISH_CYCLE && signal.side() == MomentumSide.BULLISH_MOMENTUM);
    }

    private boolean isMirrorOf(CycleType cycle, DistributionSignal signal) {
        if (cycle == null) return false;
        return (cycle == CycleType.BEARISH_CYCLE && signal.type() == DistributionType.ACCUMULATION)
            || (cycle == CycleType.BULLISH_CYCLE && signal.type() == DistributionType.DISTRIBUTION);
    }

    private boolean inCooldown(Instant now) {
        return lastCompletedAt != null && Duration.between(lastCompletedAt, now).compareTo(cooldown) < 0;
    }

    private Double averagePrice(double move, Double reference) {
        if (reference == null) return null;
        return reference + (move / 2.0);
    }

    private Optional<SmartMoneyCycleSignal> emitPartial(double currentPrice, Instant now, CyclePhase phase) {
        double durationMinutes = phase1Time != null
            ? Duration.between(phase1Time, now).toMillis() / 60000.0
            : 0.0;
        int confidence = computeConfidence(phase, 0.0);
        return Optional.of(new SmartMoneyCycleSignal(
            instrument,
            pendingCycleType,
            phase,
            phase1Price != null ? phase1Price : currentPrice,
            phase2Price,
            null,
            0.0,
            durationMinutes,
            confidence,
            phase1Time != null ? phase1Time : now,
            null
        ));
    }

    private SmartMoneyCycleSignal buildComplete(DistributionSignal phase3, Instant now) {
        double totalMove = Math.abs(phase3.priceAtDetection() - (phase1Price != null ? phase1Price : phase3.priceAtDetection()));
        double durationMinutes = phase1Time != null
            ? Duration.between(phase1Time, now).toMillis() / 60000.0
            : 0.0;
        int confidence = computeConfidence(CyclePhase.COMPLETE, phase3.confidenceScore());
        return new SmartMoneyCycleSignal(
            instrument,
            pendingCycleType,
            CyclePhase.COMPLETE,
            phase1Price != null ? phase1Price : phase3.priceAtDetection(),
            phase2Price,
            phase3.priceAtDetection(),
            totalMove,
            durationMinutes,
            confidence,
            phase1Time != null ? phase1Time : now,
            now
        );
    }

    /**
     * Confidence 0-100 combining phase completion and per-phase signal quality.
     * <ul>
     *   <li>PHASE_1 partial: 40 base + proportional to phase 1 confidence (max 55)</li>
     *   <li>PHASE_2 partial: 50 base + momentum score contribution (max 70)</li>
     *   <li>COMPLETE: 70 base + phase 3 confidence (max 100)</li>
     * </ul>
     */
    private int computeConfidence(CyclePhase phase, double phase3Confidence) {
        double base;
        double bonus;
        switch (phase) {
            case PHASE_1 -> {
                base = 40.0;
                bonus = (phase1Confidence / 100.0) * 15.0;
            }
            case PHASE_2 -> {
                base = 50.0;
                bonus = Math.min(20.0, (phase2Score / 5.0) * 20.0);
            }
            case PHASE_3, COMPLETE -> {
                base = 70.0;
                bonus = (phase3Confidence / 100.0) * 30.0;
            }
            default -> { base = 0.0; bonus = 0.0; }
        }
        int score = (int) Math.round(base + bonus);
        return Math.max(0, Math.min(100, score));
    }

    private void resetInternal() {
        state = State.NONE;
        pendingCycleType = null;
        phase1Time = null;
        phase1Price = null;
        phase1Confidence = 0.0;
        phase2Time = null;
        phase2Price = null;
        phase2Score = 0.0;
    }
}

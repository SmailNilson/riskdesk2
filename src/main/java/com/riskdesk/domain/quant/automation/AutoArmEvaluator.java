package com.riskdesk.domain.quant.automation;

import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.shared.TradingSessionResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Pure, framework-free evaluator deciding whether a {@link QuantSnapshot}
 * qualifies for auto-arming an execution. Used by the application-layer
 * {@code QuantAutoArmService}.
 *
 * <h3>Gates (in order)</h3>
 * <ol>
 *   <li>At least one direction must be available
 *       ({@code shortAvailable() || longAvailable()}).</li>
 *   <li>If both are available, the higher-scoring direction wins (tie-break: SHORT,
 *       matching legacy behaviour for SHORT-only deployments).</li>
 *   <li>{@link QuantSnapshot#score()} (raw 7-gate, before structural modifier)
 *       must be at least {@link AutoArmConfig#minScore()}.</li>
 *   <li>The instrument must NOT have an active execution
 *       (any non-terminal {@link ExecutionStatus}).</li>
 *   <li>Cooldown: caller passes {@code lastArmAt} — if {@code now - lastArmAt}
 *       is less than {@link AutoArmConfig#cooldownDuration()}, skip.</li>
 *   <li>Kill-zone: {@link TradingSessionResolver#isWithinKillZone(Instant)}
 *       must return true (London 02:00–05:00 ET or NY 08:30–11:00 ET).</li>
 * </ol>
 *
 * <p>When all gates pass, the evaluator builds an {@link AutoArmDecision} with
 * Entry/SL/TP from the snapshot's per-direction suggestions and a
 * {@code sizePercent} reduced by the number of structural warnings:
 * <code>0 warnings → 100 %</code>, <code>1–2 → 50 %</code>, <code>3+ → 25 %</code>.</p>
 */
public final class AutoArmEvaluator {

    /** Statuses that count as "active" — block a new auto-arm on the same instrument. */
    private static final Set<ExecutionStatus> ACTIVE_STATUSES = EnumSet.of(
        ExecutionStatus.PENDING_ENTRY_SUBMISSION,
        ExecutionStatus.ENTRY_SUBMITTED,
        ExecutionStatus.ENTRY_PARTIALLY_FILLED,
        ExecutionStatus.ACTIVE,
        ExecutionStatus.VIRTUAL_EXIT_TRIGGERED,
        ExecutionStatus.EXIT_SUBMITTED
    );

    /**
     * Evaluate whether the snapshot qualifies for auto-arming.
     *
     * @param snap                    the latest quant snapshot
     * @param cfg                     auto-arm config (min score, expire, cooldown, default size)
     * @param activeOnInstrument      currently-active execution on this instrument, if any
     * @param lastArmAt               timestamp of the most recent auto-arm for this instrument,
     *                                or {@code null} if none yet (cooldown bypassed)
     * @param now                     current UTC time (injected for testability)
     * @return {@link Optional#empty()} if any gate fails; otherwise the decision
     */
    public Optional<AutoArmDecision> evaluate(QuantSnapshot snap,
                                               AutoArmConfig cfg,
                                               Optional<TradeExecutionRecord> activeOnInstrument,
                                               Instant lastArmAt,
                                               Instant now) {
        if (snap == null || cfg == null || now == null) return Optional.empty();
        if (snap.price() == null) return Optional.empty();

        QuantSnapshotDirectionView view = new QuantSnapshotDirectionView(snap);
        AutoArmDirection direction = pickDirection(view);
        if (direction == null) return Optional.empty();

        // Gate: raw 7-gate score >= minScore for the chosen direction. Uses
        // the per-direction score so a strong LONG (longScore=7) is not
        // penalised by a weak SHORT score on the same snapshot. SHORT path
        // uses snap.score() (legacy), LONG uses snap.longScore() via the view.
        int chosenScore = view.rawScore(direction, snap);
        if (chosenScore < cfg.minScore()) return Optional.empty();

        // Gate: no active execution on this instrument.
        if (activeOnInstrument.isPresent() && ACTIVE_STATUSES.contains(activeOnInstrument.get().getStatus())) {
            return Optional.empty();
        }

        // Gate: cooldown elapsed.
        if (lastArmAt != null && now.isBefore(lastArmAt.plusSeconds(cfg.cooldownSeconds()))) {
            return Optional.empty();
        }

        // Gate: kill-zone active.
        if (!TradingSessionResolver.isWithinKillZone(now)) return Optional.empty();

        // Build the decision.
        Double entry = view.suggestedEntry(direction);
        Double sl    = view.suggestedSL(direction);
        Double tp1   = view.suggestedTP1(direction);
        Double tp2   = view.suggestedTP2(direction);
        if (entry == null || sl == null || tp1 == null) return Optional.empty();

        double sizePercent = sizeFor(cfg.sizePercentDefault(), snap.structuralWarnings().size());
        Instant expiresAt = now.plusSeconds(cfg.expireSeconds());
        String reasoning = buildReasoning(direction, snap, view);

        return Optional.of(new AutoArmDecision(
            direction,
            toBigDecimal(entry),
            toBigDecimal(sl),
            toBigDecimal(tp1),
            tp2 == null ? null : toBigDecimal(tp2),
            sizePercent,
            now,
            expiresAt,
            reasoning
        ));
    }

    /**
     * Pick the direction to arm. Returns {@code null} if neither is available.
     * If both are available, higher score wins; SHORT breaks ties (legacy default).
     */
    private AutoArmDirection pickDirection(QuantSnapshotDirectionView view) {
        boolean shortOk = view.isAvailable(AutoArmDirection.SHORT);
        boolean longOk  = view.isAvailable(AutoArmDirection.LONG);
        if (!shortOk && !longOk) return null;
        if (shortOk && !longOk) return AutoArmDirection.SHORT;
        if (longOk && !shortOk) return AutoArmDirection.LONG;
        // Both available: pick higher score.
        int shortScore = view.score(AutoArmDirection.SHORT);
        int longScore  = view.score(AutoArmDirection.LONG);
        if (longScore > shortScore) return AutoArmDirection.LONG;
        return AutoArmDirection.SHORT;
    }

    private static double sizeFor(double base, int warningCount) {
        if (warningCount <= 0) return base;
        if (warningCount <= 2) return base * 0.50;
        return base * 0.25;
    }

    private static BigDecimal toBigDecimal(Double v) {
        // 4-decimal precision is enough for all instruments above tick-size; the
        // application layer rounds to instrument tick before persisting.
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static String buildReasoning(AutoArmDirection dir, QuantSnapshot snap, QuantSnapshotDirectionView view) {
        StringBuilder sb = new StringBuilder();
        sb.append(dir.name()).append(" auto-arm");
        sb.append(" — score ").append(snap.score()).append("/7");
        if (snap.structuralScoreModifier() != 0) {
            sb.append(" (final ").append(snap.finalScore()).append(")");
        }
        if (!snap.structuralWarnings().isEmpty()) {
            sb.append(" with ").append(snap.structuralWarnings().size()).append(" warning(s)");
        }
        if (dir == AutoArmDirection.LONG && view.longSupportPresent()) {
            sb.append(" — LONG-side ").append(view.score(AutoArmDirection.LONG));
        }
        return sb.toString();
    }
}

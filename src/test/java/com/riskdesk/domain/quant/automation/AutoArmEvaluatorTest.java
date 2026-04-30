package com.riskdesk.domain.quant.automation;

import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.structure.StructuralWarning;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AutoArmEvaluator}. The evaluator is pure — every test
 * fully controls the snapshot, config, active execution, last-arm time and
 * the {@code now} clock.
 *
 * <p>Time picks: {@link #NY_KILL_ZONE} is 13:30 UTC = 09:30 ET, inside NY
 * kill zone (08:30–11:00 ET). {@link #OUT_OF_KILL_ZONE} is 17:00 UTC = 13:00
 * ET, well outside both London and NY kill zones.</p>
 */
class AutoArmEvaluatorTest {

    private static final Instant NY_KILL_ZONE = ZonedDateTime
        .of(2026, 4, 30, 9, 30, 0, 0, ZoneId.of("America/New_York"))
        .toInstant();
    private static final Instant OUT_OF_KILL_ZONE = ZonedDateTime
        .of(2026, 4, 30, 13, 0, 0, 0, ZoneId.of("America/New_York"))
        .toInstant();

    private static final AutoArmConfig CFG = AutoArmConfig.defaults();
    private final AutoArmEvaluator evaluator = new AutoArmEvaluator();

    @Test
    void short_only_snapshot_arms_short() {
        QuantSnapshot snap = shortReadySnapshot(7);
        Optional<AutoArmDecision> decision = evaluator.evaluate(snap, CFG, Optional.empty(), null, NY_KILL_ZONE);
        assertThat(decision).isPresent();
        assertThat(decision.get().direction()).isEqualTo(AutoArmDirection.SHORT);
        // SHORT levels: entry=price, sl=price+25, tp1=price-40, tp2=price-80.
        assertThat(decision.get().entryPrice().doubleValue()).isEqualTo(20000.0);
        assertThat(decision.get().stopLoss().doubleValue()).isEqualTo(20025.0);
        assertThat(decision.get().takeProfit1().doubleValue()).isEqualTo(19960.0);
        assertThat(decision.get().takeProfit2().doubleValue()).isEqualTo(19920.0);
    }

    @Test
    void score_below_min_is_rejected() {
        // score=6 with default min=7 → not eligible. Snapshot is "short
        // available" via the early-setup threshold (>=6) but the min-score
        // gate trumps it.
        QuantSnapshot snap = shortReadySnapshot(6);
        Optional<AutoArmDecision> decision = evaluator.evaluate(snap, CFG, Optional.empty(), null, NY_KILL_ZONE);
        assertThat(decision).isEmpty();
    }

    @Test
    void score_below_min_is_rejected_even_when_short_available() {
        AutoArmConfig stricter = new AutoArmConfig(7, 120, 30, 60, 0.005);
        QuantSnapshot snap = shortReadySnapshot(6);
        assertThat(snap.shortAvailable()).isTrue(); // sanity: snapshot allows SHORT
        assertThat(evaluator.evaluate(snap, stricter, Optional.empty(), null, NY_KILL_ZONE)).isEmpty();
    }

    @Test
    void blocked_short_is_rejected() {
        // Snapshot at score 7 BUT structurally blocked → shortAvailable=false → reject.
        QuantSnapshot snap = blockedSnapshot(7);
        assertThat(snap.shortAvailable()).isFalse();
        assertThat(evaluator.evaluate(snap, CFG, Optional.empty(), null, NY_KILL_ZONE)).isEmpty();
    }

    @Test
    void active_execution_on_instrument_blocks_arm() {
        QuantSnapshot snap = shortReadySnapshot(7);
        TradeExecutionRecord active = new TradeExecutionRecord();
        active.setStatus(ExecutionStatus.ACTIVE);
        active.setInstrument("MNQ");
        Optional<AutoArmDecision> decision = evaluator.evaluate(snap, CFG, Optional.of(active), null, NY_KILL_ZONE);
        assertThat(decision).isEmpty();
    }

    @Test
    void terminal_execution_on_instrument_does_not_block_arm() {
        // CLOSED is terminal — a previous trade should not block a new arm.
        QuantSnapshot snap = shortReadySnapshot(7);
        TradeExecutionRecord closed = new TradeExecutionRecord();
        closed.setStatus(ExecutionStatus.CLOSED);
        closed.setInstrument("MNQ");
        assertThat(evaluator.evaluate(snap, CFG, Optional.of(closed), null, NY_KILL_ZONE)).isPresent();
    }

    @Test
    void cooldown_blocks_arm_when_within_window() {
        QuantSnapshot snap = shortReadySnapshot(7);
        Instant lastArm = NY_KILL_ZONE.minusSeconds(30); // 30 s ago, < 60 s cooldown
        assertThat(evaluator.evaluate(snap, CFG, Optional.empty(), lastArm, NY_KILL_ZONE)).isEmpty();
    }

    @Test
    void cooldown_elapsed_allows_arm() {
        QuantSnapshot snap = shortReadySnapshot(7);
        Instant lastArm = NY_KILL_ZONE.minusSeconds(120); // > 60 s cooldown
        assertThat(evaluator.evaluate(snap, CFG, Optional.empty(), lastArm, NY_KILL_ZONE)).isPresent();
    }

    @Test
    void outside_kill_zone_blocks_arm() {
        QuantSnapshot snap = shortReadySnapshot(7);
        assertThat(evaluator.evaluate(snap, CFG, Optional.empty(), null, OUT_OF_KILL_ZONE)).isEmpty();
    }

    @Test
    void warnings_reduce_size_percent() {
        QuantSnapshot snap0 = shortReadySnapshot(7, 0);
        QuantSnapshot snap2 = shortReadySnapshot(7, 2);
        QuantSnapshot snap3 = shortReadySnapshot(7, 3);

        AutoArmDecision d0 = evaluator.evaluate(snap0, CFG, Optional.empty(), null, NY_KILL_ZONE).orElseThrow();
        AutoArmDecision d2 = evaluator.evaluate(snap2, CFG, Optional.empty(), null, NY_KILL_ZONE).orElseThrow();
        AutoArmDecision d3 = evaluator.evaluate(snap3, CFG, Optional.empty(), null, NY_KILL_ZONE).orElseThrow();

        assertThat(d0.sizePercent()).isEqualTo(CFG.sizePercentDefault());
        assertThat(d2.sizePercent()).isEqualTo(CFG.sizePercentDefault() * 0.50);
        assertThat(d3.sizePercent()).isEqualTo(CFG.sizePercentDefault() * 0.25);
    }

    @Test
    void null_price_is_rejected() {
        // Snapshot with no price cannot produce a tradable plan.
        QuantSnapshot snap = new QuantSnapshot(
            Instrument.MNQ, allGatesPass(7), 7, /*price*/null, "test", 0.0,
            ZonedDateTime.now(), List.of(), List.of(), 0, false);
        assertThat(evaluator.evaluate(snap, CFG, Optional.empty(), null, NY_KILL_ZONE)).isEmpty();
    }

    @Test
    void decision_includes_reasoning_with_warnings_and_score() {
        QuantSnapshot snap = shortReadySnapshot(7, 1);
        AutoArmDecision decision = evaluator.evaluate(snap, CFG, Optional.empty(), null, NY_KILL_ZONE).orElseThrow();
        assertThat(decision.reasoning()).contains("SHORT").contains("7/7").contains("warning");
    }

    @Test
    void expires_at_is_now_plus_expire_seconds() {
        QuantSnapshot snap = shortReadySnapshot(7);
        AutoArmDecision decision = evaluator.evaluate(snap, CFG, Optional.empty(), null, NY_KILL_ZONE).orElseThrow();
        assertThat(decision.expiresAt()).isEqualTo(NY_KILL_ZONE.plusSeconds(CFG.expireSeconds()));
        assertThat(decision.decisionAt()).isEqualTo(NY_KILL_ZONE);
    }

    @Test
    void long_only_snapshot_arms_long() {
        // SHORT score = 4 (below early threshold) → SHORT not available.
        // LONG score = 7 → LONG available (post-PR #301 longAvailable()).
        QuantSnapshot snap = new QuantSnapshot(
            Instrument.MNQ, allGatesPass(4), 4, /*longScore*/7,
            20000.0, "test", 0.0, ZonedDateTime.now(),
            List.of(), List.of(), 0, false,
            List.of(), List.of(), 0, false
        );
        Optional<AutoArmDecision> decision = evaluator.evaluate(snap, CFG, Optional.empty(), null, NY_KILL_ZONE);
        assertThat(decision).isPresent();
        assertThat(decision.get().direction()).isEqualTo(AutoArmDirection.LONG);
        // LONG levels: entry=price, sl=price-25, tp1=price+40, tp2=price+80.
        assertThat(decision.get().entryPrice().doubleValue()).isEqualTo(20000.0);
        assertThat(decision.get().stopLoss().doubleValue()).isEqualTo(19975.0);
        assertThat(decision.get().takeProfit1().doubleValue()).isEqualTo(20040.0);
    }

    @Test
    void both_directions_available_picks_higher_score() {
        // SHORT 6/7, LONG 7/7 → LONG wins.
        QuantSnapshot snap = new QuantSnapshot(
            Instrument.MNQ, allGatesPass(7), 6, /*longScore*/7,
            20000.0, "test", 0.0, ZonedDateTime.now(),
            List.of(), List.of(), 0, false,
            List.of(), List.of(), 0, false
        );
        Optional<AutoArmDecision> decision = evaluator.evaluate(snap, CFG, Optional.empty(), null, NY_KILL_ZONE);
        assertThat(decision).isPresent();
        assertThat(decision.get().direction()).isEqualTo(AutoArmDirection.LONG);
    }

    @Test
    void both_directions_tied_picks_short() {
        // SHORT 7/7, LONG 7/7 → tie-break: SHORT (legacy default).
        QuantSnapshot snap = new QuantSnapshot(
            Instrument.MNQ, allGatesPass(7), 7, /*longScore*/7,
            20000.0, "test", 0.0, ZonedDateTime.now(),
            List.of(), List.of(), 0, false,
            List.of(), List.of(), 0, false
        );
        Optional<AutoArmDecision> decision = evaluator.evaluate(snap, CFG, Optional.empty(), null, NY_KILL_ZONE);
        assertThat(decision).isPresent();
        assertThat(decision.get().direction()).isEqualTo(AutoArmDirection.SHORT);
    }

    // ───────────────────────── helpers ─────────────────────────

    private static QuantSnapshot shortReadySnapshot(int score) {
        return shortReadySnapshot(score, 0);
    }

    private static QuantSnapshot shortReadySnapshot(int score, int warningCount) {
        // shortAvailable() requires !shortBlocked && score>=6 — pass score
        // straight through. Use price=20000 so SHORT levels are easy to assert.
        return new QuantSnapshot(
            Instrument.MNQ,
            allGatesPass(score),
            score,
            20000.0,
            "test",
            0.0,
            ZonedDateTime.now(),
            List.of(),
            buildWarnings(warningCount),
            0,
            /*shortBlocked=*/false
        );
    }

    private static QuantSnapshot blockedSnapshot(int score) {
        return new QuantSnapshot(
            Instrument.MNQ,
            allGatesPass(score),
            score,
            20000.0,
            "test",
            0.0,
            ZonedDateTime.now(),
            List.of(), // BLOCKS list is informational here; the boolean flag is what gates
            List.of(),
            0,
            /*shortBlocked=*/true
        );
    }

    private static Map<Gate, GateResult> allGatesPass(int passCount) {
        Map<Gate, GateResult> map = new EnumMap<>(Gate.class);
        Gate[] gates = Gate.values();
        for (int i = 0; i < gates.length; i++) {
            boolean ok = i < passCount;
            map.put(gates[i], new GateResult(ok, ok ? "ok" : "fail"));
        }
        return map;
    }

    private static List<StructuralWarning> buildWarnings(int count) {
        if (count <= 0) return List.of();
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> new StructuralWarning("WARN_" + i, "evidence " + i, -1))
            .toList();
    }
}

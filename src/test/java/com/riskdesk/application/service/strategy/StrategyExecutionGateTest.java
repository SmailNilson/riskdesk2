package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.DecisionType;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.infrastructure.config.StrategyExecutionGateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyExecutionGateTest {

    private static final Instant AT = Instant.parse("2026-04-17T10:00:00Z");

    // ── Pass paths ──────────────────────────────────────────────────────────

    @Test
    void passes_when_gate_disabled_globally() {
        StrategyExecutionGateProperties props = new StrategyExecutionGateProperties();
        props.setEnabled(false);
        props.setInstruments(Set.of("MGC"));
        StrategyExecutionGate gate = new StrategyExecutionGate(props, emptyProvider());

        GateOutcome out = gate.check(review("MGC", "1h", "LONG"));

        assertThat(out.allow()).isTrue();
        assertThat(out.reason()).contains("gate-disabled-globally");
    }

    @Test
    void passes_when_instrument_not_enrolled() {
        StrategyExecutionGateProperties props = new StrategyExecutionGateProperties();
        props.setEnabled(true);
        props.setInstruments(Set.of("MGC"));  // MCL not in list
        StrategyExecutionGate gate = new StrategyExecutionGate(props, emptyProvider());

        GateOutcome out = gate.check(review("MCL", "1h", "LONG"));

        assertThat(out.allow()).isTrue();
        assertThat(out.reason()).contains("instrument-not-enrolled");
    }

    @Test
    void passes_when_engine_agrees_on_direction_and_decision() {
        StrategyExecutionGateProperties props = enrolledFor("MGC");
        StubEngine engine = engineReturning(decision(DecisionType.HALF_SIZE, Direction.LONG, "NOR"));
        StrategyExecutionGate gate = new StrategyExecutionGate(props, provider(engine));

        GateOutcome out = gate.check(review("MGC", "1h", "LONG"));

        assertThat(out.allow()).isTrue();
        assertThat(out.reason())
            .contains("engine-agrees")
            .contains("NOR")
            .contains("HALF_SIZE");
    }

    // ── Block paths ─────────────────────────────────────────────────────────

    @Test
    void blocks_when_engine_bean_unavailable() {
        StrategyExecutionGateProperties props = enrolledFor("MGC");
        StrategyExecutionGate gate = new StrategyExecutionGate(props, emptyProvider());

        GateOutcome out = gate.check(review("MGC", "1h", "LONG"));

        assertThat(out.allow()).isFalse();
        assertThat(out.reason()).contains("strategy-engine-unavailable");
    }

    @Test
    void blocks_when_engine_throws() {
        StrategyExecutionGateProperties props = enrolledFor("MGC");
        StubEngine engine = new StubEngine(null, new RuntimeException("boom"));
        StrategyExecutionGate gate = new StrategyExecutionGate(props, provider(engine));

        GateOutcome out = gate.check(review("MGC", "1h", "LONG"));

        assertThat(out.allow()).isFalse();
        assertThat(out.reason()).contains("engine-error");
    }

    @Test
    void blocks_when_engine_decision_is_no_trade() {
        StrategyExecutionGateProperties props = enrolledFor("MGC");
        StubEngine engine = engineReturning(decision(DecisionType.NO_TRADE, null, null));
        StrategyExecutionGate gate = new StrategyExecutionGate(props, provider(engine));

        GateOutcome out = gate.check(review("MGC", "1h", "LONG"));

        assertThat(out.allow()).isFalse();
        assertThat(out.reason()).contains("engine-decision=NO_TRADE");
    }

    @Test
    void blocks_when_engine_decision_is_paper_trade() {
        StrategyExecutionGateProperties props = enrolledFor("MGC");
        // PAPER_TRADE = setup seen, too weak for live → gate must still block
        StubEngine engine = engineReturning(decision(DecisionType.PAPER_TRADE, Direction.LONG, "LSAR"));
        StrategyExecutionGate gate = new StrategyExecutionGate(props, provider(engine));

        GateOutcome out = gate.check(review("MGC", "1h", "LONG"));

        assertThat(out.allow()).isFalse();
        assertThat(out.reason()).contains("engine-decision=PAPER_TRADE");
    }

    @Test
    void blocks_when_engine_direction_disagrees_with_review_action() {
        // Review says LONG, engine says SHORT — the worst-case divergence.
        StrategyExecutionGateProperties props = enrolledFor("MGC");
        StubEngine engine = engineReturning(decision(DecisionType.HALF_SIZE, Direction.SHORT, "LS"));
        StrategyExecutionGate gate = new StrategyExecutionGate(props, provider(engine));

        GateOutcome out = gate.check(review("MGC", "1h", "LONG"));

        assertThat(out.allow()).isFalse();
        assertThat(out.reason())
            .contains("direction-mismatch")
            .contains("engine=SHORT")
            .contains("review=LONG");
    }

    @Test
    void blocks_when_engine_direction_is_empty_but_decision_is_tradeable() {
        // Defensive: an engine that reports HALF_SIZE without a direction is
        // internally inconsistent. The gate blocks rather than guessing.
        StrategyExecutionGateProperties props = enrolledFor("MGC");
        StubEngine engine = engineReturning(decision(DecisionType.HALF_SIZE, null, "LSAR"));
        StrategyExecutionGate gate = new StrategyExecutionGate(props, provider(engine));

        GateOutcome out = gate.check(review("MGC", "1h", "LONG"));

        assertThat(out.allow()).isFalse();
        assertThat(out.reason()).contains("direction-mismatch");
    }

    @Test
    void blocks_on_unknown_instrument_code() {
        // Enrolled but the instrument string isn't in the Instrument enum —
        // defensive path; in practice the review layer already validates this
        // but the gate must not crash.
        StrategyExecutionGateProperties props = new StrategyExecutionGateProperties();
        props.setEnabled(true);
        props.setInstruments(Set.of("BAD"));
        StubEngine engine = engineReturning(decision(DecisionType.HALF_SIZE, Direction.LONG, "LSAR"));
        StrategyExecutionGate gate = new StrategyExecutionGate(props, provider(engine));

        GateOutcome out = gate.check(review("BAD", "1h", "LONG"));

        assertThat(out.allow()).isFalse();
        assertThat(out.reason()).contains("unknown-instrument");
    }

    // ── Property defaults ───────────────────────────────────────────────────

    @Test
    void default_properties_disable_the_gate() {
        StrategyExecutionGateProperties defaults = new StrategyExecutionGateProperties();
        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.getInstruments()).isEmpty();
        assertThat(defaults.enrolls("MGC")).isFalse();
    }

    @Test
    void instrument_names_are_normalised_to_uppercase() {
        StrategyExecutionGateProperties props = new StrategyExecutionGateProperties();
        props.setEnabled(true);
        props.setInstruments(Set.of("mgc", " MCL ", ""));
        assertThat(props.enrolls("MGC")).isTrue();
        assertThat(props.enrolls("mgc")).isTrue();
        assertThat(props.enrolls("MCL")).isTrue();
        assertThat(props.enrolls("MNQ")).isFalse();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static MentorSignalReviewRecord review(String instrument, String timeframe, String action) {
        MentorSignalReviewRecord r = new MentorSignalReviewRecord();
        r.setId(123L);
        r.setInstrument(instrument);
        r.setTimeframe(timeframe);
        r.setAction(action);
        return r;
    }

    private static StrategyExecutionGateProperties enrolledFor(String instrument) {
        StrategyExecutionGateProperties p = new StrategyExecutionGateProperties();
        p.setEnabled(true);
        p.setInstruments(Set.of(instrument));
        return p;
    }

    private static StrategyDecision decision(DecisionType type, Direction direction, String playbookId) {
        Optional<Direction> dir = Optional.ofNullable(direction);
        Optional<MechanicalPlan> plan = dir.map(d -> new MechanicalPlan(d,
            new BigDecimal("100"), new BigDecimal("99"),
            new BigDecimal("102"), new BigDecimal("103"), 2.0));
        List<AgentVote> votes = List.of(
            AgentVote.of("ctx", StrategyLayer.CONTEXT, 60, 0.8, List.of("ctx")),
            AgentVote.of("zone", StrategyLayer.ZONE, 40, 0.7, List.of("zone"))
        );
        return new StrategyDecision(
            Optional.ofNullable(playbookId),
            votes,
            Map.of(StrategyLayer.CONTEXT, 60.0, StrategyLayer.ZONE, 40.0, StrategyLayer.TRIGGER, 0.0),
            72.5, type, dir, plan,
            List.of(), AT);
    }

    private static StubEngine engineReturning(StrategyDecision decision) {
        return new StubEngine(decision, null);
    }

    private static ObjectProvider<StrategyEngineService> emptyProvider() {
        return new StubObjectProvider(null);
    }

    private static ObjectProvider<StrategyEngineService> provider(StubEngine engine) {
        return new StubObjectProvider(engine);
    }

    // ── test doubles ───────────────────────────────────────────────────────

    private static final class StubEngine extends StrategyEngineService {
        private final StrategyDecision decision;
        private final RuntimeException toThrow;

        StubEngine(StrategyDecision decision, RuntimeException toThrow) {
            // Pass nulls — the stub overrides evaluate() and never calls super.
            super(null, null, null, null, null, null);
            this.decision = decision;
            this.toThrow = toThrow;
        }

        @Override
        public StrategyDecision evaluate(Instrument instrument, String timeframe) {
            if (toThrow != null) throw toThrow;
            return decision;
        }
    }

    private static final class StubObjectProvider implements ObjectProvider<StrategyEngineService> {
        private final StrategyEngineService instance;

        StubObjectProvider(StrategyEngineService instance) {
            this.instance = instance;
        }

        @Override public StrategyEngineService getIfAvailable() { return instance; }
        @Override public StrategyEngineService getObject() { return instance; }
        @Override public StrategyEngineService getObject(Object... args) { return instance; }
        @Override public StrategyEngineService getIfUnique() { return instance; }
    }
}

package com.riskdesk.presentation.dto;

import com.riskdesk.domain.engine.playbook.model.ChecklistItem;
import com.riskdesk.domain.engine.playbook.model.ChecklistStatus;
import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.FilterResult;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SetupType;
import com.riskdesk.domain.engine.playbook.model.VaPosition;
import com.riskdesk.domain.engine.strategy.model.DecisionType;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionComparisonViewTest {

    private static final Instant AT = Instant.parse("2026-04-17T10:00:00Z");

    @Test
    void both_no_trade_when_legacy_below_4_and_new_is_no_trade() {
        PlaybookEvaluation legacy = legacyEvaluation(2, Direction.LONG);
        StrategyDecision decision = decision(DecisionType.NO_TRADE, null);

        DecisionComparisonView view = DecisionComparisonView.build("MGC", "1h", legacy, decision);
        assertThat(view.agreement()).isEqualTo(DecisionComparisonView.Agreement.BOTH_NO_TRADE);
    }

    @Test
    void both_same_direction_when_both_tradeable_and_long() {
        PlaybookEvaluation legacy = legacyEvaluation(6, Direction.LONG);
        StrategyDecision decision = decision(DecisionType.HALF_SIZE, Direction.LONG);

        DecisionComparisonView view = DecisionComparisonView.build("MGC", "1h", legacy, decision);
        assertThat(view.agreement()).isEqualTo(
            DecisionComparisonView.Agreement.BOTH_TRADEABLE_SAME_DIRECTION);
    }

    @Test
    void opposite_directions_flagged() {
        PlaybookEvaluation legacy = legacyEvaluation(6, Direction.LONG);
        StrategyDecision decision = decision(DecisionType.HALF_SIZE, Direction.SHORT);

        DecisionComparisonView view = DecisionComparisonView.build("MGC", "1h", legacy, decision);
        assertThat(view.agreement()).isEqualTo(
            DecisionComparisonView.Agreement.BOTH_TRADEABLE_OPPOSITE_DIRECTION);
    }

    @Test
    void legacy_only_when_legacy_tradeable_and_new_no_trade() {
        PlaybookEvaluation legacy = legacyEvaluation(7, Direction.LONG);
        StrategyDecision decision = decision(DecisionType.NO_TRADE, null);

        DecisionComparisonView view = DecisionComparisonView.build("MGC", "1h", legacy, decision);
        assertThat(view.agreement()).isEqualTo(DecisionComparisonView.Agreement.LEGACY_ONLY_TRADEABLE);
    }

    @Test
    void new_only_when_new_tradeable_and_legacy_rejected() {
        PlaybookEvaluation legacy = legacyEvaluation(3, Direction.LONG);  // below legacy 4/7 threshold
        StrategyDecision decision = decision(DecisionType.HALF_SIZE, Direction.LONG);

        DecisionComparisonView view = DecisionComparisonView.build("MGC", "1h", legacy, decision);
        assertThat(view.agreement()).isEqualTo(DecisionComparisonView.Agreement.NEW_ONLY_TRADEABLE);
    }

    @Test
    void paper_trade_counts_as_tradeable_for_agreement() {
        // PAPER_TRADE from the new engine = "setup seen, too weak to live trade" —
        // for comparison purposes it's still a signal, not a reject.
        PlaybookEvaluation legacy = legacyEvaluation(6, Direction.LONG);
        StrategyDecision decision = decision(DecisionType.PAPER_TRADE, Direction.LONG);

        DecisionComparisonView view = DecisionComparisonView.build("MGC", "1h", legacy, decision);
        assertThat(view.agreement()).isEqualTo(
            DecisionComparisonView.Agreement.BOTH_TRADEABLE_SAME_DIRECTION);
    }

    @Test
    void inconclusive_when_either_side_is_null() {
        assertThat(DecisionComparisonView.build("MGC", "1h", null, null).agreement())
            .isEqualTo(DecisionComparisonView.Agreement.INCONCLUSIVE);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static PlaybookEvaluation legacyEvaluation(int score, Direction dir) {
        FilterResult filters = new FilterResult(
            true, dir == Direction.LONG ? "BULLISH" : "BEARISH", dir,
            true, 3, 0, 3, 1.0,
            true, VaPosition.INSIDE_VA, true);
        SetupCandidate setup = new SetupCandidate(
            SetupType.ZONE_RETEST, "Test OB",
            new BigDecimal("101"), new BigDecimal("100"), new BigDecimal("100.5"),
            0.0, true, true, true, 2.0, score);
        PlaybookPlan plan = new PlaybookPlan(
            new BigDecimal("100.5"), new BigDecimal("99"),
            new BigDecimal("102.5"), new BigDecimal("104"),
            2.0, 1.0, "test-sl", "test-tp");
        ChecklistItem item = new ChecklistItem(1, "Bias aligned", ChecklistStatus.PASS, dir.name());
        return new PlaybookEvaluation(
            filters, List.of(setup), setup, plan, List.of(item), score,
            "TEST", AT);
    }

    private static StrategyDecision decision(DecisionType type, Direction direction) {
        Optional<Direction> dir = Optional.ofNullable(direction);
        Optional<MechanicalPlan> plan = dir.map(d -> new MechanicalPlan(
            d, new BigDecimal("100"), new BigDecimal("99"),
            new BigDecimal("102"), new BigDecimal("103"), 2.0));
        return new StrategyDecision(
            Optional.of("LSAR"), List.of(),
            Map.of(StrategyLayer.CONTEXT, 50.0, StrategyLayer.ZONE, 40.0, StrategyLayer.TRIGGER, 30.0),
            60.0, type, dir, plan, List.of(), AT
        );
    }
}

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
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookPlanViewTest {

    @Test
    void playbookPlanView_carriesContractSpecFromInstrument() {
        PlaybookPlan plan = new PlaybookPlan(
            new BigDecimal("29471.75"),
            new BigDecimal("29458.00"),
            new BigDecimal("29485.50"),
            new BigDecimal("29499.25"),
            1.0, 0.005, "BOS retest", "1R"
        );

        PlaybookPlanView view = PlaybookPlanView.from(plan, Instrument.MNQ);

        assertThat(view.entryPrice()).isEqualByComparingTo("29471.75");
        assertThat(view.stopLoss()).isEqualByComparingTo("29458.00");
        assertThat(view.takeProfit1()).isEqualByComparingTo("29485.50");
        assertThat(view.takeProfit2()).isEqualByComparingTo("29499.25");
        assertThat(view.contractMultiplier()).isEqualByComparingTo("2");
        assertThat(view.tickSize()).isEqualByComparingTo("0.25");
        assertThat(view.tickValue()).isEqualByComparingTo("0.50");
    }

    @Test
    void playbookPlanView_supportsAllInstruments() {
        PlaybookPlan plan = new PlaybookPlan(
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            1.0, 0.005, "", ""
        );

        assertThat(PlaybookPlanView.from(plan, Instrument.MCL).contractMultiplier())
            .isEqualByComparingTo("100");
        assertThat(PlaybookPlanView.from(plan, Instrument.MGC).contractMultiplier())
            .isEqualByComparingTo("10");
        assertThat(PlaybookPlanView.from(plan, Instrument.E6).contractMultiplier())
            .isEqualByComparingTo("125000");
    }

    @Test
    void playbookPlanView_returnsNullForNullPlan() {
        assertThat(PlaybookPlanView.from(null, Instrument.MNQ)).isNull();
    }

    @Test
    void evaluationView_preservesAllFieldsAndSwapsPlan() {
        FilterResult filters = new FilterResult(
            true, "BULLISH", Direction.LONG,
            true, 9, 0, 9, 1.0,
            false, VaPosition.ABOVE_VA, false
        );
        SetupCandidate setup = new SetupCandidate(
            SetupType.BREAK_RETEST, "BOS-zone",
            new BigDecimal("29470"), new BigDecimal("29473"), new BigDecimal("29471.5"),
            0.5, true, true, true, 1.0, 5
        );
        PlaybookPlan plan = new PlaybookPlan(
            new BigDecimal("29471.75"), new BigDecimal("29458.00"),
            new BigDecimal("29485.50"), new BigDecimal("29499.25"),
            1.0, 0.005, "BOS retest", "1R"
        );
        ChecklistItem item = new ChecklistItem(1, "Bias", ChecklistStatus.PASS, "BULLISH");
        PlaybookEvaluation eval = new PlaybookEvaluation(
            filters, List.of(setup), setup, plan, List.of(item),
            5, "WAIT for confirmation", Instant.parse("2026-05-22T15:00:00Z"), false
        );

        PlaybookEvaluationView view = PlaybookEvaluationView.from(eval, Instrument.MNQ);

        assertThat(view.filters()).isSameAs(filters);
        assertThat(view.setups()).containsExactly(setup);
        assertThat(view.bestSetup()).isSameAs(setup);
        assertThat(view.checklist()).containsExactly(item);
        assertThat(view.checklistScore()).isEqualTo(5);
        assertThat(view.verdict()).isEqualTo("WAIT for confirmation");
        assertThat(view.lateEntry()).isFalse();
        assertThat(view.plan()).isNotNull();
        assertThat(view.plan().contractMultiplier()).isEqualByComparingTo("2");
    }
}

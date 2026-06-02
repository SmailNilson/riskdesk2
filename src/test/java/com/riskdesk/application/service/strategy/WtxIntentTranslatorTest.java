package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxAction;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.execution.IntentKind;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WtxIntentTranslatorTest {

    private static TradeIntent intent(WtxAction action) {
        return WtxIntentTranslator.toTradeIntent(action, "wtx:MNQ:5m:1:" + action.name(),
            ExecutionTriggerSource.WTX_AUTO, Instrument.MNQ, "5m", 2, new BigDecimal("18000.25"), "DU1");
    }

    // ---- action → TradeIntent (kind + side) ----------------------------------------------------

    @Test void openLong_mapsToOpenLong() {
        TradeIntent i = intent(WtxAction.OPEN_LONG);
        assertThat(i.kind()).isEqualTo(IntentKind.OPEN);
        assertThat(i.side()).isEqualTo(Side.LONG);
    }

    @Test void openShort_mapsToOpenShort() {
        TradeIntent i = intent(WtxAction.OPEN_SHORT);
        assertThat(i.kind()).isEqualTo(IntentKind.OPEN);
        assertThat(i.side()).isEqualTo(Side.SHORT);
    }

    @Test void reverseToLong_mapsToReverseLong() {
        TradeIntent i = intent(WtxAction.REVERSE_TO_LONG);
        assertThat(i.kind()).isEqualTo(IntentKind.REVERSE);
        assertThat(i.side()).isEqualTo(Side.LONG);
    }

    @Test void reverseToShort_mapsToReverseShort() {
        TradeIntent i = intent(WtxAction.REVERSE_TO_SHORT);
        assertThat(i.kind()).isEqualTo(IntentKind.REVERSE);
        assertThat(i.side()).isEqualTo(Side.SHORT);
    }

    @Test void closeLong_mapsToCloseLong() {
        TradeIntent i = intent(WtxAction.CLOSE_LONG);
        assertThat(i.kind()).isEqualTo(IntentKind.CLOSE);
        assertThat(i.side()).isEqualTo(Side.LONG);
    }

    @Test void closeShort_mapsToCloseShort() {
        TradeIntent i = intent(WtxAction.CLOSE_SHORT);
        assertThat(i.kind()).isEqualTo(IntentKind.CLOSE);
        assertThat(i.side()).isEqualTo(Side.SHORT);
    }

    @Test void closeAll_mapsToFlatten_noSide() {
        TradeIntent i = intent(WtxAction.CLOSE_ALL);
        assertThat(i.kind()).isEqualTo(IntentKind.FLATTEN);
        assertThat(i.side()).isNull(); // FLATTEN derives the held side at routing time
    }

    @Test void toTradeIntent_carriesContextVerbatim() {
        TradeIntent i = intent(WtxAction.OPEN_LONG);
        assertThat(i.idempotencyKey()).isEqualTo("wtx:MNQ:5m:1:OPEN_LONG");
        assertThat(i.source()).isEqualTo(ExecutionTriggerSource.WTX_AUTO);
        assertThat(i.instrument()).isEqualTo(Instrument.MNQ);
        assertThat(i.timeframe()).isEqualTo("5m");
        assertThat(i.quantity()).isEqualTo(2);
        assertThat(i.limitPrice()).isEqualByComparingTo("18000.25");
        assertThat(i.brokerAccountId()).isEqualTo("DU1");
    }

    @Test void toTradeIntent_coversEveryRoutableWtxAction() {
        for (WtxAction a : WtxAction.values()) {
            if (a == WtxAction.NONE) continue; // not routable — asserted separately
            assertThat(intent(a)).as("intent for %s", a).isNotNull();
        }
    }

    @Test void none_isNotRoutable_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> intent(WtxAction.NONE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- RoutingOutcome → WtxRoutingOutcome ----------------------------------------------------

    @Test void toWtxOutcome_coversEveryRoutingOutcome_noNullNoThrow() {
        for (RoutingOutcome o : RoutingOutcome.values()) {
            assertThat(WtxIntentTranslator.toWtxOutcome(o)).as("wtx outcome for %s", o).isNotNull();
        }
    }

    @Test void toWtxOutcome_oneToOneMappings() {
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.ROUTED)).isEqualTo(WtxRoutingOutcome.ROUTED);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.ROUTED_FLATTEN_ONLY)).isEqualTo(WtxRoutingOutcome.ROUTED_FLATTEN_ONLY);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.ACK_PENDING)).isEqualTo(WtxRoutingOutcome.ACK_PENDING);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.SKIPPED_DUPLICATE)).isEqualTo(WtxRoutingOutcome.SKIPPED_DUPLICATE);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT)).isEqualTo(WtxRoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.SKIPPED_NO_OPEN_ROW)).isEqualTo(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.FAILED_BROKER_REJECT)).isEqualTo(WtxRoutingOutcome.FAILED_BROKER_REJECT);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.FAILED_TIMEOUT)).isEqualTo(WtxRoutingOutcome.FAILED_TIMEOUT);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.FAILED)).isEqualTo(WtxRoutingOutcome.FAILED);
    }

    @Test void toWtxOutcome_routerOnlyOutcomesCollapseToClosestWtxValue() {
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.SKIPPED_RECONCILING)).isEqualTo(WtxRoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.SKIPPED_NO_ACCOUNT)).isEqualTo(WtxRoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.SKIPPED_STALE_PRICE_SOURCE)).isEqualTo(WtxRoutingOutcome.SKIPPED_NO_PRICE);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.FAILED_INSUFFICIENT_MARGIN)).isEqualTo(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN)).isEqualTo(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.FAILED_READ_ONLY)).isEqualTo(WtxRoutingOutcome.FAILED_BROKER_REJECT);
        assertThat(WtxIntentTranslator.toWtxOutcome(RoutingOutcome.PAPER_ONLY)).isEqualTo(WtxRoutingOutcome.ROUTED);
    }
}

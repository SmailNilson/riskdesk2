package com.riskdesk.application.execution;

import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionReconcilerTest {

    private final ExecutionReconciler pure = new ExecutionReconciler(null);

    private static BrokerPositionState pos(String net, boolean confirmedFlat) {
        return new BrokerPositionState(net == null ? null : new BigDecimal(net), confirmedFlat);
    }

    private static TradeIntent open(Side side) {
        return TradeIntent.open("k", ExecutionTriggerSource.WTX_AUTO, Instrument.MNQ, "5m", side, 1, new BigDecimal("100"), "DU1");
    }
    private static TradeIntent reverse(Side side) {
        return TradeIntent.reverse("k", ExecutionTriggerSource.WTX_AUTO, Instrument.MNQ, "5m", side, 1, new BigDecimal("100"), "DU1");
    }
    private static TradeIntent close(Side side) {
        return TradeIntent.close("k", ExecutionTriggerSource.WTX_AUTO, Instrument.MNQ, "5m", side, 1, new BigDecimal("100"), "DU1");
    }
    private static TradeIntent flatten() {
        return TradeIntent.flatten("k", ExecutionTriggerSource.WTX_AUTO, Instrument.MNQ, "5m", 1, new BigDecimal("100"), "DU1");
    }

    // ---- reconcile(): entry decision table (OPEN / REVERSE) -------------------------------------

    @Test void open_flat_opens() {
        assertThat(pure.reconcile(open(Side.LONG), pos("0", true))).isEqualTo(new ReconcilePlan.Open(Side.LONG));
    }

    @Test void open_unavailable_passesThrough() {
        assertThat(pure.reconcile(open(Side.SHORT), pos(null, false))).isEqualTo(new ReconcilePlan.Open(Side.SHORT));
    }

    @Test void reverse_confirmedFlat_downgradesToOpen() {
        assertThat(pure.reconcile(reverse(Side.LONG), pos("0", true))).isEqualTo(new ReconcilePlan.Open(Side.LONG));
    }

    @Test void reverse_netZeroButOffsettingLegs_keepsReverse() {
        // net 0 but NOT confirmedFlat (offsetting expiries are LIVE) → keep the two-leg reverse
        assertThat(pure.reconcile(reverse(Side.LONG), pos("0", false))).isEqualTo(new ReconcilePlan.Reverse(Side.LONG));
    }

    @Test void open_netZeroButOffsettingLegs_skipsToAvoidStacking() {
        // net 0 but NOT confirmedFlat (offsetting live legs) → a plain OPEN must NOT stack a fresh entry
        ReconcilePlan p = pure.reconcile(open(Side.LONG), pos("0", false));
        assertThat(p).isInstanceOf(ReconcilePlan.Skip.class);
        assertThat(((ReconcilePlan.Skip) p).outcome()).isEqualTo(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
    }

    @Test void open_sameSideHeld_skipsDuplicate() {
        ReconcilePlan p = pure.reconcile(open(Side.LONG), pos("2", false));
        assertThat(p).isInstanceOf(ReconcilePlan.Skip.class);
        assertThat(((ReconcilePlan.Skip) p).outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
    }

    @Test void open_oppositeHeld_upgradesToReverse() {
        assertThat(pure.reconcile(open(Side.LONG), pos("-2", false))).isEqualTo(new ReconcilePlan.Reverse(Side.LONG));
        assertThat(pure.reconcile(open(Side.SHORT), pos("2", false))).isEqualTo(new ReconcilePlan.Reverse(Side.SHORT));
    }

    @Test void reverse_oppositeHeld_keepsReverse() {
        assertThat(pure.reconcile(reverse(Side.SHORT), pos("3", false))).isEqualTo(new ReconcilePlan.Reverse(Side.SHORT));
    }

    @Test void reverse_sameSideHeld_skipsDuplicate() {
        ReconcilePlan p = pure.reconcile(reverse(Side.LONG), pos("2", false));
        assertThat(p).isInstanceOf(ReconcilePlan.Skip.class);
        assertThat(((ReconcilePlan.Skip) p).outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
    }

    // ---- reconcile(): close / flatten ----------------------------------------------------------

    @Test void close_withPosition_closes() {
        assertThat(pure.reconcile(close(Side.LONG), pos("2", false))).isEqualTo(new ReconcilePlan.Close(Side.LONG));
    }

    @Test void close_confirmedFlat_skips() {
        ReconcilePlan p = pure.reconcile(close(Side.LONG), pos("0", true));
        assertThat(p).isInstanceOf(ReconcilePlan.Skip.class);
        assertThat(((ReconcilePlan.Skip) p).outcome()).isEqualTo(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
    }

    @Test void flatten_longHeld_flattensLong() {
        assertThat(pure.reconcile(flatten(), pos("2", false))).isEqualTo(new ReconcilePlan.Flatten(Side.LONG));
    }

    @Test void flatten_shortHeld_flattensShort() {
        assertThat(pure.reconcile(flatten(), pos("-2", false))).isEqualTo(new ReconcilePlan.Flatten(Side.SHORT));
    }

    @Test void flatten_flat_skips() {
        assertThat(pure.reconcile(flatten(), pos("0", true))).isInstanceOf(ReconcilePlan.Skip.class);
    }

    @Test void flatten_unavailable_skips() {
        assertThat(pure.reconcile(flatten(), pos(null, false))).isInstanceOf(ReconcilePlan.Skip.class);
    }

    // ---- readPositionState(): confirmedFlat semantics ------------------------------------------

    private static IbkrPositionView position(String account, String desc, String qty) {
        return new IbkrPositionView(account, 0L, desc, "FUT", new BigDecimal(qty),
            null, null, null, null, null, null, "USD");
    }
    private static IbkrPortfolioSnapshot snap(boolean connected, IbkrPositionView... positions) {
        return new IbkrPortfolioSnapshot(connected, "DU1", List.of(), null, null, null, null, null, null, null,
            "USD", List.of(positions), null);
    }

    @Test void readPosition_nonzeroLeg_netAndNotFlat() {
        IbkrPortfolioService svc = mock(IbkrPortfolioService.class);
        when(svc.getPortfolio("DU1")).thenReturn(snap(true, position("DU1", "MNQH6", "2")));

        BrokerPositionState s = new ExecutionReconciler(svc).readPositionState("DU1", Instrument.MNQ);

        assertThat(s.available()).isTrue();
        assertThat(s.net()).isEqualByComparingTo("2");
        assertThat(s.confirmedFlat()).isFalse();
        assertThat(s.isLong()).isTrue();
    }

    @Test void readPosition_offsettingLegs_netZeroButLive() {
        IbkrPortfolioService svc = mock(IbkrPortfolioService.class);
        when(svc.getPortfolio("DU1")).thenReturn(snap(true,
            position("DU1", "MNQH6", "1"), position("DU1", "MNQM6", "-1")));  // rollover overlap

        BrokerPositionState s = new ExecutionReconciler(svc).readPositionState("DU1", Instrument.MNQ);

        assertThat(s.net()).isEqualByComparingTo("0");
        assertThat(s.confirmedFlat()).isFalse(); // offsetting legs are LIVE, not flat
    }

    @Test void readPosition_noMatchingLeg_confirmedFlat() {
        IbkrPortfolioService svc = mock(IbkrPortfolioService.class);
        when(svc.getPortfolio("DU1")).thenReturn(snap(true, position("DU1", "MGCM6", "3"))); // different instrument

        BrokerPositionState s = new ExecutionReconciler(svc).readPositionState("DU1", Instrument.MNQ);

        assertThat(s.net()).isEqualByComparingTo("0");
        assertThat(s.confirmedFlat()).isTrue();
    }

    @Test void readPosition_otherAccountFiltered_confirmedFlat() {
        IbkrPortfolioService svc = mock(IbkrPortfolioService.class);
        when(svc.getPortfolio("DU1")).thenReturn(snap(true, position("DU2", "MNQH6", "5"))); // wrong account

        BrokerPositionState s = new ExecutionReconciler(svc).readPositionState("DU1", Instrument.MNQ);

        assertThat(s.confirmedFlat()).isTrue(); // other account's leg filtered out
    }

    @Test void readPosition_notConnected_unavailable() {
        IbkrPortfolioService svc = mock(IbkrPortfolioService.class);
        when(svc.getPortfolio("DU1")).thenReturn(snap(false));

        assertThat(new ExecutionReconciler(svc).readPositionState("DU1", Instrument.MNQ).available()).isFalse();
    }

    @Test void readPosition_nullService_unavailable() {
        assertThat(new ExecutionReconciler(null).readPositionState("DU1", Instrument.MNQ).available()).isFalse();
    }

    @Test void symbolMatching_e6SpecialCase() {
        assertThat(ExecutionReconciler.ibkrSymbol(Instrument.E6)).isEqualTo("6E");
        assertThat(ExecutionReconciler.ibkrSymbol(Instrument.MNQ)).isEqualTo("MNQ");
        assertThat(ExecutionReconciler.matchesSymbol("6EM6", "6E")).isTrue();
        assertThat(ExecutionReconciler.matchesSymbol("MNQH6", "6E")).isFalse();
        assertThat(ExecutionReconciler.matchesSymbol(null, "MNQ")).isFalse();
    }
}

package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P2.2 (R3) — WTX-RSI virtual-side self-heal against execution-row truth.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WtxRsiPositionReconcilerTest {

    @Mock TradeExecutionRepositoryPort repo;

    private WtxRsiPositionReconciler reconciler() {
        return new WtxRsiPositionReconciler(repo);
    }

    private static WtxRsiStrategyState liveLong() {
        return WtxRsiStrategyState.initial("MNQ", "5m").withAutoExecution(true).withPosition(
            WtxRsiPosition.LONG, new BigDecimal("30000.00"), BigDecimal.ONE,
            new BigDecimal("29950.00"), new BigDecimal("30100.00"));
    }

    private static TradeExecutionRecord row(ExecutionStatus status, String action) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setStatus(status);
        r.setAction(action);
        r.setQuantity(1);
        r.setInstrument("MNQ");
        r.setTimeframe("5m");
        r.setNormalizedEntryPrice(new BigDecimal("30000.00"));
        r.setVirtualStopLoss(new BigDecimal("29950.00"));
        r.setVirtualTakeProfit(new BigDecimal("30100.00"));
        return r;
    }

    @Test
    void clearsPhantomSideWhenNoActiveRow() {
        // Strategy still believes LONG, but IBKR/row is flat (P1 closed the phantom) → correct to FLAT.
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource("MNQ", "5m", ExecutionTriggerSource.WTXRSI_AUTO))
            .thenReturn(Optional.empty());

        WtxRsiStrategyState out = reconciler().reconcile(liveLong(), Instrument.MNQ, new BigDecimal("30010.00"));

        assertThat(out.currentPosition()).isEqualTo(WtxRsiPosition.FLAT);
    }

    @Test
    void leavesAlignedSideUnchanged() {
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any()))
            .thenReturn(Optional.of(row(ExecutionStatus.ACTIVE, "LONG")));

        WtxRsiStrategyState in = liveLong();
        WtxRsiStrategyState out = reconciler().reconcile(in, Instrument.MNQ, new BigDecimal("30010.00"));

        assertThat(out.currentPosition()).isEqualTo(WtxRsiPosition.LONG);
        assertThat(out).isSameAs(in);
    }

    @Test
    void keepsOptimisticSideWhileEntryInFlight() {
        // A resting/partial entry is NOT yet a confirmed position — don't clear the optimistic side.
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any()))
            .thenReturn(Optional.of(row(ExecutionStatus.ENTRY_SUBMITTED, "LONG")));

        WtxRsiStrategyState in = liveLong();
        WtxRsiStrategyState out = reconciler().reconcile(in, Instrument.MNQ, new BigDecimal("30010.00"));

        assertThat(out).isSameAs(in);
        assertThat(out.currentPosition()).isEqualTo(WtxRsiPosition.LONG);
    }

    @Test
    void doesNotReconcilePaperState() {
        WtxRsiStrategyState paper = WtxRsiStrategyState.initial("MNQ", "5m").withPosition(
            WtxRsiPosition.LONG, new BigDecimal("30000.00"), BigDecimal.ONE,
            new BigDecimal("29950.00"), new BigDecimal("30100.00")); // autoExecution OFF

        WtxRsiStrategyState out = reconciler().reconcile(paper, Instrument.MNQ, new BigDecimal("30010.00"));

        assertThat(out).isSameAs(paper);
    }

    @Test
    void adoptsRowPositionWhenStrategyFlatButRowLive() {
        // Strategy thinks FLAT, but the row records a live SHORT (e.g. a fill the strategy missed) → adopt it.
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any()))
            .thenReturn(Optional.of(row(ExecutionStatus.ACTIVE, "SHORT")));
        WtxRsiStrategyState flat = WtxRsiStrategyState.initial("MNQ", "5m").withAutoExecution(true);

        WtxRsiStrategyState out = reconciler().reconcile(flat, Instrument.MNQ, new BigDecimal("30010.00"));

        assertThat(out.currentPosition()).isEqualTo(WtxRsiPosition.SHORT);
        assertThat(out.entryPrice()).isEqualByComparingTo("30000.00");
        assertThat(out.stopLoss()).isEqualByComparingTo("29950.00");
    }
}

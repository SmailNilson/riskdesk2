package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WtxClosePnlSettlerTest {

    private TradeExecutionRepositoryPort repo;
    private WtxClosePnlSettler settler;

    @BeforeEach
    void setUp() {
        repo = mock(TradeExecutionRepositoryPort.class);
        settler = new WtxClosePnlSettler(repo);
    }

    /** A state that just optimistically closed a position: FLAT, auto-exec, with booked + pending close P&L. */
    private WtxStrategyState closedWithPending(String pnl) {
        return WtxStrategyState.initial("MNQ", "5m", new BigDecimal("10000"))
            .withAutoExecution(true)
            .withFlat(new BigDecimal(pnl))          // booked the close P&L into realized/equity
            .withPendingClose(new BigDecimal(pnl)); // marked it pending (broker fill unconfirmed)
    }

    private void stubRow(ExecutionStatus status) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setStatus(status);
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any()))
            .thenReturn(Optional.of(r));
    }

    @Test
    void noPending_returnsUnchanged_andNeverReadsTheRow() {
        WtxStrategyState s = WtxStrategyState.initial("MNQ", "5m", new BigDecimal("10000")).withAutoExecution(true);

        assertThat(settler.settle(s)).isSameAs(s);
        verifyNoInteractions(repo);
    }

    @Test
    void closeFilled_noNonTerminalRow_finalizesKeepingPnl() {
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any())).thenReturn(Optional.empty());

        WtxStrategyState out = settler.settle(closedWithPending("100")); // realized 100, equity 10100

        assertThat(out.hasPendingClose()).isFalse();                 // marker cleared
        assertThat(out.dailyRealizedPnl()).isEqualByComparingTo("100"); // P&L kept — close confirmed
        assertThat(out.currentEquity()).isEqualByComparingTo("10100");
    }

    @Test
    void closeCancelled_rowRevivedActive_rollsBackPnl() {
        stubRow(ExecutionStatus.ACTIVE); // close cancelled without a fill → fill tracker revived to ACTIVE

        WtxStrategyState out = settler.settle(closedWithPending("100"));

        assertThat(out.hasPendingClose()).isFalse();
        assertThat(out.dailyRealizedPnl()).isEqualByComparingTo("0");   // rolled back — close never completed
        assertThat(out.currentEquity()).isEqualByComparingTo("10000");
    }

    @Test
    void closeStillResting_rowExitSubmitted_waits() {
        stubRow(ExecutionStatus.EXIT_SUBMITTED); // close still in flight

        WtxStrategyState in = closedWithPending("100");
        WtxStrategyState out = settler.settle(in);

        assertThat(out).isSameAs(in);            // unchanged — wait for the close to resolve
        assertThat(out.hasPendingClose()).isTrue();
        assertThat(out.dailyRealizedPnl()).isEqualByComparingTo("100");
    }

    @Test
    void rollbackOfALosingClose_addsBackTheLoss() {
        stubRow(ExecutionStatus.ACTIVE);

        WtxStrategyState out = settler.settle(closedWithPending("-250")); // booked a -250 loss

        assertThat(out.dailyRealizedPnl()).isEqualByComparingTo("0");     // loss un-booked (position still live)
        assertThat(out.currentEquity()).isEqualByComparingTo("10000");
    }
}

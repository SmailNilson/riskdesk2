package com.riskdesk.domain.engine.strategy.wtx;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Pending-close-P&L wither contracts (Slice: WTX close-P&L finalized only on confirmed close). */
class WtxStrategyStateTest {

    private WtxStrategyState closedWithPending(String pnl) {
        return WtxStrategyState.initial("MNQ", "5m", new BigDecimal("10000"))
            .withAutoExecution(true)
            .withFlat(new BigDecimal(pnl))          // realized += pnl, equity = 10000 + pnl
            .withPendingClose(new BigDecimal(pnl)); // mark pending
    }

    @Test
    void withPendingClose_marksPending() {
        WtxStrategyState s = closedWithPending("100");
        assertThat(s.hasPendingClose()).isTrue();
        assertThat(s.pendingClosePnl()).isEqualByComparingTo("100");
        assertThat(s.dailyRealizedPnl()).isEqualByComparingTo("100");
    }

    @Test
    void withClosePnlRolledBack_unbooksPendingFromRealizedAndEquity() {
        WtxStrategyState r = closedWithPending("100").withClosePnlRolledBack();
        assertThat(r.dailyRealizedPnl()).isEqualByComparingTo("0");
        assertThat(r.currentEquity()).isEqualByComparingTo("10000");
        assertThat(r.hasPendingClose()).isFalse();
    }

    @Test
    void withPendingClosePnlFinalized_keepsPnl_clearsMarker() {
        WtxStrategyState f = closedWithPending("100").withPendingClosePnlFinalized();
        assertThat(f.dailyRealizedPnl()).isEqualByComparingTo("100");
        assertThat(f.currentEquity()).isEqualByComparingTo("10100");
        assertThat(f.hasPendingClose()).isFalse();
    }

    @Test
    void withDayReset_clearsPending() {
        WtxStrategyState s = closedWithPending("100");
        assertThat(s.withDayReset(s.currentEquity()).hasPendingClose()).isFalse();
    }

    @Test
    void withPosition_clearsPending() {
        WtxStrategyState o = closedWithPending("100")
            .withPosition(WtxPosition.LONG, new BigDecimal("18000"), BigDecimal.valueOf(2));
        assertThat(o.hasPendingClose()).isFalse();
    }

    @Test
    void zeroPnlClose_isNotTreatedAsPending() {
        // A break-even close has no P&L to roll back, so it does not arm the pending marker.
        WtxStrategyState s = closedWithPending("0");
        assertThat(s.hasPendingClose()).isFalse();
    }

    @Test
    void initialState_hasNoPending() {
        assertThat(WtxStrategyState.initial("MNQ", "5m", new BigDecimal("10000")).hasPendingClose()).isFalse();
    }
}

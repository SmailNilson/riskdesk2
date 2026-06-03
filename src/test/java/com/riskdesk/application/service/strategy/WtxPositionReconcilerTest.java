package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
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

class WtxPositionReconcilerTest {

    private TradeExecutionRepositoryPort repo;
    private WtxPositionReconciler reconciler;

    private static final Instrument MNQ = Instrument.MNQ;
    private static final BigDecimal CLOSE = new BigDecimal("18010.00");
    private static final BigDecimal ATR = new BigDecimal("12.50"); // bar ATR passed to a re-adopt

    @BeforeEach
    void setUp() {
        repo = mock(TradeExecutionRepositoryPort.class);
        reconciler = new WtxPositionReconciler(repo);
    }

    /** Auto-exec ON, FLAT, no pending close. */
    private WtxStrategyState liveFlat() {
        return WtxStrategyState.initial("MNQ", "5m", new BigDecimal("10000")).withAutoExecution(true);
    }

    private WtxStrategyState liveLong(String entry, int qty) {
        return liveFlat().withPosition(WtxPosition.LONG, new BigDecimal(entry), BigDecimal.valueOf(qty));
    }

    private void stubRow(TradeExecutionRecord row) {
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any()))
            .thenReturn(Optional.ofNullable(row));
    }

    private TradeExecutionRecord row(ExecutionStatus status, String action, String avgFill, int qty) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setInstrument("MNQ");
        r.setTimeframe("5m");
        r.setTriggerSource(ExecutionTriggerSource.WTX_AUTO);
        r.setStatus(status);
        r.setAction(action);
        r.setQuantity(qty);
        r.setNormalizedEntryPrice(new BigDecimal("18000.00"));
        if (avgFill != null) {
            r.setAvgFillPrice(new BigDecimal(avgFill));
            r.setFilledQuantity(BigDecimal.valueOf(qty));
        }
        return r;
    }

    @Test
    void paperTimeframe_returnsUnchanged_andNeverReadsTheRow() {
        WtxStrategyState paper = WtxStrategyState.initial("MNQ", "5m", new BigDecimal("10000"))
            .withPosition(WtxPosition.LONG, new BigDecimal("18000"), BigDecimal.valueOf(2)); // auto-exec OFF

        WtxStrategyState out = reconciler.reconcile(paper, MNQ, CLOSE, ATR);

        assertThat(out).isSameAs(paper);
        verifyNoInteractions(repo);
    }

    @Test
    void closePending_defersToSettler_andNeverReadsTheRow() {
        // A close is mid-settlement (pending P&L). The reconciler must NOT re-adopt — that would clear the
        // settler's pending marker before it can finalize/roll back, re-introducing the double-count.
        WtxStrategyState pending = liveFlat()
            .withFlat(new BigDecimal("50")).withPendingClose(new BigDecimal("50"));

        WtxStrategyState out = reconciler.reconcile(pending, MNQ, CLOSE, ATR);

        assertThat(out).isSameAs(pending);
        verifyNoInteractions(repo);
    }

    @Test
    void aligned_long_sameFill_returnsUnchanged() {
        stubRow(row(ExecutionStatus.ACTIVE, "LONG", "18000.00", 2));

        WtxStrategyState in = liveLong("18000.00", 2);
        assertThat(reconciler.reconcile(in, MNQ, CLOSE, ATR)).isSameAs(in);
    }

    @Test
    void sameSide_rowHasDifferentFill_correctsEntryBasis_preservesTrailing() {
        stubRow(row(ExecutionStatus.ACTIVE, "LONG", "18003.25", 2));
        WtxStrategyState in = liveLong("18000.00", 2)
            .withTrailing(new BigDecimal("18050"), new BigDecimal("18020"));

        WtxStrategyState out = reconciler.reconcile(in, MNQ, CLOSE, ATR);

        assertThat(out.currentPosition()).isEqualTo(WtxPosition.LONG);
        assertThat(out.entryPrice()).isEqualByComparingTo("18003.25");           // adopted real fill
        assertThat(out.entryQty()).isEqualByComparingTo("2");
        assertThat(out.bestFavorablePrice()).isEqualByComparingTo("18050");        // trailing preserved
        assertThat(out.trailingStopPrice()).isEqualByComparingTo("18020");
        assertThat(out.dailyRealizedPnl()).isEqualByComparingTo(in.dailyRealizedPnl());
    }

    @Test
    void sameSide_rowNotFilledYet_keepsOptimisticEntry() {
        stubRow(row(ExecutionStatus.ACTIVE, "LONG", null, 2));
        WtxStrategyState in = liveLong("18000.00", 2);
        assertThat(reconciler.reconcile(in, MNQ, CLOSE, ATR)).isSameAs(in);
    }

    @Test
    void inFlightEntry_keepsOptimisticSide() {
        stubRow(row(ExecutionStatus.ENTRY_SUBMITTED, "LONG", null, 2));
        WtxStrategyState in = liveLong("18000.00", 2);
        assertThat(reconciler.reconcile(in, MNQ, CLOSE, ATR)).isSameAs(in);
    }

    @Test
    void strategyFlat_rowActiveLong_adoptsFromFill() {
        stubRow(row(ExecutionStatus.ACTIVE, "LONG", "18002.50", 3));

        WtxStrategyState out = reconciler.reconcile(liveFlat(), MNQ, CLOSE, ATR);

        assertThat(out.currentPosition()).isEqualTo(WtxPosition.LONG);
        assertThat(out.entryPrice()).isEqualByComparingTo("18002.50");
        assertThat(out.entryQty()).isEqualByComparingTo("3");
        assertThat(out.entryAtr()).isEqualByComparingTo(ATR); // re-adopt carries an ATR basis → trailing works
    }

    @Test
    void strategyFlat_rowActive_noFill_adoptsLimitAndRequestedQty() {
        stubRow(row(ExecutionStatus.ACTIVE, "SHORT", null, 2));

        WtxStrategyState out = reconciler.reconcile(liveFlat(), MNQ, CLOSE, ATR);

        assertThat(out.currentPosition()).isEqualTo(WtxPosition.SHORT);
        assertThat(out.entryPrice()).isEqualByComparingTo("18000.00");
        assertThat(out.entryQty()).isEqualByComparingTo("2");
    }

    @Test
    void strategyLong_rowGone_flattensAndRealizesAtClose() {
        stubRow(null); // missed close — strategy never booked it (no pending)

        WtxStrategyState out = reconciler.reconcile(liveLong("18000.00", 2), MNQ, CLOSE, ATR);

        assertThat(out.currentPosition()).isEqualTo(WtxPosition.FLAT);
        BigDecimal expectedPnl = MNQ.calculatePnL(new BigDecimal("18000.00"), CLOSE, 2, Side.LONG);
        assertThat(out.dailyRealizedPnl()).isEqualByComparingTo(expectedPnl);
        assertThat(expectedPnl.signum()).isPositive();
    }

    @Test
    void strategyLong_rowActiveShort_flattensThenAdoptsOpposite() {
        stubRow(row(ExecutionStatus.ACTIVE, "SHORT", "18011.00", 2));

        WtxStrategyState out = reconciler.reconcile(liveLong("18000.00", 2), MNQ, CLOSE, ATR);

        assertThat(out.currentPosition()).isEqualTo(WtxPosition.SHORT);
        assertThat(out.entryPrice()).isEqualByComparingTo("18011.00");
        BigDecimal realized = MNQ.calculatePnL(new BigDecimal("18000.00"), CLOSE, 2, Side.LONG);
        assertThat(out.dailyRealizedPnl()).isEqualByComparingTo(realized);
    }

    @Test
    void strategyFlat_noPending_rowExitSubmitted_adoptsOriginalSide() {
        // FLAT with NO pending (e.g. a restart) but the row shows a close resting → the position is still
        // live on the row's side until that close fills.
        stubRow(row(ExecutionStatus.EXIT_SUBMITTED, "LONG", "18000.00", 2));

        WtxStrategyState out = reconciler.reconcile(liveFlat(), MNQ, CLOSE, ATR);

        assertThat(out.currentPosition()).isEqualTo(WtxPosition.LONG);
        assertThat(out.entryAtr()).isEqualByComparingTo(ATR); // re-adopted position keeps a protective ATR basis
    }
}

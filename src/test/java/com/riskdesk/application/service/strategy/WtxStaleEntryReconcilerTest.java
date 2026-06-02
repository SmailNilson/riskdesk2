package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.BrokerOrderLookup;
import com.riskdesk.application.dto.BrokerOrderStatusView;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WtxStaleEntryReconcilerTest {

    private IbkrOrderService orderService;
    private TradeExecutionRepositoryPort repo;
    private IbkrProperties props;
    private IbkrPortfolioService portfolio;

    @BeforeEach
    void setUp() {
        orderService = mock(IbkrOrderService.class);
        repo = mock(TradeExecutionRepositoryPort.class);
        portfolio = mock(IbkrPortfolioService.class);
        props = new IbkrProperties();
        props.setEnabled(true);
    }

    private WtxStaleEntryReconciler reconciler(long graceSeconds) {
        return new WtxStaleEntryReconciler(orderService, repo, props, portfolio, graceSeconds);
    }

    private TradeExecutionRecord stuckRow(String key, long ageMinutes) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(1L);
        r.setExecutionKey(key);
        r.setBrokerAccountId("wtx-default");
        r.setInstrument("MNQ");
        r.setTimeframe("5m");
        r.setTriggerSource(ExecutionTriggerSource.WTX_AUTO);
        r.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        Instant ts = Instant.now().minus(ageMinutes, ChronoUnit.MINUTES);
        r.setEntrySubmittedAt(ts);
        r.setCreatedAt(ts);
        r.setUpdatedAt(ts);
        return r;
    }

    private void seed(TradeExecutionRecord row) {
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.WTX_AUTO, ExecutionStatus.ENTRY_SUBMITTED))
                .thenReturn(List.of(row));
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    private static IbkrPortfolioSnapshot snapshot(boolean connected, List<IbkrPositionView> legs) {
        return new IbkrPortfolioSnapshot(connected, "DU123", List.of(), bd(10000), bd(2000), bd(8000),
                bd(8000), bd(0), bd(0), bd(0), "USD", legs, null);
    }

    private static IbkrPositionView leg(String contractDesc, BigDecimal qty) {
        return legAcct("DU123", contractDesc, qty);
    }

    private static IbkrPositionView legAcct(String account, String contractDesc, BigDecimal qty) {
        return new IbkrPositionView(account, 1L, contractDesc, "FUT", qty,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD");
    }

    // ── FOUND-status reconciles ────────────────────────────────────────────

    @Test
    void cancelledOrder_reconcilesRowToCancelled() {
        TradeExecutionRecord row = stuckRow("wtx:MNQ:5m:1:OPEN_SHORT", 10);
        seed(row);
        when(orderService.findOrder(any(), eq(row.getExecutionKey())))
                .thenReturn(BrokerOrderLookup.found(new BrokerOrderStatusView(99L, row.getExecutionKey(), "acct", "Cancelled")));

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.CANCELLED, row.getStatus());
        verify(repo).save(row);
    }

    @Test
    void inactiveOrder_reconcilesRowToCancelled() {
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any()))
                .thenReturn(BrokerOrderLookup.found(new BrokerOrderStatusView(99L, "k", "acct", "Inactive")));

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.CANCELLED, row.getStatus());
    }

    @Test
    void filledOrder_reconcilesRowToActive_andSetsEntryFilledAt() {
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any()))
                .thenReturn(BrokerOrderLookup.found(new BrokerOrderStatusView(99L, "k", "acct", "Filled")));

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ACTIVE, row.getStatus());
        assertNotNull(row.getEntryFilledAt(), "missed-fill activation must stamp entryFilledAt");
        verify(repo).save(row);
    }

    @Test
    void liveOrder_leavesRowUntouched() {
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any()))
                .thenReturn(BrokerOrderLookup.found(new BrokerOrderStatusView(99L, "k", "acct", "Submitted")));

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus(), "a genuinely resting order is not stale");
        verify(repo, never()).save(any());
    }

    // ── NOT_FOUND gated by the IBKR position truth ─────────────────────────

    @Test
    void notFound_ibkrFlat_reconcilesToCancelled() {
        // The order is gone (rejected at submit / never placed) AND IBKR holds no position → phantom.
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any())).thenReturn(BrokerOrderLookup.notFound());
        when(portfolio.getPortfolio(any())).thenReturn(snapshot(true, List.of())); // flat

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.CANCELLED, row.getStatus());
        verify(repo).save(row);
    }

    @Test
    void notFound_ibkrHoldsPosition_leavesRow() {
        // Not in completed orders but a live MNQ position exists → the order likely filled and its fill
        // aged out; cancelling would hide a real position. Leave it.
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any())).thenReturn(BrokerOrderLookup.notFound());
        when(portfolio.getPortfolio(any())).thenReturn(snapshot(true, List.of(leg("MNQM6", bd(-1)))));

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus(), "must not cancel a row backed by a live position");
        verify(repo, never()).save(any());
    }

    @Test
    void notFound_portfolioUnavailable_leavesRow() {
        // Can't read positions (disconnected) → can't confirm flat → never guess.
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any())).thenReturn(BrokerOrderLookup.notFound());
        when(portfolio.getPortfolio(any())).thenReturn(snapshot(false, List.of())); // disconnected

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        verify(repo, never()).save(any());
    }

    @Test
    void notFound_offsettingLegs_notFlat_leavesRow() {
        // Net zero but live offsetting rollover legs = a live position → not flat → leave.
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any())).thenReturn(BrokerOrderLookup.notFound());
        when(portfolio.getPortfolio(any()))
                .thenReturn(snapshot(true, List.of(leg("MNQM6", bd(1)), leg("MNQU6", bd(-1)))));

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        verify(repo, never()).save(any());
    }

    @Test
    void notFound_positionInOtherAccount_isStillFlatForThisAccount() {
        // Codex P2: a multi-account gateway returns positions across accounts. The row's account is
        // DU777; a same-instrument position on DU111 must be filtered out, or the phantom row would
        // stay stuck forever whenever another account holds the same future.
        TradeExecutionRecord row = stuckRow("k", 10);
        row.setBrokerAccountId("DU777");
        seed(row);
        when(orderService.findOrder(any(), any())).thenReturn(BrokerOrderLookup.notFound());
        when(portfolio.getPortfolio(any()))
                .thenReturn(snapshot(true, List.of(legAcct("DU111", "MNQM6", bd(-1))))); // other account

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.CANCELLED, row.getStatus(),
                "another account's position must not block this account's phantom-row reconcile");
    }

    @Test
    void notFound_positionInSameConfiguredAccount_leavesRow() {
        // Counterpart: a position on the row's OWN configured account is a real position → not flat.
        TradeExecutionRecord row = stuckRow("k", 10);
        row.setBrokerAccountId("DU777");
        seed(row);
        when(orderService.findOrder(any(), any())).thenReturn(BrokerOrderLookup.notFound());
        when(portfolio.getPortfolio(any()))
                .thenReturn(snapshot(true, List.of(legAcct("DU777", "MNQM6", bd(-1)))));

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        verify(repo, never()).save(any());
    }

    // ── Uncertainty is never reconciled ────────────────────────────────────

    @Test
    void unavailableLookup_leavesRow_andNeverChecksPositions() {
        // UNAVAILABLE (gateway outage) must never be read as absence — regardless of age, and without
        // even consulting positions (we already know we can't confirm anything).
        TradeExecutionRecord row = stuckRow("k", 30 * 60);
        seed(row);
        when(orderService.findOrder(any(), any())).thenReturn(BrokerOrderLookup.unavailable());

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        verify(repo, never()).save(any());
        verify(portfolio, never()).getPortfolio(any());
    }

    @Test
    void freshRowWithinGrace_isNotLookedUp() {
        TradeExecutionRecord row = stuckRow("k", 0); // just submitted
        seed(row);

        reconciler(120).reconcileStaleEntries();

        verify(orderService, never()).findOrder(any(), any());
        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
    }

    @Test
    void ibkrDisabled_isNoOp() {
        props.setEnabled(false);
        reconciler(120).reconcileStaleEntries();
        verify(repo, never()).findByTriggerSourceAndStatus(any(), any());
        verify(orderService, never()).findOrder(any(), any());
    }

    @Test
    void lookupThrows_leavesRowUntouched() {
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any())).thenThrow(new RuntimeException("gateway down"));

        reconciler(120).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        verify(repo, never()).save(any());
    }
}

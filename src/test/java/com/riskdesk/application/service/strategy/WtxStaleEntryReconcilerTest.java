package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.BrokerOrderStatusView;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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

    @BeforeEach
    void setUp() {
        orderService = mock(IbkrOrderService.class);
        repo = mock(TradeExecutionRepositoryPort.class);
        props = new IbkrProperties();
        props.setEnabled(true);
    }

    private WtxStaleEntryReconciler reconciler(long graceSeconds, long maxAgeHours) {
        return new WtxStaleEntryReconciler(orderService, repo, props, graceSeconds, maxAgeHours);
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

    @Test
    void cancelledOrder_reconcilesRowToCancelled() {
        TradeExecutionRecord row = stuckRow("wtx:MNQ:5m:1:OPEN_SHORT", 10);
        seed(row);
        when(orderService.findOrder(any(), eq(row.getExecutionKey())))
                .thenReturn(Optional.of(new BrokerOrderStatusView(99L, row.getExecutionKey(), "acct", "Cancelled")));

        reconciler(120, 24).reconcileStaleEntries();

        assertEquals(ExecutionStatus.CANCELLED, row.getStatus());
        verify(repo).save(row);
    }

    @Test
    void inactiveOrder_reconcilesRowToCancelled() {
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any()))
                .thenReturn(Optional.of(new BrokerOrderStatusView(99L, "k", "acct", "Inactive")));

        reconciler(120, 24).reconcileStaleEntries();

        assertEquals(ExecutionStatus.CANCELLED, row.getStatus());
    }

    @Test
    void filledOrder_reconcilesRowToActive_andSetsEntryFilledAt() {
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any()))
                .thenReturn(Optional.of(new BrokerOrderStatusView(99L, "k", "acct", "Filled")));

        reconciler(120, 24).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ACTIVE, row.getStatus());
        assertNotNull(row.getEntryFilledAt(), "missed-fill activation must stamp entryFilledAt");
        verify(repo).save(row);
    }

    @Test
    void liveOrder_leavesRowUntouched() {
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any()))
                .thenReturn(Optional.of(new BrokerOrderStatusView(99L, "k", "acct", "Submitted")));

        reconciler(120, 24).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus(), "a genuinely resting order is not stale");
        verify(repo, never()).save(any());
    }

    @Test
    void notFoundAndOlderThanMaxAge_reconcilesToCancelled() {
        // 30h old, not found in live OR completed → a DAY order can't survive that long → gone.
        TradeExecutionRecord row = stuckRow("k", 30 * 60);
        seed(row);
        when(orderService.findOrder(any(), any())).thenReturn(Optional.empty());

        reconciler(120, 24).reconcileStaleEntries();

        assertEquals(ExecutionStatus.CANCELLED, row.getStatus());
    }

    @Test
    void notFoundButRecent_leavesRowUntouched() {
        // 10 min old, not found → could be transient / same-session; never guess prematurely.
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any())).thenReturn(Optional.empty());

        reconciler(120, 24).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        verify(repo, never()).save(any());
    }

    @Test
    void freshRowWithinGrace_isNotLookedUp() {
        TradeExecutionRecord row = stuckRow("k", 0); // just submitted
        seed(row);

        reconciler(120, 24).reconcileStaleEntries();

        verify(orderService, never()).findOrder(any(), any());
        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
    }

    @Test
    void ibkrDisabled_isNoOp() {
        props.setEnabled(false);
        reconciler(120, 24).reconcileStaleEntries();
        verify(repo, never()).findByTriggerSourceAndStatus(any(), any());
        verify(orderService, never()).findOrder(any(), any());
    }

    @Test
    void lookupThrows_leavesRowUntouched() {
        TradeExecutionRecord row = stuckRow("k", 10);
        seed(row);
        when(orderService.findOrder(any(), any())).thenThrow(new RuntimeException("gateway down"));

        reconciler(120, 24).reconcileStaleEntries();

        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        verify(repo, never()).save(any());
    }
}

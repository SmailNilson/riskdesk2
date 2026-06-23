package com.riskdesk.application.execution;

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
import com.riskdesk.domain.notification.port.NotificationPort;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlaybookBrokerTruthReconcilerTest {

    private IbkrOrderService ibkrOrderService;
    private TradeExecutionRepositoryPort repo;
    private IbkrProperties ibkrProperties;
    private IbkrPortfolioService portfolioService;
    private ExecutionReadinessGate readinessGate;
    private NotificationPort notificationPort;
    private PlaybookBrokerTruthReconciler reconciler;

    @BeforeEach
    void setUp() {
        ibkrOrderService = mock(IbkrOrderService.class);
        repo = mock(TradeExecutionRepositoryPort.class);
        ibkrProperties = mock(IbkrProperties.class);
        portfolioService = mock(IbkrPortfolioService.class);
        readinessGate = mock(ExecutionReadinessGate.class);
        notificationPort = mock(NotificationPort.class);
        when(ibkrProperties.isEnabled()).thenReturn(true);
        reconciler = new PlaybookBrokerTruthReconciler(ibkrOrderService, repo, ibkrProperties,
            portfolioService, readinessGate, notificationPort, 120, 30);
    }

    // ─── P1: stale-entry recovery ───────────────────────────────────────────────────────────────

    @Test
    void p1_ibkr_disabled_does_nothing() {
        when(ibkrProperties.isEnabled()).thenReturn(false);
        reconciler.reconcileStaleEntries();
        verifyNoInteractions(repo, ibkrOrderService, portfolioService);
    }

    @Test
    void p1_fresh_row_within_grace_is_left() {
        TradeExecutionRecord row = entryRow(1L, secondsAgo(10)); // younger than 120s grace
        stubEntries(row);
        reconciler.reconcileStaleEntries();
        verify(ibkrOrderService, never()).findOrder(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void p1_found_live_order_is_left() {
        TradeExecutionRecord row = entryRow(2L, secondsAgo(300));
        stubEntries(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-2"))).thenReturn(found("Submitted"));
        reconciler.reconcileStaleEntries();
        verify(repo, never()).save(any());
    }

    @Test
    void p1_found_filled_recovers_to_active() {
        TradeExecutionRecord row = entryRow(3L, secondsAgo(300));
        stubEntries(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-3"))).thenReturn(found("Filled"));

        reconciler.reconcileStaleEntries();

        ArgumentCaptor<TradeExecutionRecord> saved = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutionStatus.ACTIVE);
        assertThat(saved.getValue().getEntryFilledAt()).isNotNull();
        verify(notificationPort).sendExecutionReconciled(any());
    }

    @Test
    void p1_found_cancelled_marks_cancelled() {
        TradeExecutionRecord row = entryRow(4L, secondsAgo(300));
        stubEntries(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-4"))).thenReturn(found("Cancelled"));

        reconciler.reconcileStaleEntries();

        ArgumentCaptor<TradeExecutionRecord> saved = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void p1_unavailable_lookup_is_left() {
        TradeExecutionRecord row = entryRow(5L, secondsAgo(300));
        stubEntries(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-5"))).thenReturn(BrokerOrderLookup.unavailable());
        reconciler.reconcileStaleEntries();
        verify(repo, never()).save(any());
        verifyNoInteractions(portfolioService);
    }

    @Test
    void p1_not_found_and_flat_marks_cancelled() {
        TradeExecutionRecord row = entryRow(6L, secondsAgo(300));
        stubEntries(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-6"))).thenReturn(BrokerOrderLookup.notFound());
        when(portfolioService.getPortfolio(any())).thenReturn(flatPortfolio());

        reconciler.reconcileStaleEntries();

        ArgumentCaptor<TradeExecutionRecord> saved = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void p1_not_found_but_position_exists_is_left() {
        TradeExecutionRecord row = entryRow(7L, secondsAgo(300));
        stubEntries(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-7"))).thenReturn(BrokerOrderLookup.notFound());
        when(portfolioService.getPortfolio(any())).thenReturn(portfolioWith("MNQ", new BigDecimal("1")));

        reconciler.reconcileStaleEntries();

        verify(repo, never()).save(any()); // a live position means the fill aged out — never cancel
    }

    @Test
    void p1_lookup_throws_is_left() {
        TradeExecutionRecord row = entryRow(8L, secondsAgo(300));
        stubEntries(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-8"))).thenThrow(new RuntimeException("gateway hiccup"));
        reconciler.reconcileStaleEntries();
        verify(repo, never()).save(any());
    }

    // ─── P2: orphan-order cancel ────────────────────────────────────────────────────────────────

    @Test
    void p2_no_order_id_does_not_cancel() {
        TradeExecutionRecord row = cancelledRow(10L, secondsAgo(300), null);
        stubCancelled(row);
        reconciler.cancelOrphanOrders();
        verify(ibkrOrderService, never()).cancelOrder(anyInt());
    }

    @Test
    void p2_fresh_cancel_within_grace_is_left() {
        TradeExecutionRecord row = cancelledRow(11L, secondsAgo(10), 4242);
        stubCancelled(row);
        reconciler.cancelOrphanOrders();
        verify(ibkrOrderService, never()).findOrder(any(), any());
        verify(ibkrOrderService, never()).cancelOrder(anyInt());
    }

    @Test
    void p2_too_old_is_outside_window() {
        TradeExecutionRecord row = cancelledRow(12L, minutesAgo(45), 4242); // beyond 30-min window
        stubCancelled(row);
        reconciler.cancelOrphanOrders();
        verify(ibkrOrderService, never()).findOrder(any(), any());
        verify(ibkrOrderService, never()).cancelOrder(anyInt());
    }

    @Test
    void p2_found_live_orphan_is_recancelled() {
        TradeExecutionRecord row = cancelledRow(13L, secondsAgo(300), 4242);
        stubCancelled(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-13"))).thenReturn(found("Submitted"));
        when(ibkrOrderService.cancelOrder(4242)).thenReturn("Cancelled");

        reconciler.cancelOrphanOrders();

        verify(ibkrOrderService).cancelOrder(4242);
        verify(notificationPort).sendExecutionReconciled(any());
        verify(repo, never()).save(any()); // row stays CANCELLED — broker callback owns finalization
    }

    @Test
    void p2_found_filled_does_not_cancel_but_alarms() {
        // The cancel raced a fill: an unprotected live position on a CANCELLED row. Must NOT cancel
        // (can't cancel a fill / would hide the position) but must alarm loudly.
        TradeExecutionRecord row = cancelledRow(14L, secondsAgo(300), 4242);
        stubCancelled(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-14"))).thenReturn(found("Filled"));

        reconciler.cancelOrphanOrders();

        verify(ibkrOrderService, never()).cancelOrder(anyInt());
        verify(notificationPort).sendExecutionReconciled(any());
    }

    @Test
    void p2_found_cancelled_is_consistent_no_action() {
        TradeExecutionRecord row = cancelledRow(15L, secondsAgo(300), 4242);
        stubCancelled(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-15"))).thenReturn(found("Cancelled"));
        reconciler.cancelOrphanOrders();
        verify(ibkrOrderService, never()).cancelOrder(anyInt());
    }

    @Test
    void p2_not_found_does_not_cancel() {
        TradeExecutionRecord row = cancelledRow(16L, secondsAgo(300), 4242);
        stubCancelled(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-16"))).thenReturn(BrokerOrderLookup.notFound());
        reconciler.cancelOrphanOrders();
        verify(ibkrOrderService, never()).cancelOrder(anyInt());
    }

    @Test
    void p2_unavailable_does_not_cancel() {
        TradeExecutionRecord row = cancelledRow(17L, secondsAgo(300), 4242);
        stubCancelled(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-17"))).thenReturn(BrokerOrderLookup.unavailable());
        reconciler.cancelOrphanOrders();
        verify(ibkrOrderService, never()).cancelOrder(anyInt());
    }

    @Test
    void p2_cancel_throwing_does_not_rewrite_row() {
        TradeExecutionRecord row = cancelledRow(18L, secondsAgo(300), 4242);
        stubCancelled(row);
        when(ibkrOrderService.findOrder(any(), eq("pb-18"))).thenReturn(found("Submitted"));
        when(ibkrOrderService.cancelOrder(4242)).thenThrow(new RuntimeException("IBKR 161 already filled"));

        reconciler.cancelOrphanOrders();

        verify(ibkrOrderService).cancelOrder(4242);
        verify(repo, never()).save(any());
    }

    // ─── fixtures ───────────────────────────────────────────────────────────────────────────────

    private void stubEntries(TradeExecutionRecord... rows) {
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.ENTRY_SUBMITTED))
            .thenReturn(List.of(rows));
    }

    private void stubCancelled(TradeExecutionRecord... rows) {
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.CANCELLED))
            .thenReturn(List.of(rows));
    }

    private static TradeExecutionRecord entryRow(long id, Instant submittedAt) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(id);
        r.setInstrument("MNQ");
        r.setTimeframe("10m");
        r.setAction("LONG");
        r.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        r.setExecutionKey("pb-" + id);
        r.setEntrySubmittedAt(submittedAt);
        r.setCreatedAt(submittedAt);
        r.setUpdatedAt(submittedAt);
        return r;
    }

    private static TradeExecutionRecord cancelledRow(long id, Instant cancelledAt, Integer orderId) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(id);
        r.setInstrument("MNQ");
        r.setTimeframe("10m");
        r.setAction("LONG");
        r.setStatus(ExecutionStatus.CANCELLED);
        r.setExecutionKey("pb-" + id);
        r.setIbkrOrderId(orderId);
        r.setEntrySubmittedAt(cancelledAt.minus(2, ChronoUnit.HOURS)); // submitted long before the cancel
        r.setCreatedAt(cancelledAt.minus(2, ChronoUnit.HOURS));
        r.setUpdatedAt(cancelledAt); // P2 ages from updatedAt (= when cancelled)
        return r;
    }

    private static BrokerOrderLookup found(String status) {
        return BrokerOrderLookup.found(new BrokerOrderStatusView(1L, "ref", "acct", status));
    }

    private static IbkrPortfolioSnapshot flatPortfolio() {
        return new IbkrPortfolioSnapshot(true, null, List.of(), null, null, null, null, null, null, null,
            "USD", List.of(), null);
    }

    private static IbkrPortfolioSnapshot portfolioWith(String contractDesc, BigDecimal qty) {
        IbkrPositionView pos = new IbkrPositionView(null, 1L, contractDesc, "FUT", qty,
            null, null, null, null, null, null, "USD");
        return new IbkrPortfolioSnapshot(true, null, List.of(), null, null, null, null, null, null, null,
            "USD", List.of(pos), null);
    }

    private static Instant secondsAgo(long s) {
        return Instant.now().minusSeconds(s);
    }

    private static Instant minutesAgo(long m) {
        return Instant.now().minus(m, ChronoUnit.MINUTES);
    }
}

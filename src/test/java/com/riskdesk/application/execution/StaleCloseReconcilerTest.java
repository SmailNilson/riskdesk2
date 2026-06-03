package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerOrderLookup;
import com.riskdesk.application.dto.BrokerOrderStatusView;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.service.ExecutionFillTrackingService;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the broker-truth close reconciler: a stuck EXIT_SUBMITTED row whose close FILLED (IBKR flat,
 * nothing working) replays the missed Filled callback; everything uncertain is left untouched.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaleCloseReconcilerTest {

    @Mock IbkrOrderService ibkrOrderService;
    @Mock TradeExecutionRepositoryPort repo;
    @Mock IbkrPortfolioService portfolioService;
    @Mock ExecutionFillTrackingService fillTracking;
    @Mock IbkrProperties ibkrProperties;

    private StaleCloseReconciler reconciler;

    @BeforeEach
    void setUp() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        // Default: order gone (not working), IBKR flat.
        when(ibkrOrderService.findOrder(any(), any())).thenReturn(BrokerOrderLookup.notFound());
        when(portfolioService.getPortfolio(any())).thenReturn(flatSnapshot());
        reconciler = newReconciler(true, 0);
    }

    private StaleCloseReconciler newReconciler(boolean enabled, long graceSeconds) {
        return new StaleCloseReconciler(ibkrOrderService, repo, portfolioService, fillTracking,
            () -> true, ibkrProperties, enabled, graceSeconds);
    }

    @Test
    void replaysFilledWhenFlatAndNoOpenOrder() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));

        reconciler.reconcileStaleCloses();

        verify(fillTracking).onOrderStatus(eq(888), eq("Filled"), isNull(), isNull(), isNull(), any());
    }

    @Test
    void skipsWhenCloseStillWorking() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));
        when(ibkrOrderService.findOrder(any(), any())).thenReturn(
            BrokerOrderLookup.found(new BrokerOrderStatusView(888L, "ref", "U1", "Submitted")));

        reconciler.reconcileStaleCloses();

        verify(fillTracking, never()).onOrderStatus(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsWhenInstrumentNotFlat() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));
        when(portfolioService.getPortfolio(any())).thenReturn(snapshotWith(position("U1", "MNQ JUN26", BigDecimal.ONE)));

        reconciler.reconcileStaleCloses();

        verify(fillTracking, never()).onOrderStatus(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsWhenLookupUnavailable() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));
        when(ibkrOrderService.findOrder(any(), any())).thenReturn(BrokerOrderLookup.unavailable());

        reconciler.reconcileStaleCloses();

        verify(fillTracking, never()).onOrderStatus(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsWhenPortfolioNotConnected() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));
        when(portfolioService.getPortfolio(any())).thenReturn(disconnectedSnapshot());

        reconciler.reconcileStaleCloses();

        verify(fillTracking, never()).onOrderStatus(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void ignoresNonExitSubmittedRows() {
        TradeExecutionRecord active = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        active.setStatus(ExecutionStatus.ACTIVE);
        when(repo.findAllActive()).thenReturn(List.of(active));

        reconciler.reconcileStaleCloses();

        verify(fillTracking, never()).onOrderStatus(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsTooFreshRows() {
        StaleCloseReconciler graced = newReconciler(true, 120);
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", Instant.now());
        when(repo.findAllActive()).thenReturn(List.of(row));

        graced.reconcileStaleCloses();

        verify(fillTracking, never()).onOrderStatus(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsWhenNoCloseOrderId() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        row.setIbkrOrderId(null);
        when(repo.findAllActive()).thenReturn(List.of(row));

        reconciler.reconcileStaleCloses();

        verify(fillTracking, never()).onOrderStatus(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void noOpWhenDisabled() {
        StaleCloseReconciler off = newReconciler(false, 0);
        off.reconcileStaleCloses();
        verify(repo, never()).findAllActive();
    }

    @Test
    void noOpWhenIbkrDisabled() {
        when(ibkrProperties.isEnabled()).thenReturn(false);
        reconciler.reconcileStaleCloses();
        verify(repo, never()).findAllActive();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Instant agedSubmit() {
        return Instant.now().minusSeconds(600);
    }

    private static TradeExecutionRecord exitRow(Integer orderId, String instrument, String account, Instant submittedAt) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(7L);
        r.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        r.setIbkrOrderId(orderId);
        r.setInstrument(instrument);
        r.setExecutionKey("quant-sim:" + instrument + ":SHORT:1:OPEN");
        r.setBrokerAccountId(account);
        r.setExitSubmittedAt(submittedAt);
        return r;
    }

    private static IbkrPortfolioSnapshot flatSnapshot() {
        return snapshotWith();
    }

    private static IbkrPortfolioSnapshot snapshotWith(IbkrPositionView... positions) {
        return new IbkrPortfolioSnapshot(true, "U1", List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            "USD", List.of(positions), null);
    }

    private static IbkrPortfolioSnapshot disconnectedSnapshot() {
        return new IbkrPortfolioSnapshot(false, null, List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            "USD", null, null);
    }

    private static IbkrPositionView position(String account, String desc, BigDecimal qty) {
        return new IbkrPositionView(account, 1L, desc, "FUT", qty, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD");
    }
}

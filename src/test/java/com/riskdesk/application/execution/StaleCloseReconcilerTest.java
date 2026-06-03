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
import com.riskdesk.domain.notification.event.ExecutionReconciledEvent;
import com.riskdesk.domain.notification.port.NotificationPort;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the broker-truth close reconciler: a stuck EXIT_SUBMITTED row whose close FILLED (IBKR flat,
 * nothing working) is flipped CLOSED directly; everything uncertain is left untouched. ACTIVE phantoms
 * are closed too, but debounced.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaleCloseReconcilerTest {

    @Mock IbkrOrderService ibkrOrderService;
    @Mock TradeExecutionRepositoryPort repo;
    @Mock IbkrPortfolioService portfolioService;
    @Mock IbkrProperties ibkrProperties;
    @Mock NotificationPort notificationPort;

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
        return newReconciler(enabled, true, true, graceSeconds, 0);
    }

    private StaleCloseReconciler newReconciler(boolean enabled, boolean reconcileActive,
                                               boolean reconcileEntries, long graceSeconds, long confirmSeconds) {
        return new StaleCloseReconciler(ibkrOrderService, repo, portfolioService,
            () -> true, ibkrProperties, notificationPort, enabled, reconcileActive, reconcileEntries,
            graceSeconds, confirmSeconds);
    }

    @Test
    void closesStuckExitDirectlyWhenFlat() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));

        reconciler.reconcileStaleCloses();

        verify(repo).save(row);
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.CLOSED);
    }

    @Test
    void closesStuckExitEvenWithoutOrderId() {
        // orderId-collision-proof: the flip is on the held row, not a findByIbkrOrderId lookup.
        TradeExecutionRecord row = exitRow(null, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));

        reconciler.reconcileStaleCloses();

        verify(repo).save(row);
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.CLOSED);
    }

    @Test
    void skipsWhenCloseStillWorking() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));
        when(ibkrOrderService.findOrder(any(), any())).thenReturn(
            BrokerOrderLookup.found(new BrokerOrderStatusView(888L, "ref", "U1", "Submitted")));

        reconciler.reconcileStaleCloses();

        verify(repo, never()).save(any());
    }

    @Test
    void skipsWhenInstrumentNotFlat() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));
        when(portfolioService.getPortfolio(any())).thenReturn(snapshotWith(position("U1", "MNQ JUN26", BigDecimal.ONE)));

        reconciler.reconcileStaleCloses();

        verify(repo, never()).save(any());
    }

    @Test
    void skipsWhenLookupUnavailable() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));
        when(ibkrOrderService.findOrder(any(), any())).thenReturn(BrokerOrderLookup.unavailable());

        reconciler.reconcileStaleCloses();

        verify(repo, never()).save(any());
    }

    @Test
    void skipsWhenPortfolioNotConnected() {
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));
        when(portfolioService.getPortfolio(any())).thenReturn(disconnectedSnapshot());

        reconciler.reconcileStaleCloses();

        verify(repo, never()).save(any());
    }

    // ── ACTIVE phantom (debounced) ───────────────────────────────────────────

    @Test
    void closesActivePhantomWhenFlatAndConfirmed() {
        // confirm window 0 → acts on the first confirmed-flat sweep.
        StaleCloseReconciler r = newReconciler(true, true, true, 0, 0);
        TradeExecutionRecord active = activeRow("MNQ", "quant-sim-default");
        when(repo.findAllActive()).thenReturn(List.of(active));

        r.reconcileStaleCloses();

        verify(repo).save(active);
        assertThat(active.getStatus()).isEqualTo(ExecutionStatus.CLOSED);
    }

    @Test
    void closesVirtualExitTriggeredPhantomWhenFlat() {
        // The app decided to exit but the close never filled; IBKR is flat → the position is already gone.
        // Previously this status was ignored by the loop, leaving the row stuck non-terminal forever.
        StaleCloseReconciler r = newReconciler(true, true, true, 0, 0);
        TradeExecutionRecord row = openPositionRow("MNQ", "quant-sim-default", ExecutionStatus.VIRTUAL_EXIT_TRIGGERED);
        when(repo.findAllActive()).thenReturn(List.of(row));

        r.reconcileStaleCloses();

        verify(repo).save(row);
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.CLOSED);
    }

    @Test
    void closesPartiallyFilledPhantomWhenFlat() {
        // A partial fill is a real (smaller) position; IBKR flat → it was closed → phantom. Also previously ignored.
        StaleCloseReconciler r = newReconciler(true, true, true, 0, 0);
        TradeExecutionRecord row = openPositionRow("MNQ", "quant-sim-default", ExecutionStatus.ENTRY_PARTIALLY_FILLED);
        when(repo.findAllActive()).thenReturn(List.of(row));

        r.reconcileStaleCloses();

        verify(repo).save(row);
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.CLOSED);
    }

    @Test
    void debouncesActivePhantomUntilConfirmWindowElapses() {
        // confirm window 120s → the first confirmed-flat sweep only records, never closes.
        StaleCloseReconciler r = newReconciler(true, true, true, 0, 120);
        TradeExecutionRecord active = activeRow("MNQ", "quant-sim-default");
        when(repo.findAllActive()).thenReturn(List.of(active));

        r.reconcileStaleCloses();

        verify(repo, never()).save(any());
        assertThat(active.getStatus()).isEqualTo(ExecutionStatus.ACTIVE);
    }

    @Test
    void doesNotCloseActivePhantomWhenNotFlat() {
        StaleCloseReconciler r = newReconciler(true, true, true, 0, 0);
        TradeExecutionRecord active = activeRow("MNQ", "quant-sim-default");
        when(repo.findAllActive()).thenReturn(List.of(active));
        when(portfolioService.getPortfolio(any())).thenReturn(snapshotWith(position("U1", "MNQ JUN26", BigDecimal.ONE)));

        r.reconcileStaleCloses();

        verify(repo, never()).save(any());
    }

    @Test
    void ignoresActiveRowsWhenFeatureOff() {
        StaleCloseReconciler r = newReconciler(true, false, true, 0, 0);
        TradeExecutionRecord active = activeRow("MNQ", "quant-sim-default");
        when(repo.findAllActive()).thenReturn(List.of(active));

        r.reconcileStaleCloses();

        verify(repo, never()).save(any());
    }

    // ── zombie ENTRY_SUBMITTED (debounced) ───────────────────────────────────

    @Test
    void cancelsZombieEntryWhenFlatAndOrderGone() {
        // default: findOrder NOT_FOUND, IBKR flat, confirm window 0.
        TradeExecutionRecord entry = entryRow("MNQ", "wtx-default");
        when(repo.findAllActive()).thenReturn(List.of(entry));

        reconciler.reconcileStaleCloses();

        verify(repo).save(entry);
        assertThat(entry.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void cancelsZombieEntryWhenPendingSubmit() {
        // PendingSubmit was never transmitted to the exchange → a zombie on a flat account.
        TradeExecutionRecord entry = entryRow("MNQ", "wtx-default");
        when(repo.findAllActive()).thenReturn(List.of(entry));
        when(ibkrOrderService.findOrder(any(), any())).thenReturn(
            BrokerOrderLookup.found(new BrokerOrderStatusView(7L, "ref", "U1", "PendingSubmit")));

        reconciler.reconcileStaleCloses();

        verify(repo).save(entry);
        assertThat(entry.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void cancelsZombiePendingEntrySubmissionWhenFlat() {
        // PENDING_ENTRY_SUBMISSION = the submit threw or never got its orderStatus ack. With IBKR flat and
        // the order gone, it is a zombie that froze the strategy (the "ENTRY became weird / NONE" symptom).
        // Previously the loop ignored this status entirely, so the row stuck and blocked the instrument.
        TradeExecutionRecord entry = entryRow("MNQ", "wtx-default", ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        when(repo.findAllActive()).thenReturn(List.of(entry));

        reconciler.reconcileStaleCloses();

        verify(repo).save(entry);
        assertThat(entry.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void leavesGenuinelyWorkingEntry() {
        // A real resting limit (Submitted) may still fill → never cancel it.
        TradeExecutionRecord entry = entryRow("MNQ", "wtx-default");
        when(repo.findAllActive()).thenReturn(List.of(entry));
        when(ibkrOrderService.findOrder(any(), any())).thenReturn(
            BrokerOrderLookup.found(new BrokerOrderStatusView(7L, "ref", "U1", "Submitted")));

        reconciler.reconcileStaleCloses();

        verify(repo, never()).save(any());
    }

    @Test
    void debouncesZombieEntryUntilConfirmWindowElapses() {
        StaleCloseReconciler r = newReconciler(true, true, true, 0, 120);
        TradeExecutionRecord entry = entryRow("MNQ", "wtx-default");
        when(repo.findAllActive()).thenReturn(List.of(entry));

        r.reconcileStaleCloses();

        verify(repo, never()).save(any());
        assertThat(entry.getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
    }

    @Test
    void ignoresEntriesWhenFeatureOff() {
        StaleCloseReconciler r = newReconciler(true, true, false, 0, 0);
        TradeExecutionRecord entry = entryRow("MNQ", "wtx-default");
        when(repo.findAllActive()).thenReturn(List.of(entry));

        r.reconcileStaleCloses();

        verify(repo, never()).save(any());
    }

    @Test
    void skipsTooFreshRows() {
        StaleCloseReconciler graced = newReconciler(true, 120);
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", Instant.now());
        when(repo.findAllActive()).thenReturn(List.of(row));

        graced.reconcileStaleCloses();

        verify(repo, never()).save(any());
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

    // ── divergence alarm (R7) ────────────────────────────────────────────────

    @Test
    void firesDivergenceAlarmWhenPhantomCorrected() {
        // Every correction must surface the divergence (Telegram) so it is never discovered by losing money.
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));

        reconciler.reconcileStaleCloses();

        ArgumentCaptor<ExecutionReconciledEvent> captor = ArgumentCaptor.forClass(ExecutionReconciledEvent.class);
        verify(notificationPort).sendExecutionReconciled(captor.capture());
        ExecutionReconciledEvent evt = captor.getValue();
        assertThat(evt.instrument()).isEqualTo("MNQ");
        assertThat(evt.fromStatus()).isEqualTo("EXIT_SUBMITTED");
        assertThat(evt.toStatus()).isEqualTo("CLOSED");
        assertThat(evt.triggerSource()).isEqualTo("QUANT_SIM_AUTO");
    }

    @Test
    void doesNotAlarmWhenNothingReconciled() {
        // Order still working → no correction → no alarm (no false-positive divergence notifications).
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));
        when(ibkrOrderService.findOrder(any(), any())).thenReturn(
            BrokerOrderLookup.found(new BrokerOrderStatusView(888L, "ref", "U1", "Submitted")));

        reconciler.reconcileStaleCloses();

        verify(notificationPort, never()).sendExecutionReconciled(any());
    }

    @Test
    void alarmFailureNeverAbortsTheCorrection() {
        // The state correction has already persisted — a Telegram outage must not roll it back or throw.
        TradeExecutionRecord row = exitRow(888, "MNQ", "quant-sim-default", agedSubmit());
        when(repo.findAllActive()).thenReturn(List.of(row));
        doThrow(new RuntimeException("telegram down")).when(notificationPort).sendExecutionReconciled(any());

        reconciler.reconcileStaleCloses();

        verify(repo).save(row);
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.CLOSED);
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
        r.setTriggerSource(ExecutionTriggerSource.QUANT_SIM_AUTO);
        r.setExecutionKey("quant-sim:" + instrument + ":SHORT:1:OPEN");
        r.setBrokerAccountId(account);
        r.setExitSubmittedAt(submittedAt);
        return r;
    }

    private static TradeExecutionRecord activeRow(String instrument, String account) {
        return openPositionRow(instrument, account, ExecutionStatus.ACTIVE);
    }

    private static TradeExecutionRecord openPositionRow(String instrument, String account, ExecutionStatus status) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(9L);
        r.setStatus(status);
        r.setIbkrOrderId(555);
        r.setInstrument(instrument);
        r.setTriggerSource(ExecutionTriggerSource.QUANT_SIM_AUTO);
        r.setExecutionKey("quant-sim:" + instrument + ":SHORT:1:OPEN");
        r.setBrokerAccountId(account);
        r.setUpdatedAt(Instant.now().minusSeconds(600)); // aged past grace
        return r;
    }

    private static TradeExecutionRecord entryRow(String instrument, String account) {
        return entryRow(instrument, account, ExecutionStatus.ENTRY_SUBMITTED);
    }

    private static TradeExecutionRecord entryRow(String instrument, String account, ExecutionStatus status) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(11L);
        r.setStatus(status);
        r.setIbkrOrderId(777);
        r.setInstrument(instrument);
        r.setTriggerSource(ExecutionTriggerSource.WTX_AUTO);
        r.setExecutionKey("wtx:" + instrument + ":5m:1:SHORT");
        r.setBrokerAccountId(account);
        r.setEntrySubmittedAt(Instant.now().minusSeconds(600)); // aged past grace
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

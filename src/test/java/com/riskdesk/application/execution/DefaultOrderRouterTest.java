package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.IntentKind;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort.CreateOutcome;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOrderRouterTest {

    @Mock private IbkrOrderService ibkrOrderService;
    @Mock private TradeExecutionRepositoryPort repo;
    @Mock private ExecutionReconciler reconciler;

    private IbkrProperties props;
    private DefaultOrderRouter router;

    @BeforeEach
    void setUp() {
        props = new IbkrProperties();
        props.setEnabled(true);
        router = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true, reconciler);
        // createIfAbsentTracked: we created the row (created=true) — it assigns the PK; save echoes it.
        lenient().when(repo.createIfAbsentTracked(any())).thenAnswer(inv -> {
            TradeExecutionRecord r = inv.getArgument(0);
            if (r != null) r.setId(1L); // null-safe: re-stubbing in a test re-invokes this with a null arg
            return new CreateOutcome(r, true);
        });
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Default reconcile: position unavailable, plan = pass-through (Open for OPEN, Reverse for REVERSE).
        lenient().when(reconciler.readPositionState(any(), any())).thenReturn(BrokerPositionState.unavailable());
        lenient().when(reconciler.reconcile(any(), any())).thenAnswer(inv -> {
            TradeIntent i = inv.getArgument(0);
            if (i == null) return null; // null-safe: re-stubbing in a test re-invokes this with a null arg
            return i.kind() == IntentKind.REVERSE ? new ReconcilePlan.Reverse(i.side()) : new ReconcilePlan.Open(i.side());
        });
    }

    private TradeIntent openLong() {
        return TradeIntent.open("wtx:MNQ:5m:1:OPEN_LONG", ExecutionTriggerSource.WTX_AUTO,
            Instrument.MNQ, "5m", Side.LONG, 2, new BigDecimal("18000.30"), "DU1");
    }

    private BrokerEntryOrderSubmission submission(Long brokerOrderId, String status) {
        return new BrokerEntryOrderSubmission(brokerOrderId, status, "wtx:MNQ:5m:1:OPEN_LONG", Instant.now());
    }

    @Test
    void routesOpen_persistsBothIds_roundsTick() {
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(12345L, "Submitted"));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        assertThat(r.executionId()).isEqualTo(1L);
        assertThat(r.brokerOrderId()).isEqualTo(12345L);

        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        TradeExecutionRecord saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
        assertThat(saved.getEntryOrderId()).isEqualTo(12345L);
        assertThat(saved.getIbkrOrderId()).isEqualTo(12345);                 // Integer cast for fill tracker
        assertThat(saved.getAction()).isEqualTo("LONG");
        assertThat(saved.getNormalizedEntryPrice()).isEqualByComparingTo("18000.25"); // rounded to 0.25 tick
    }

    @Test
    void pendingSubmitMapsToAckPending() {
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(777L, "PendingSubmit"));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ACK_PENDING);
        assertThat(r.brokerOrderId()).isEqualTo(777L);
    }

    @Test
    void skipsDuplicateWhenRowAlreadyExisted_noDoubleSubmit() {
        // Race resolved by the DB unique constraint: createIfAbsentTracked reports created=false, so this
        // (loser) caller must NOT submit a second order.
        TradeExecutionRecord existing = new TradeExecutionRecord();
        existing.setId(1L);
        existing.setEntryOrderId(555L);
        when(repo.createIfAbsentTracked(any())).thenReturn(new CreateOutcome(existing, false));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
        assertThat(r.executionId()).isEqualTo(1L);
        assertThat(r.brokerOrderId()).isEqualTo(555L); // the winner's broker order id
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void mapsInsufficientMargin_terminalFailed() {
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN, 201, "margin", "insufficient", null));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_INSUFFICIENT_MARGIN);
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.FAILED); // terminal: broker rejected
    }

    @Test
    void readOnlyRejectMapsToFailedReadOnly() {
        // The kill-switch / TWS Read-Only surface as BROKER_REJECT carrying a read-only message.
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.BROKER_REJECT, null, "native-read-only kill-switch is ON",
            "Order NOT sent: riskdesk.ibkr.native-read-only is ON (software kill-switch).", null));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_READ_ONLY);
    }

    @Test
    void timeoutWithBrokerIdIsAckPending() {
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.TIMEOUT, null, "timeout", "no ack", 999L));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ACK_PENDING);
        assertThat(r.brokerOrderId()).isEqualTo(999L);
    }

    @Test
    void timeoutWithoutBrokerIdIsFailedTimeout_nonTerminal() {
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.TIMEOUT, null, "timeout", "no ack", null));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_TIMEOUT);
        assertThat(r.brokerOrderId()).isNull();
        // Broker state UNKNOWN on a no-id timeout — row MUST stay non-terminal for the reconciler.
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
    }

    // ---- CLOSE -----------------------------------------------------------------------------

    private TradeIntent closeLong() {
        return TradeIntent.close("wtx:MNQ:5m:2:CLOSE_LONG", ExecutionTriggerSource.WTX_AUTO,
            Instrument.MNQ, "5m", Side.LONG, 1, new BigDecimal("18010.00"), "DU1");
    }

    private TradeExecutionRecord activeRow(ExecutionStatus status, int qty, Long entryOrderId) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(7L);
        r.setExecutionKey("wtx:MNQ:5m:1:OPEN_LONG");
        r.setInstrument("MNQ");
        r.setTimeframe("5m");
        r.setBrokerAccountId("DU1");
        r.setQuantity(qty);
        r.setStatus(status);
        r.setEntryOrderId(entryOrderId);
        return r;
    }

    private void stubActive(TradeExecutionRecord row) {
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any())).thenReturn(Optional.of(row));
    }

    private void stubFlat(boolean flat) {
        when(reconciler.readPositionState(any(), any()))
            .thenReturn(new BrokerPositionState(flat ? BigDecimal.ZERO : new BigDecimal("1"), flat));
    }

    @Test
    void close_noActiveRow_skipsNoOpenRow() {
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any())).thenReturn(Optional.empty());

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void close_flatAndActiveRow_voidsPhantom() {
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubFlat(true);

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.CANCELLED); // phantom voided, DB-only
    }

    @Test
    void close_flatAndInFlight_skipsEntryInFlight() {
        stubActive(activeRow(ExecutionStatus.ENTRY_SUBMITTED, 1, 100L));
        stubFlat(true);

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void close_submits_exitSubmitted_oppositeActionAndRowQty() {
        stubActive(activeRow(ExecutionStatus.ACTIVE, 2, 100L));
        stubFlat(false);
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(888L, "Submitted", "k", Instant.now()));

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        assertThat(r.brokerOrderId()).isEqualTo(888L);
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().action()).isEqualTo("SHORT"); // closing a long = SELL
        assertThat(req.getValue().quantity()).isEqualTo(2);      // the row's open qty, not the intent qty
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.EXIT_SUBMITTED);
    }

    @Test
    void close_rejectKeepsRowNonTerminal() {
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubFlat(false);
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.BROKER_REJECT, null, "reject", "rejected", null));

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_BROKER_REJECT);
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.ACTIVE); // NOT terminal: position still open
    }

    @Test
    void close_marginOnCloseMapsToBrokerReject() {
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubFlat(false);
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN, 201, "margin", "insufficient", null));

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_BROKER_REJECT); // a reducing order needs no margin
    }

    // ---- FLATTEN ---------------------------------------------------------------------------

    private TradeIntent flatten() {
        return TradeIntent.flatten("wtx:MNQ:5m:3:FLATTEN", ExecutionTriggerSource.WTX_AUTO,
            Instrument.MNQ, "5m", 1, new BigDecimal("18010.00"), "DU1");
    }

    @Test
    void flatten_closesHeldLong_withSell() {
        TradeExecutionRecord row = activeRow(ExecutionStatus.ACTIVE, 2, 100L);
        row.setAction("LONG"); // held long
        stubActive(row);
        stubFlat(false);
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(900L, "Submitted", "k", Instant.now()));

        RoutingResult r = router.route(flatten());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().action()).isEqualTo("SHORT"); // flatten a long = SELL
    }

    @Test
    void flatten_closesHeldShort_withBuy() {
        TradeExecutionRecord row = activeRow(ExecutionStatus.ACTIVE, 1, 100L);
        row.setAction("SHORT"); // held short
        stubActive(row);
        stubFlat(false);
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(901L, "Submitted", "k", Instant.now()));

        RoutingResult r = router.route(flatten());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().action()).isEqualTo("LONG"); // flatten a short = BUY
    }

    @Test
    void flatten_noRow_skipsNoOpenRow() {
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any())).thenReturn(Optional.empty());

        RoutingResult r = router.route(flatten());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    // ---- REVERSE ---------------------------------------------------------------------------

    private TradeIntent reverseToShort() {
        return TradeIntent.reverse("wtx:MNQ:5m:2:REVERSE_SHORT", ExecutionTriggerSource.WTX_AUTO,
            Instrument.MNQ, "5m", Side.SHORT, 2, new BigDecimal("18000.00"), "DU1");
    }

    private TradeIntent openShort() {
        return TradeIntent.open("wtx:MNQ:5m:2:OPEN_SHORT", ExecutionTriggerSource.WTX_AUTO,
            Instrument.MNQ, "5m", Side.SHORT, 2, new BigDecimal("18000.00"), "DU1");
    }

    private TradeExecutionRecord priorLong() {
        TradeExecutionRecord prior = activeRow(ExecutionStatus.ACTIVE, 2, 100L);
        prior.setAction("LONG"); // a held long, to be flattened by the reverse
        return prior;
    }

    @Test
    void reverse_closeThenOpen_routed() {
        stubActive(priorLong());
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Submitted")); // both legs ok

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        // Two legs: close (on the prior row) then open (a new row).
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService, times(2)).submitEntryOrder(req.capture());
        assertThat(req.getAllValues().get(0).executionKey()).isEqualTo("wtx:MNQ:5m:1:OPEN_LONG");   // close on prior row
        assertThat(req.getAllValues().get(1).executionKey()).isEqualTo("wtx:MNQ:5m:2:REVERSE_SHORT"); // open new row
    }

    @Test
    void reverse_closeAckPending_abortsOpen() {
        stubActive(priorLong());
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "PendingSubmit")); // close ack-pends

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ACK_PENDING);
        verify(ibkrOrderService, times(1)).submitEntryOrder(any()); // only the close leg; open NOT attempted
    }

    @Test
    void reverse_closeRejected_abortsOpen() {
        stubActive(priorLong());
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.BROKER_REJECT, null, "reject", "rejected", null));

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_BROKER_REJECT);
        verify(ibkrOrderService, times(1)).submitEntryOrder(any()); // close rejected; open NOT attempted
    }

    @Test
    void reverse_openRejectedAfterClose_routedFlattenOnly() {
        stubActive(priorLong());
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(submission(900L, "Submitted"))                       // close OK
            .thenThrow(new IbkrOrderRejectionException(                       // open rejected
                IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN, 201, "margin", "insufficient", null));

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED_FLATTEN_ONLY); // broker FLAT, protected
        verify(ibkrOrderService, times(2)).submitEntryOrder(any());
    }

    @Test
    void open_upgradedToReverse_closesOppositeThenOpens() {
        // An OPEN_SHORT that reconcile upgrades to a REVERSE because IBKR holds the opposite (long).
        when(reconciler.readPositionState(any(), any())).thenReturn(new BrokerPositionState(new BigDecimal("2"), false));
        when(reconciler.reconcile(any(), any())).thenReturn(new ReconcilePlan.Reverse(Side.SHORT));
        stubActive(priorLong());
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Submitted"));

        RoutingResult r = router.route(openShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        verify(ibkrOrderService, times(2)).submitEntryOrder(any()); // close opposite + open short
    }

    @Test
    void entry_reconciledSkip_returnsOutcome() {
        // reconcile says the broker is already on the wanted side → skip, no submit.
        when(reconciler.reconcile(any(), any())).thenReturn(
            new ReconcilePlan.Skip(RoutingOutcome.SKIPPED_DUPLICATE, "IBKR already long"));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void skipsWhenIbkrDisabled() {
        props.setEnabled(false);

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_IBKR_DISABLED);
        verifyNoInteractions(ibkrOrderService);
    }

    @Test
    void skipsWhenNotReady() {
        DefaultOrderRouter gated = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> false, reconciler);

        RoutingResult r = gated.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_RECONCILING);
        verifyNoInteractions(ibkrOrderService);
    }
}

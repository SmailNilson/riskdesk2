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
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    /** Default test live-price port: no price → exit legs fall back to the passive intent limit
     *  (legacy behaviour), so pre-existing assertions are unaffected. */
    private static final LivePricePort NO_PRICE = instr -> Optional.empty();

    @BeforeEach
    void setUp() {
        props = new IbkrProperties();
        props.setEnabled(true);
        router = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true, reconciler,
            Instrument::getTickSize, Optional.empty(), null, NO_PRICE, 10, true, true);
        // createIfAbsentTracked: we created the row (created=true) — it assigns the PK; save echoes it.
        lenient().when(repo.createIfAbsentTracked(any())).thenAnswer(inv -> {
            TradeExecutionRecord r = inv.getArgument(0);
            if (r != null) r.setId(1L); // null-safe: re-stubbing in a test re-invokes this with a null arg
            return new CreateOutcome(r, true);
        });
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // createIfAbsent (used to synthesise a phantom close row in a reverse against drift).
        lenient().when(repo.createIfAbsent(any())).thenAnswer(inv -> {
            TradeExecutionRecord r = inv.getArgument(0);
            if (r != null) r.setId(9L);
            return r;
        });
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
    void routesOpen_roundsToProviderMinTick_notHardcodedInstrumentTick() {
        // The router rounds to the provider's (broker ContractDetails.minTick) value, not the hardcoded tick.
        DefaultOrderRouter r = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true, reconciler,
            instr -> new BigDecimal("0.50"), Optional.empty(), null, NO_PRICE, 10, true, true); // broker minTick 0.50
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(12345L, "Submitted"));

        r.route(openLong()); // entry 18000.30

        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getNormalizedEntryPrice()).isEqualByComparingTo("18000.50"); // 18000.30 → nearest 0.50
    }

    @Test
    void pendingSubmitMapsToAckPending() {
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(777L, "PendingSubmit"));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ACK_PENDING);
        assertThat(r.brokerOrderId()).isEqualTo(777L);
    }

    @Test
    void open_immediatelyFilled_marksActive() {
        // A marketable entry IBKR fills immediately (first accepted status "Filled") must be ACTIVE, not
        // ENTRY_SUBMITTED — else a later CLOSE/FLATTEN skips the LIVE position as entry-in-flight.
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(12345L, "Filled"));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.ACTIVE);
        assertThat(cap.getValue().getEntryFilledAt()).isNotNull();
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

    @Test
    void entry_flatBroker_staleExitRow_voidsItAndOpens() {
        // Broker confirmed flat but a stale EXIT_SUBMITTED row lingers (its close filled while we missed the
        // callback / restarted). It must be VOIDED — not treated as entry-in-flight — so the open proceeds
        // instead of being blocked forever.
        stubActive(activeRow(ExecutionStatus.EXIT_SUBMITTED, 1, 100L));
        stubFlat(true);
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(12345L, "Submitted"));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(rec -> rec.getStatus() == ExecutionStatus.CANCELLED); // stale exit voided
        verify(ibkrOrderService).submitEntryOrder(any());
    }

    @Test
    void entry_flatBroker_inFlightEntryRow_skipsToAvoidDoubleFill() {
        // Broker flat but our entry order is genuinely resting unfilled (ENTRY_SUBMITTED) — opening another
        // risks a double fill once both rest. This is the ONLY status that should skip on this path.
        stubActive(activeRow(ExecutionStatus.ENTRY_SUBMITTED, 1, 100L));
        stubFlat(true);

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
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
        r.setAction("LONG"); // held side (NOT NULL in the entity); flatten/reverse tests override as needed
        r.setQuantity(qty);
        r.setStatus(status);
        r.setEntryOrderId(entryOrderId);
        return r;
    }

    private void stubActive(TradeExecutionRecord row) {
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccount(any(), any(), any(), any())).thenReturn(Optional.of(row));
    }

    private void stubFlat(boolean flat) {
        when(reconciler.readPositionState(any(), any()))
            .thenReturn(new BrokerPositionState(flat ? BigDecimal.ZERO : new BigDecimal("1"), flat));
    }

    /** Stub broker truth to a signed net (available, not flat) — e.g. "2" = long 2, "-2" = short 2. */
    private void stubBroker(String net) {
        when(reconciler.readPositionState(any(), any()))
            .thenReturn(new BrokerPositionState(new BigDecimal(net), false));
    }

    @Test
    void close_noActiveRow_skipsNoOpenRow() {
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccount(any(), any(), any(), any())).thenReturn(Optional.empty());

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
        when(reconciler.readPositionState(any(), any())) // broker holds long 2 (matches the row)
            .thenReturn(new BrokerPositionState(new BigDecimal("2"), false));
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(888L, "Submitted", "k", Instant.now()));

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        assertThat(r.brokerOrderId()).isEqualTo(888L);
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().action()).isEqualTo("SHORT"); // closing a long = SELL
        assertThat(req.getValue().quantity()).isEqualTo(2);      // the row's open qty, not the intent qty
        // Distinct, retry-safe exit ref: "<entry key>:exit:<close intent key>" (per-signal discriminator).
        assertThat(req.getValue().executionKey()).startsWith("wtx:MNQ:5m:1:OPEN_LONG:exit:");
        assertThat(req.getValue().executionKey()).endsWith(":wtx:MNQ:5m:2:CLOSE_LONG");
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.EXIT_SUBMITTED);
    }

    @Test
    void close_capsExitQtyToBrokerNet_noOverCloseFlip() {
        // Row records qty 2 but IBKR only holds long 1 (manual partial close). Closing the row qty (SELL 2)
        // would flatten the long 1 and OPEN a short 1 — cap to the live broker qty → SELL 1.
        stubActive(activeRow(ExecutionStatus.ACTIVE, 2, 100L)); // row qty 2
        when(reconciler.readPositionState(any(), any()))
            .thenReturn(new BrokerPositionState(new BigDecimal("1"), false)); // broker long 1
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(888L, "Submitted", "k", Instant.now()));

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().quantity()).isEqualTo(1);     // capped to broker net, not row qty 2
        assertThat(req.getValue().action()).isEqualTo("SHORT"); // SELL to reduce the long
    }

    @Test
    void close_immediatelyFilled_marksClosed() {
        // A close IBKR fills immediately ("Filled" as first accepted status) must be CLOSED, not left
        // EXIT_SUBMITTED — else a later CLOSE/FLATTEN hits the duplicate-exit guard over an already-flat row.
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubBroker("1"); // broker long 1
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(888L, "Filled", "k", Instant.now()));

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.CLOSED);
        // A synchronous close fill must stamp closedAt here — the later orderStatus(Filled) callback skips
        // it (row no longer EXIT_SUBMITTED), so otherwise the closed row has a null close timestamp.
        assertThat(cap.getValue().getClosedAt()).isNotNull();
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

    @Test
    void close_rejectWithBrokerOrderId_staysActiveRetryable_notExitSubmitted() {
        // IBKR allocated an order id then REJECTED the close. The position is still open — the row must stay
        // ACTIVE (retryable on the next signal), NOT EXIT_SUBMITTED (which the duplicate guard would skip).
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubFlat(false);
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.BROKER_REJECT, null, "reject", "rejected", 555L)); // id, then rejected

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_BROKER_REJECT);
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.ACTIVE); // retryable, NOT EXIT_SUBMITTED
    }

    @Test
    void close_timeoutWithBrokerOrderId_marksExitSubmitted() {
        // A close that timed out but got an order id is genuinely live (ACK_PENDING) — mark EXIT_SUBMITTED
        // so the fill tracker resolves it on the Filled/Cancelled callback.
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubFlat(false);
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.TIMEOUT, null, "timeout", "no ack", 777L));

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ACK_PENDING);
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.EXIT_SUBMITTED);
        assertThat(cap.getValue().getIbkrOrderId()).isEqualTo(777); // close id recorded
    }

    @Test
    void close_scopesActiveRowLookupToIntentAccount() {
        // P1 guard: the active-row lookup MUST be account-scoped. A CLOSE for one account must never
        // locate (and then flatten) another account's row — submitCloseLeg closes on the row's own
        // brokerAccountId, so a wrong-account row would flatten the wrong position.
        ArgumentCaptor<String> account = ArgumentCaptor.forClass(String.class);
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccount(any(), any(), any(), account.capture()))
            .thenReturn(Optional.empty());

        RoutingResult r = router.route(closeLong()); // intent account = "DU1"

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        assertThat(account.getValue()).isEqualTo("DU1"); // lookup scoped to the intent's resolved account
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void close_inFlightEntry_notFlat_skipsEntryInFlight() {
        // Entry still resting (ENTRY_SUBMITTED) and broker NOT confirmed flat (real position or broker
        // truth unavailable) — no confirmed full position to reduce. A close would be naked/over-sized.
        stubActive(activeRow(ExecutionStatus.ENTRY_SUBMITTED, 1, 100L));
        stubFlat(false);

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void close_brokerTruthUnavailable_skipsNoBlindOrder() {
        // Broker position truth unreadable — can't confirm a live position. This is the only path by which
        // the REVERSE fill-ordering race could surface a naked exit (row ACTIVE while broker is flat). A
        // reducing order must NOT fire blind; skip until truth is back.
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        when(reconciler.readPositionState(any(), any())).thenReturn(BrokerPositionState.unavailable());

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void close_intentSideMismatchesBrokerHeld_skipsNoExposureIncrease() {
        // CLOSE_LONG but IBKR actually holds a SHORT (broker truth is authoritative; the local row may be
        // stale). Deriving SELL would increase the short — there is no long to close, so skip.
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L)); // row even says LONG (default)
        when(reconciler.readPositionState(any(), any()))
            .thenReturn(new BrokerPositionState(new BigDecimal("-2"), false)); // broker SHORT

        RoutingResult r = router.route(closeLong()); // CLOSE_LONG vs broker SHORT

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
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
        when(reconciler.readPositionState(any(), any())) // broker holds SHORT (authoritative)
            .thenReturn(new BrokerPositionState(new BigDecimal("-1"), false));
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(901L, "Submitted", "k", Instant.now()));

        RoutingResult r = router.route(flatten());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().action()).isEqualTo("LONG"); // flatten a short = BUY
    }

    @Test
    void flatten_staleRowOppositeBrokerSide_reducesBrokerSideNoIncrease() {
        // Row says LONG but IBKR holds SHORT (drift / missed reverse). FLATTEN must reduce the broker's
        // ACTUAL side — BUY to cover the short — NOT SELL (which would increase it).
        TradeExecutionRecord row = activeRow(ExecutionStatus.ACTIVE, 2, 100L);
        row.setAction("LONG"); // stale local belief
        stubActive(row);
        when(reconciler.readPositionState(any(), any()))
            .thenReturn(new BrokerPositionState(new BigDecimal("-2"), false)); // broker SHORT
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(905L, "Submitted", "k", Instant.now()));

        RoutingResult r = router.route(flatten());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().action()).isEqualTo("LONG"); // BUY to reduce the short, not SELL
    }

    @Test
    void flatten_noRow_skipsNoOpenRow() {
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccount(any(), any(), any(), any())).thenReturn(Optional.empty());

        RoutingResult r = router.route(flatten());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    // ---- REVERSE ---------------------------------------------------------------------------

    private TradeIntent reverseToShort() {
        return TradeIntent.reverse("wtx:MNQ:5m:2:REVERSE_SHORT", ExecutionTriggerSource.WTX_AUTO,
            Instrument.MNQ, "5m", Side.SHORT, 2, new BigDecimal("18000.00"), "DU1");
    }

    private TradeIntent reverseToLong() {
        return TradeIntent.reverse("wtx:MNQ:5m:2:REVERSE_LONG", ExecutionTriggerSource.WTX_AUTO,
            Instrument.MNQ, "5m", Side.LONG, 2, new BigDecimal("17000.00"), "DU1");
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
        stubBroker("2"); // broker holds LONG 2 (the position the reverse flattens)
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Filled")); // close fills → open inline

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        // Two legs: close (on the prior row) then open (a new row).
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService, times(2)).submitEntryOrder(req.capture());
        assertThat(req.getAllValues().get(0).executionKey()).startsWith("wtx:MNQ:5m:1:OPEN_LONG:exit:"); // close: distinct, retry-safe exit ref
        assertThat(req.getAllValues().get(1).executionKey()).isEqualTo("wtx:MNQ:5m:2:REVERSE_SHORT");  // open new row
    }

    @Test
    void reverse_closeAckPending_abortsOpen() {
        stubActive(priorLong());
        stubBroker("2"); // broker holds LONG 2
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "PendingSubmit")); // close ack-pends

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ACK_PENDING);
        verify(ibkrOrderService, times(1)).submitEntryOrder(any()); // only the close leg; open NOT attempted
    }

    @Test
    void reverse_closeRejected_abortsOpen() {
        stubActive(priorLong());
        stubBroker("2"); // broker holds LONG 2
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.BROKER_REJECT, null, "reject", "rejected", null));

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_BROKER_REJECT);
        verify(ibkrOrderService, times(1)).submitEntryOrder(any()); // close rejected; open NOT attempted
    }

    @Test
    void reverse_openRejectedAfterClose_routedFlattenOnly() {
        stubActive(priorLong());
        stubBroker("2"); // broker holds LONG 2
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(submission(900L, "Filled"))                          // close fills inline → open inline
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
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Filled")); // close fills → open inline

        RoutingResult r = router.route(openShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        verify(ibkrOrderService, times(2)).submitEntryOrder(any()); // close opposite + open short
    }

    // ---- Marketable-limit EXIT pricing ---------------------------------------------------------
    // A reducing leg (REVERSE-close, CLOSE, FLATTEN) crosses the internal live price (LivePricePort —
    // the IBKR->Postgres->services path) by cross-ticks so it fills like a market order instead of
    // resting unfilled; the limit caps slippage. Entries stay passive. Same source as the Quant force-close.

    private DefaultOrderRouter pricedRouter(LivePricePort port, int crossTicks) {
        return pricedRouter(port, crossTicks, true);
    }

    private DefaultOrderRouter pricedRouter(LivePricePort port, int crossTicks, boolean reverseOpenMarketable) {
        return new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true, reconciler,
            Instrument::getTickSize, Optional.empty(), null, port, crossTicks, true, reverseOpenMarketable);
    }

    /** Fixed internal live price (fresh LIVE_PUSH provenance). */
    private static LivePricePort livePrice(String price) {
        return livePriceWith(price, "LIVE_PUSH", 0);
    }

    /** Internal live price with explicit provenance + age (seconds) for source/freshness gating tests. */
    private static LivePricePort livePriceWith(String price, String source, long ageSeconds) {
        return instr -> Optional.of(new LivePriceSnapshot(
            Double.parseDouble(price), Instant.now().minusSeconds(ageSeconds), source));
    }

    private TradeIntent closeShort() {
        return TradeIntent.close("wtx:MNQ:5m:2:CLOSE_SHORT", ExecutionTriggerSource.WTX_AUTO,
            Instrument.MNQ, "5m", Side.SHORT, 1, new BigDecimal("17990.00"), "DU1");
    }

    @Test
    void close_long_pricesMarketableCrossingDown() {
        // Reduce a long = SELL → cross the live price DOWN: price − cross (4 ticks · 0.25 = 1.00).
        DefaultOrderRouter r = pricedRouter(livePrice("18000.00"), 4);
        stubActive(activeRow(ExecutionStatus.ACTIVE, 2, 100L));
        stubBroker("2"); // broker long 2
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(888L, "Submitted", "k", Instant.now()));

        r.route(closeLong());

        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().action()).isEqualTo("SHORT");
        assertThat(req.getValue().limitPrice()).isEqualByComparingTo("17999.00"); // 18000.00 − 1.00, NOT intent 18010
    }

    @Test
    void close_short_pricesMarketableCrossingUp() {
        // Reduce a short = BUY → cross the live price UP: price + cross (4 · 0.25 = 1.00).
        DefaultOrderRouter r = pricedRouter(livePrice("18000.00"), 4);
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubBroker("-1"); // broker short 1
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(889L, "Submitted", "k", Instant.now()));

        r.route(closeShort());

        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().action()).isEqualTo("LONG");
        assertThat(req.getValue().limitPrice()).isEqualByComparingTo("18001.00"); // 18000.00 + 1.00
    }

    @Test
    void closeLeg_noLivePrice_fallsBackToPassiveIntentLimit() {
        // Default router (NO_PRICE) → no live price → exit rests at the passive intent limit (legacy).
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubBroker("1");
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(890L, "Submitted", "k", Instant.now()));

        router.route(closeLong());

        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().limitPrice()).isEqualByComparingTo("18010.00"); // intent limit, unchanged
    }

    @Test
    void reverse_bothLegsMarketable_whenReverseOpenEnabled() {
        // Reverse-open marketable ON (default): BOTH legs cross the live price so the flip completes.
        // Reverse long→short: close = SELL (reduce long), open = SELL (open short) → both price − cross
        // = 18005.00 − 1.00 = 18004.00.
        DefaultOrderRouter r = pricedRouter(livePrice("18005.00"), 4); // reverse-open enabled (default)
        stubActive(priorLong());
        stubBroker("2");
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Filled")); // close fills → open inline

        r.route(reverseToShort());

        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService, times(2)).submitEntryOrder(req.capture());
        assertThat(req.getAllValues().get(0).limitPrice()).isEqualByComparingTo("18004.00"); // close, crossed
        assertThat(req.getAllValues().get(1).action()).isEqualTo("SHORT");
        assertThat(req.getAllValues().get(1).limitPrice()).isEqualByComparingTo("18004.00"); // open, crossed → flip completes
        // The ACTIVE reverse-open row is tracked at the crossed price actually submitted (NOT the passive
        // 18000 signal limit) — else ActivePositionView's live P&L would be skewed.
        ArgumentCaptor<TradeExecutionRecord> saved = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        TradeExecutionRecord openRow = saved.getAllValues().stream()
            .filter(rec -> "wtx:MNQ:5m:2:REVERSE_SHORT".equals(rec.getExecutionKey()))
            .reduce((a, b) -> b).orElseThrow();
        assertThat(openRow.getNormalizedEntryPrice()).isEqualByComparingTo("18004.00");
    }

    @Test
    void reverse_openStaysPassive_whenReverseOpenDisabled() {
        // Reverse-open marketable OFF: the close still crosses, but the open keeps the passive intent limit.
        DefaultOrderRouter r = pricedRouter(livePrice("18005.00"), 4, false); // reverse-open disabled
        stubActive(priorLong());
        stubBroker("2");
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Filled"));

        r.route(reverseToShort());

        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService, times(2)).submitEntryOrder(req.capture());
        assertThat(req.getAllValues().get(0).limitPrice()).isEqualByComparingTo("18004.00"); // close, crossed
        assertThat(req.getAllValues().get(1).limitPrice()).isEqualByComparingTo("18000.00"); // open, passive intent
    }

    @Test
    void reverse_open_preflightsAgainstCrossedPrice_notPassiveLimit() {
        // A size-increasing reverse must preflight margin at the CROSSED price we'll submit, not the passive
        // signal limit — else a marketable open can pass preflight cheap then be margin-rejected (flat instead
        // of reversed). Reverse SHORT 1 → LONG 2 (delta 1); open BUY crosses up: 18005.00 + 1.00 = 18006.00.
        OrderAffordabilityPort aff = mock(OrderAffordabilityPort.class);
        when(aff.check(any(), any(), anyInt(), any(), any())).thenReturn(OrderAffordabilityPort.Affordability.allow());
        DefaultOrderRouter r = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true, reconciler,
            Instrument::getTickSize, Optional.of(aff), null, livePrice("18005.00"), 4, true, true);
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubBroker("-1"); // broker SHORT 1
        when(reconciler.reconcile(any(), any())).thenReturn(new ReconcilePlan.Reverse(Side.LONG));
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Filled"));

        r.route(reverseToLong());

        ArgumentCaptor<BigDecimal> price = ArgumentCaptor.forClass(BigDecimal.class);
        verify(aff).check(any(), eq("LONG"), eq(1), price.capture(), any());
        assertThat(price.getValue()).isEqualByComparingTo("18006.00"); // crossed (18005 + 1.00), NOT passive 17000
    }

    @Test
    void reverse_flatBroker_preflightsAtPassivePrice_notCrossed() {
        // Reverse reconciled while IBKR is FLAT: NO close fires → the open submits PASSIVE, so the preflight
        // must use the passive intent limit, not the crossed price (else it could falsely deny an affordable
        // open). marketable-reverse-open is ON, but closeLegFired stays false → passive estimate.
        OrderAffordabilityPort aff = mock(OrderAffordabilityPort.class);
        when(aff.check(any(), any(), anyInt(), any(), any())).thenReturn(OrderAffordabilityPort.Affordability.allow());
        DefaultOrderRouter r = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true, reconciler,
            Instrument::getTickSize, Optional.of(aff), null, livePrice("18005.00"), 4, true, true);
        when(reconciler.readPositionState(any(), any())).thenReturn(new BrokerPositionState(BigDecimal.ZERO, true)); // flat
        when(reconciler.reconcile(any(), any())).thenReturn(new ReconcilePlan.Reverse(Side.LONG));
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Filled"));

        r.route(reverseToLong());

        ArgumentCaptor<BigDecimal> price = ArgumentCaptor.forClass(BigDecimal.class);
        verify(aff).check(any(), eq("LONG"), eq(2), price.capture(), any()); // delta = full qty (broker flat)
        assertThat(price.getValue()).isEqualByComparingTo("17000.00"); // passive intent (flat → open passive), NOT crossed
        verify(ibkrOrderService, times(1)).submitEntryOrder(any()); // open only, no close fired
    }

    @Test
    void reverse_open_inline_submitsThePreflightedPrice_evenIfLivePriceTicksAfter() {
        // The inline reverse-open must submit the EXACT price the margin preflight checked — one live read,
        // carried — so a price ticking up between reads can't slip a higher crossed order past margin.
        OrderAffordabilityPort aff = mock(OrderAffordabilityPort.class);
        when(aff.check(any(), any(), anyInt(), any(), any())).thenReturn(OrderAffordabilityPort.Affordability.allow());
        LivePricePort jumpy = mock(LivePricePort.class);
        when(jumpy.current(any()))
            .thenReturn(Optional.of(new LivePriceSnapshot(18005.0, Instant.now(), "LIVE_PUSH")))  // read 1: close leg
            .thenReturn(Optional.of(new LivePriceSnapshot(18100.0, Instant.now(), "LIVE_PUSH")))  // read 2: open preflight
            .thenReturn(Optional.of(new LivePriceSnapshot(18200.0, Instant.now(), "LIVE_PUSH"))); // would be a 2nd open read
        DefaultOrderRouter r = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true, reconciler,
            Instrument::getTickSize, Optional.of(aff), null, jumpy, 4, true, true);
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubBroker("-1"); // broker SHORT 1 → reverse to LONG 2 is size-increasing (delta 1); open BUY crosses up
        when(reconciler.reconcile(any(), any())).thenReturn(new ReconcilePlan.Reverse(Side.LONG));
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Filled"));

        r.route(reverseToLong());

        // Open preflight used read 2 (18100 + 1.00 = 18101); the inline submit reuses THAT exact price —
        // not a third read (18200) — so preflight price == submit price.
        ArgumentCaptor<BigDecimal> affPrice = ArgumentCaptor.forClass(BigDecimal.class);
        verify(aff).check(any(), eq("LONG"), eq(1), affPrice.capture(), any());
        assertThat(affPrice.getValue()).isEqualByComparingTo("18101.00");
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService, times(2)).submitEntryOrder(req.capture());
        assertThat(req.getAllValues().get(1).limitPrice()).isEqualByComparingTo("18101.00"); // submit == preflight (carried)
    }

    @Test
    void close_marketableDisabled_usesPassiveIntentLimit() {
        // Kill-switch OFF → even with a live price, the exit rests at the passive intent limit.
        DefaultOrderRouter r = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true, reconciler,
            Instrument::getTickSize, Optional.empty(), null, livePrice("18000.00"), 4, false, true);
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubBroker("1");
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(891L, "Submitted", "k", Instant.now()));

        r.route(closeLong());

        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().limitPrice()).isEqualByComparingTo("18010.00"); // disabled → intent limit
    }

    @Test
    void closeLeg_priceLookupThrows_fallsBackToPassiveIntentLimit_noCrash() {
        // A price hiccup must NEVER break a close — degrade to the passive intent limit, still route.
        LivePricePort boom = instr -> { throw new RuntimeException("market-data hiccup"); };
        DefaultOrderRouter r = pricedRouter(boom, 4);
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubBroker("1");
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(892L, "Submitted", "k", Instant.now()));

        RoutingResult res = r.route(closeLong());

        assertThat(res.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().limitPrice()).isEqualByComparingTo("18010.00"); // intent limit — no crash
    }

    @Test
    void closeLeg_dbFallbackSource_fallsBackToPassiveIntentLimit() {
        // A DB-fallback candle close is NOT an executable live reference — must not be crossed.
        DefaultOrderRouter r = pricedRouter(livePriceWith("17000.00", "FALLBACK_DB", 0), 4);
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubBroker("1");
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(893L, "Submitted", "k", Instant.now()));

        r.route(closeLong());

        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().limitPrice()).isEqualByComparingTo("18010.00"); // passive intent, NOT 17000 − cross
    }

    @Test
    void closeLeg_staleLivePrice_fallsBackToPassiveIntentLimit() {
        // A live-sourced but stale price (feed outage) is treated like no price → passive limit.
        DefaultOrderRouter r = pricedRouter(livePriceWith("18000.00", "LIVE_PUSH", 120), 4);
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubBroker("1");
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(894L, "Submitted", "k", Instant.now()));

        r.route(closeLong());

        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().limitPrice()).isEqualByComparingTo("18010.00"); // stale → passive intent
    }

    @Test
    void close_liveProviderSource_pricesMarketable() {
        // LIVE_PROVIDER (fresh instant fetch) is also an executable live reference → crossed.
        DefaultOrderRouter r = pricedRouter(livePriceWith("18000.00", "LIVE_PROVIDER", 0), 4);
        stubActive(activeRow(ExecutionStatus.ACTIVE, 1, 100L));
        stubBroker("1");
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(895L, "Submitted", "k", Instant.now()));

        r.route(closeLong());

        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().limitPrice()).isEqualByComparingTo("17999.00"); // 18000.00 − 1.00, crossed
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
    void close_alreadyExitSubmitted_skipsDuplicate() {
        // A close is already resting (EXIT_SUBMITTED) — a second reducing order could over-close the position.
        stubActive(activeRow(ExecutionStatus.EXIT_SUBMITTED, 1, 100L));

        RoutingResult r = router.route(closeLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void reverse_noLocalRow_offsettingLegs_skipsToAvoidStacking() {
        // Rollover overlap: IBKR net 0 but NOT flat (offsetting live legs), no local row — can't synthesise a
        // single close, so opening would stack on the live legs. Skip.
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccount(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(reconciler.readPositionState(any(), any())).thenReturn(new BrokerPositionState(BigDecimal.ZERO, false));

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void reverse_noLocalRow_directionalIbkr_synthesizesCloseThenOpens() {
        // No local row but IBKR holds a directional position (drift) — synthesise a phantom, close it, open.
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccount(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(reconciler.readPositionState(any(), any())).thenReturn(new BrokerPositionState(new BigDecimal("2"), false));
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Filled")); // close fills → open inline

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        verify(repo).createIfAbsent(any());                          // phantom synthesised
        verify(ibkrOrderService, times(2)).submitEntryOrder(any());  // synthesised close + open leg
    }

    @Test
    void reverse_priorEntryStillSubmitted_skipsNoNakedOrders() {
        // Prior entry resting unfilled (ENTRY_SUBMITTED), broker not confirmed flat — no confirmed position
        // to flatten. The reverse must NOT fire a close (naked) then an open (stacked). Skip entirely.
        stubActive(activeRow(ExecutionStatus.ENTRY_SUBMITTED, 2, 100L));
        stubFlat(false);

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT);
        verify(ibkrOrderService, never()).submitEntryOrder(any()); // neither close nor open fired
    }

    @Test
    void reverse_priorEntryPartiallyFilled_skipsNoOverClose() {
        // Partial fill — closing the full row qty would over-close/flip, then the open stacks. Skip.
        stubActive(activeRow(ExecutionStatus.ENTRY_PARTIALLY_FILLED, 2, 100L));
        stubFlat(false);

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void reverse_brokerTruthUnavailable_skipsNoBlindCloseOrStackedOpen() {
        // Can't confirm broker truth — a reverse would fire a blind close (naked if IBKR is already flat
        // after a restart/manual close) and then stack the open. Skip (mirrors executeExit). The skip happens
        // before the open-row lookup, so no row stub is needed.
        when(reconciler.readPositionState(any(), any())).thenReturn(BrokerPositionState.unavailable());

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void reverse_staleRowOppositeBrokerSide_closeFromBrokerTruth() {
        // Row says LONG but IBKR actually holds SHORT (drift / a missed reverse). The reverse close leg must
        // reduce the BROKER's side — BUY to cover the short — NOT derive SELL from the stale row (which would
        // increase the live short before the open leg).
        TradeExecutionRecord prior = activeRow(ExecutionStatus.ACTIVE, 2, 100L);
        prior.setAction("LONG"); // stale local belief
        stubActive(prior);
        stubBroker("-2"); // broker actually holds SHORT 2
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Filled")); // close fills → open inline

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService, times(2)).submitEntryOrder(req.capture());
        assertThat(req.getAllValues().get(0).action()).isEqualTo("LONG"); // close leg = BUY to reduce the short, not SELL
    }

    @Test
    void open_nullAccount_persistsNonNullPlaceholder() {
        // TradeIntent allows a null brokerAccountId (gateway resolves default), but the row column is
        // NOT NULL — the router must persist a non-null placeholder the gateway resolves.
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(12345L, "Submitted"));
        TradeIntent noAccount = TradeIntent.open("wtx:MNQ:5m:9:OPEN_LONG", ExecutionTriggerSource.WTX_AUTO,
            Instrument.MNQ, "5m", Side.LONG, 1, new BigDecimal("18000.25"), null);

        RoutingResult r = router.route(noAccount);

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getBrokerAccountId()).isNotNull().isNotBlank();
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().brokerAccountId()).isNotNull();
    }

    @Test
    void entry_skipDuplicate_noLocalRow_synthesizesTrackingRow() {
        // Broker already long, no local row (drift / post-restart) — synthesise a tracking row so a later
        // CLOSE/FLATTEN can manage the live position instead of finding nothing.
        when(reconciler.reconcile(any(), any())).thenReturn(
            new ReconcilePlan.Skip(RoutingOutcome.SKIPPED_DUPLICATE, "IBKR already long 2"));
        when(reconciler.readPositionState(any(), any())).thenReturn(new BrokerPositionState(new BigDecimal("2"), false));
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccount(any(), any(), any(), any())).thenReturn(Optional.empty());

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
        assertThat(r.executionId()).isEqualTo(9L);         // the synthesised tracking row
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).createIfAbsent(cap.capture());         // tracking row created
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.ACTIVE);
        assertThat(cap.getValue().getNormalizedEntryPrice()).isNotNull(); // NOT NULL column — must be set or flush fails
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
        DefaultOrderRouter gated = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> false, reconciler,
            Instrument::getTickSize, Optional.empty(), null, NO_PRICE, 10, true, true);

        RoutingResult r = gated.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_RECONCILING);
        verifyNoInteractions(ibkrOrderService);
    }

    // ---- D4 — margin pre-flight after reconcile ------------------------------------------------

    private DefaultOrderRouter routerWith(OrderAffordabilityPort aff) {
        return new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true, reconciler,
            Instrument::getTickSize, Optional.of(aff), null, NO_PRICE, 10, true, true);
    }

    @Test
    void open_lossCapTripped_skipsAutoOff_noBrokerOrder() {
        DailyLossCapGuard tripped = mock(DailyLossCapGuard.class);
        when(tripped.blocksNewEntries()).thenReturn(true);
        DefaultOrderRouter capped = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true, reconciler,
            Instrument::getTickSize, Optional.empty(), tripped, NO_PRICE, 10, true, true);

        RoutingResult r = capped.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_AUTO_OFF);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void open_marginDenied_skipsInsufficientMargin_noBrokerOrder() {
        OrderAffordabilityPort aff = mock(OrderAffordabilityPort.class);
        when(aff.check(any(), any(), anyInt(), any(), any()))
            .thenReturn(OrderAffordabilityPort.Affordability.deny("AvailFunds 5 < InitMargin 9"));

        RoutingResult r = routerWith(aff).route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN);
        assertThat(r.message()).contains("AvailFunds 5 < InitMargin 9");
        verify(ibkrOrderService, never()).submitEntryOrder(any());      // declined before any broker side effect
        verify(aff).check(eq(Instrument.MNQ), eq("LONG"), eq(2), any(), eq("DU1")); // full qty, routed account
    }

    @Test
    void open_marginAllowed_routes() {
        OrderAffordabilityPort aff = mock(OrderAffordabilityPort.class);
        when(aff.check(any(), any(), anyInt(), any(), any())).thenReturn(OrderAffordabilityPort.Affordability.allow());
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(12345L, "Submitted"));

        RoutingResult r = routerWith(aff).route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        verify(ibkrOrderService).submitEntryOrder(any());
    }

    @Test
    void reverse_sizeIncrease_openLegUnaffordable_closeStillFires_routedFlattenOnly() {
        OrderAffordabilityPort aff = mock(OrderAffordabilityPort.class);
        when(aff.check(any(), any(), anyInt(), any(), any()))
            .thenReturn(OrderAffordabilityPort.Affordability.deny("no funds for the extra contract"));
        TradeExecutionRecord prior = priorLong();
        prior.setQuantity(1);                                            // held LONG 1
        stubActive(prior);
        stubBroker("1");                                                 // broker LONG 1
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Submitted")); // close ok

        RoutingResult r = routerWith(aff).route(reverseToShort());       // reverse to SHORT 2 → delta 1

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED_FLATTEN_ONLY); // flatten protects the user
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());      // ONLY the close leg; open skipped
        verify(aff).check(eq(Instrument.MNQ), eq("SHORT"), eq(1), any(), eq("DU1")); // delta 2−1, routed account
    }

    @Test
    void reverse_sameSize_marginPreflightSkipped_bothLegsRoute() {
        OrderAffordabilityPort aff = mock(OrderAffordabilityPort.class);
        // A hard-deny stub that must NEVER be consulted: a same-size reverse frees exactly what it consumes.
        lenient().when(aff.check(any(), any(), anyInt(), any(), any()))
            .thenReturn(OrderAffordabilityPort.Affordability.deny("should never be called"));
        stubActive(priorLong());                                        // LONG 2
        stubBroker("2");                                                 // broker LONG 2 → reverse SHORT 2, delta 0
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Filled")); // close fills → open inline

        RoutingResult r = routerWith(aff).route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        verify(ibkrOrderService, times(2)).submitEntryOrder(any());     // close + open both fired
        verify(aff, never()).check(any(), any(), anyInt(), any(), any());      // delta 0 → never pre-checked
    }

    // ---- D2 — reverse open deferred behind the close FILL --------------------------------------

    @Test
    void reverse_closeResting_defersOpenBehindCloseFill() {
        // The close RESTS (EXIT_SUBMITTED, not immediately filled) → the open is held back: persisted as a
        // deferred PENDING row linked to the close ROW (by PK), NOT submitted now. Only the close reaches IBKR.
        TradeExecutionRecord prior = priorLong(); // id 7
        stubActive(prior);
        stubBroker("2");                                                // broker LONG 2 → reverse SHORT 2
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(900L, "Submitted")); // close RESTS

        RoutingResult r = router.route(reverseToShort());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        assertThat(r.message()).contains("deferred");
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());     // ONLY the close — open is deferred
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).createIfAbsentTracked(cap.capture());              // the deferred open row
        TradeExecutionRecord deferred = cap.getValue();
        assertThat(deferred.getStatus()).isEqualTo(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        assertThat(deferred.getExecutionKey()).isEqualTo("wtx:MNQ:5m:2:REVERSE_SHORT");
        assertThat(deferred.getDeferredReverseCloseRowId()).isEqualTo(prior.getId()); // linked to the close ROW PK
    }
}

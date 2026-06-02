package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.engine.strategy.wtx.WtxAction;
import com.riskdesk.domain.engine.strategy.wtx.WtxEnrichmentSnapshot;
import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignalType;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.execution.DefaultOrderRouter;
import com.riskdesk.application.execution.ExecutionReconciler;
import com.riskdesk.application.execution.OrderRouter;
import com.riskdesk.application.service.IbkrMarginPreflightService;
import com.riskdesk.domain.execution.IntentKind;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.infrastructure.config.ExecutionProperties;
import org.mockito.ArgumentCaptor;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WtxExecutionBridgeTest {

    private FakeRepo repo;
    private IbkrOrderService ibkrOrderService;
    private IbkrProperties ibkrProperties;
    private WtxStrategyProperties wtxProperties;
    private WtxExecutionBridge bridge;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        ibkrOrderService = mock(IbkrOrderService.class);
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenReturn(new BrokerEntryOrderSubmission(999L, "Submitted", "ref", Instant.now()));
        ibkrProperties = new IbkrProperties();
        ibkrProperties.setEnabled(true);
        wtxProperties = new WtxStrategyProperties();
        bridge = new WtxExecutionBridge(ibkrOrderService, repo, ibkrProperties, wtxProperties);
    }

    @Test
    void openLong_persistsLongAction_notWtxEnum() {
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(1, repo.all().size());
        TradeExecutionRecord row = repo.all().get(0);
        // "LONG" — the token IbGatewayBrokerGateway reads as a BUY; "BUY" would be misread.
        assertEquals("LONG", row.getAction(), "action must be the broker-side direction token, not OPEN_LONG");
        assertEquals(2, row.getQuantity());
        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        assertEquals(ExecutionTriggerSource.WTX_AUTO, row.getTriggerSource());
        // The broker order id is persisted on ibkrOrderId so onOrderStatus (orderId-only lookup) can locate it.
        assertEquals(999, row.getIbkrOrderId());
    }

    @Test
    void openShort_persistsShortAction() {
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(2), bd(1));
        bridge.submit(signal(WtxAction.OPEN_SHORT), state, bd(100));

        assertEquals("SHORT", repo.all().get(0).getAction());
    }

    // ---- unified-router migration (Slice B) ----------------------------------------------------

    private static ExecutionProperties unifiedRouter(boolean enabled) {
        ExecutionProperties p = new ExecutionProperties();
        p.getUnifiedRouter().setEnabled(enabled);
        return p;
    }

    private WtxExecutionBridge unifiedBridge(OrderRouter router, IbkrMarginPreflightService preflight, boolean flagOn) {
        return new WtxExecutionBridge(ibkrOrderService, repo, ibkrProperties, wtxProperties,
                preflight, null, router, unifiedRouter(flagOn));
    }

    @Test
    void unifiedRouterOn_routesOpenViaOrderRouter_translatesIntent_mapsOutcome() {
        OrderRouter router = mock(OrderRouter.class);
        ArgumentCaptor<TradeIntent> cap = ArgumentCaptor.forClass(TradeIntent.class);
        when(router.route(cap.capture())).thenReturn(RoutingResult.of(RoutingOutcome.ROUTED, "ok"));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = unifiedBridge(router, null, true).submit(signal(WtxAction.OPEN_LONG), state, bd(70));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any()); // legacy broker path NOT used
        assertEquals(0, repo.all().size());                         // the router owns persistence, not the bridge
        TradeIntent intent = cap.getValue();
        assertEquals(IntentKind.OPEN, intent.kind());
        assertEquals(Side.LONG, intent.side());
        assertEquals(Instrument.MCL, intent.instrument());
        assertEquals("10m", intent.timeframe());
        assertEquals(2, intent.quantity());
        assertEquals(ExecutionTriggerSource.WTX_AUTO, intent.source());
        // The "wtx-default" placeholder resolves to null so the router reads the REAL default account
        // (its position filter is a no-op on null) instead of hiding live positions behind the placeholder.
        assertNull(intent.brokerAccountId());
        assertEquals(0, intent.limitPrice().compareTo(bd(70)));
        org.junit.jupiter.api.Assertions.assertTrue(intent.idempotencyKey().startsWith("wtx:MCL:10m:"));
        org.junit.jupiter.api.Assertions.assertTrue(intent.idempotencyKey().endsWith(":OPEN_LONG"));
    }

    @Test
    void unifiedRouterOff_usesLegacyPath_routerNotCalled() {
        OrderRouter router = mock(OrderRouter.class);
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));

        unifiedBridge(router, null, false).submit(signal(WtxAction.OPEN_LONG), state, bd(70));

        verify(router, never()).route(any());
        verify(ibkrOrderService).submitEntryOrder(any()); // legacy path submitted
    }

    @Test
    void unifiedRouterOn_brokerMarginReject_mapsToSkippedInsufficientMargin_afterRouting() {
        // No pre-route pre-flight in the unified path — the router must reconcile broker truth first (a margin
        // pre-decline could skip routing and leave an existing position unmanaged). Affordability is enforced
        // by the router's broker margin reject, mapped back to SKIPPED_INSUFFICIENT_MARGIN.
        OrderRouter router = mock(OrderRouter.class);
        when(router.route(any())).thenReturn(RoutingResult.of(RoutingOutcome.FAILED_INSUFFICIENT_MARGIN, "no margin"));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = unifiedBridge(router, null, true).submit(signal(WtxAction.OPEN_LONG), state, bd(70));

        assertEquals(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, result.outcome());
        verify(router).route(any()); // routed (reconcile ran); margin handled by the router, not a pre-decline
    }

    @Test
    void cutover_normalizesLegacyDefaultAccountRows_onlyWhenFlagOn() {
        // A legacy WTX row created before cutover stores brokerAccountId = "wtx-default".
        repo.createIfAbsent(wtxRow("LONG", 2, ExecutionStatus.ACTIVE));

        // Flag OFF → no-op (zero impact): the row keeps its legacy placeholder.
        unifiedBridge(mock(OrderRouter.class), null, false).normalizeLegacyDefaultAccountRowsForCutover();
        assertEquals("wtx-default", repo.all().get(0).getBrokerAccountId());

        // Flag ON → re-pointed to "__default__" so the router's account-scoped findOpenRow can locate it
        // (else a CLOSE after cutover would SKIPPED_NO_OPEN_ROW and leave the live position open).
        unifiedBridge(mock(OrderRouter.class), null, true).normalizeLegacyDefaultAccountRowsForCutover();
        assertEquals("__default__", repo.all().get(0).getBrokerAccountId());
    }

    // ---- Slice C: legacy ↔ unified parity ------------------------------------------------------

    /**
     * Runs the same signal through the LEGACY path (flag OFF) and the UNIFIED path (flag ON, with a REAL
     * OrderRouter), each with its own broker mock + repo, and returns the broker order each path submitted —
     * [0] legacy, [1] unified. The router's reconciler sees no broker truth (unavailable → pass-through),
     * matching the legacy path with no portfolio service, so both open from a clean slate.
     */
    private BrokerEntryOrderRequest[] runBothPaths(WtxAction action, WtxStrategyState state, BigDecimal refPrice) {
        IbkrOrderService legacyBroker = mock(IbkrOrderService.class);
        when(legacyBroker.submitEntryOrder(any()))
                .thenReturn(new BrokerEntryOrderSubmission(1L, "Submitted", "ref", Instant.now()));
        new WtxExecutionBridge(legacyBroker, new FakeRepo(), ibkrProperties, wtxProperties)
                .submit(signal(action), state, refPrice);

        IbkrOrderService unifiedBroker = mock(IbkrOrderService.class);
        when(unifiedBroker.submitEntryOrder(any()))
                .thenReturn(new BrokerEntryOrderSubmission(2L, "Submitted", "ref", Instant.now()));
        FakeRepo unifiedRepo = new FakeRepo();
        DefaultOrderRouter router = new DefaultOrderRouter(unifiedBroker, unifiedRepo, ibkrProperties,
                () -> true, new ExecutionReconciler(null), Instrument::getTickSize, Optional.empty());
        new WtxExecutionBridge(unifiedBroker, unifiedRepo, ibkrProperties, wtxProperties,
                null, null, router, unifiedRouter(true)).submit(signal(action), state, refPrice);

        ArgumentCaptor<BrokerEntryOrderRequest> legacyCap = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(legacyBroker).submitEntryOrder(legacyCap.capture());
        ArgumentCaptor<BrokerEntryOrderRequest> unifiedCap = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(unifiedBroker).submitEntryOrder(unifiedCap.capture());
        return new BrokerEntryOrderRequest[]{legacyCap.getValue(), unifiedCap.getValue()};
    }

    private static void assertSameTrade(BrokerEntryOrderRequest legacy, BrokerEntryOrderRequest unified) {
        assertEquals(legacy.action(), unified.action(), "broker action must match");
        assertEquals(legacy.quantity(), unified.quantity(), "quantity must match");
        assertEquals(legacy.instrument(), unified.instrument(), "instrument must match");
        assertEquals(0, legacy.limitPrice().compareTo(unified.limitPrice()), "rounded limit price must match");
        // Account differs only by placeholder ("wtx-default" vs "__default__") — both resolve to the same
        // default managed account at the gateway, so it is intentionally not asserted here.
    }

    @Test
    void parity_openLong_bothPathsSubmitSameBrokerOrder() {
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        BrokerEntryOrderRequest[] r = runBothPaths(WtxAction.OPEN_LONG, state, bd(70));
        assertSameTrade(r[0], r[1]);
        assertEquals("LONG", r[1].action()); // unified path BUYs a long, same as legacy
    }

    @Test
    void parity_openShort_bothPathsSubmitSameBrokerOrder() {
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(2), bd(1));
        BrokerEntryOrderRequest[] r = runBothPaths(WtxAction.OPEN_SHORT, state, bd(70));
        assertSameTrade(r[0], r[1]);
        assertEquals("SHORT", r[1].action());
    }

    @Test
    void closeLong_marksExistingRowExitSubmitted_andCreatesNoNewRow() {
        // Seed an open WTX long entry row
        TradeExecutionRecord open = wtxRow("LONG", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(open);

        WtxStrategyState state = flatState().withAutoExecution(true); // position already flattened by the service
        bridge.submit(signal(WtxAction.CLOSE_LONG), state, bd(105));

        assertEquals(1, repo.all().size(), "CLOSE must not create a second execution row");
        TradeExecutionRecord row = repo.all().get(0);
        // Non-terminal until the broker confirms the fill — must NOT be CLOSED yet.
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, row.getStatus());
        assertNotNull(row.getExitSubmittedAt());
        assertNull(row.getClosedAt(), "closedAt must stay null until the broker fill is reconciled");
        // The close order id is persisted on ibkrOrderId so onOrderStatus can reconcile the fill.
        assertEquals(999, row.getIbkrOrderId());
        // The flatten order is a SHORT (sell) of the original 2 contracts, submitted under a DISTINCT,
        // retry-safe exit orderRef ("<executionKey>:exit:<bar ts>") so placeLimitOrder's idempotency can't
        // return the completed entry order, and a later retry after a terminal-non-filled close gets a fresh ref.
        verify(ibkrOrderService).submitEntryOrder(argThat(r ->
                "SHORT".equals(r.action()) && r.quantity() == 2
                        && r.executionKey().contains(":exit:")));
    }

    @Test
    void closeLong_alreadyExitSubmitted_skipsDuplicateFlatten() {
        // An exit is already in flight on the open row
        TradeExecutionRecord exiting = wtxRow("LONG", 2, ExecutionStatus.EXIT_SUBMITTED);
        repo.createIfAbsent(exiting);

        WtxStrategyState state = flatState().withAutoExecution(true);
        bridge.submit(signal(WtxAction.CLOSE_LONG), state, bd(105));

        verify(ibkrOrderService, never()).submitEntryOrder(any());
        assertEquals(1, repo.all().size());
    }

    @Test
    void closeLong_noOpenRow_skipsSubmissionEntirely() {
        WtxStrategyState state = flatState().withAutoExecution(true);
        bridge.submit(signal(WtxAction.CLOSE_LONG), state, bd(105));

        assertTrue(repo.all().isEmpty(), "no row must be created when there is nothing to close");
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void reverseToLong_submitsCloseLegThenOpenLeg_eachAtPositionQty() {
        // Seed an open WTX short row that the reverse must flatten
        TradeExecutionRecord priorShort = wtxRow("SHORT", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorShort);

        // After applyAction the service hands the bridge the NEW long position state
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridge.submit(signal(WtxAction.REVERSE_TO_LONG), state, bd(100));

        assertEquals(2, repo.all().size());

        // Prior row → EXIT_SUBMITTED via its own close-leg order (a real 1:1 broker order),
        // NOT terminally closed before the fill — the fill tracker reconciles it to CLOSED.
        TradeExecutionRecord prior = repo.byId(priorShort.getId());
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, prior.getStatus(),
                "prior short row gets its own close-leg order, left non-terminal until the fill");
        assertNull(prior.getClosedAt());
        assertEquals(999, prior.getIbkrOrderId(), "prior row carries its close-leg order id for fill tracking");

        // New row → ENTRY_SUBMITTED via its own open-leg order, at position size (not doubled).
        TradeExecutionRecord fresh = repo.all().stream()
                .filter(r -> r.getStatus() == ExecutionStatus.ENTRY_SUBMITTED)
                .findFirst().orElseThrow();
        assertEquals("LONG", fresh.getAction());
        assertEquals(2, fresh.getQuantity(), "row quantity is the resulting position size");
        assertEquals(999, fresh.getIbkrOrderId(), "new row carries the open-leg order id for fill tracking");

        // Two independent orders, each at positionQty (2) — no doubled order.
        verify(ibkrOrderService, times(2)).submitEntryOrder(argThat(r ->
                "LONG".equals(r.action()) && r.quantity() == 2));
    }

    @Test
    void reverseToLong_closeLegRejected_abortsWithoutOpening() {
        TradeExecutionRecord priorShort = wtxRow("SHORT", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorShort);
        // Close leg is submitted first — make it throw.
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IllegalStateException("IBKR rejected"));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridge.submit(signal(WtxAction.REVERSE_TO_LONG), state, bd(100));

        // Prior row stays ACTIVE (close leg failed) and NO open-leg row is created — nothing
        // changed, the live position keeps its active row for the next bar to retry.
        assertEquals(1, repo.all().size(), "open leg must not run when the close leg is rejected");
        assertEquals(ExecutionStatus.ACTIVE, repo.byId(priorShort.getId()).getStatus());
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());
    }

    @Test
    void reverseToLong_closeLegSucceedsButOpenLegFails_priorExitSubmitted_newRowFailed() {
        TradeExecutionRecord priorShort = wtxRow("SHORT", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorShort);
        // Close leg accepted, open leg rejected.
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenReturn(new BrokerEntryOrderSubmission(777L, "Submitted", "ref", Instant.now()))
                .thenThrow(new IllegalStateException("IBKR rejected open leg"));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridge.submit(signal(WtxAction.REVERSE_TO_LONG), state, bd(100));

        TradeExecutionRecord prior = repo.byId(priorShort.getId());
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, prior.getStatus(),
                "prior row's close leg legitimately went through");
        assertEquals(777, prior.getIbkrOrderId());
        TradeExecutionRecord fresh = repo.all().stream()
                .filter(r -> !r.getId().equals(priorShort.getId()))
                .findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, fresh.getStatus());
    }

    @Test
    void reverseToLong_duplicateSignal_isCleanNoOpOnSecondCall() {
        TradeExecutionRecord priorShort = wtxRow("SHORT", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorShort);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxSignal sig = signal(WtxAction.REVERSE_TO_LONG);
        bridge.submit(sig, state, bd(100));   // first reverse: close leg + open leg
        bridge.submit(sig, state, bd(100));   // duplicate signalTs+action → dup-check returns first

        // The duplicate is skipped before any submission: 2 rows total, exactly 2 orders (from call 1).
        assertEquals(2, repo.all().size(), "duplicate reverse must not create a third row");
        verify(ibkrOrderService, times(2)).submitEntryOrder(any());
    }

    @Test
    void autoExecutionDisabled_isNoOp() {
        WtxStrategyState state = flatState() // autoExecutionEnabled defaults to false
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertTrue(repo.all().isEmpty());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void ibkrDisabled_isNoOp() {
        ibkrProperties.setEnabled(false);
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertTrue(repo.all().isEmpty());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void noneAction_isNoOp() {
        WtxStrategyState state = flatState().withAutoExecution(true);
        bridge.submit(signal(WtxAction.NONE), state, bd(100));

        assertTrue(repo.all().isEmpty());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void duplicateEntryKey_isSkipped() {
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxSignal sig = signal(WtxAction.OPEN_LONG);
        bridge.submit(sig, state, bd(100));
        bridge.submit(sig, state, bd(100)); // same signalTs + action → same executionKey

        assertEquals(1, repo.all().size());
    }

    // ── routing-outcome reporting ──────────────────────────────────────────

    @Test
    void openLong_returnsRouted() {
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        assertEquals(WtxRoutingOutcome.ROUTED,
                bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100)).outcome());
    }

    @Test
    void autoExecutionDisabled_returnsSkippedAutoOff() {
        WtxStrategyState state = flatState() // autoExecutionEnabled defaults to false
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        assertEquals(WtxRoutingOutcome.SKIPPED_AUTO_OFF,
                bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100)).outcome());
    }

    @Test
    void ibkrDisabled_returnsSkippedIbkrDisabled() {
        ibkrProperties.setEnabled(false);
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        assertEquals(WtxRoutingOutcome.SKIPPED_IBKR_DISABLED,
                bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100)).outcome());
    }

    @Test
    void duplicateEntryKey_returnsSkippedDuplicate() {
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxSignal sig = signal(WtxAction.OPEN_LONG);
        bridge.submit(sig, state, bd(100));
        assertEquals(WtxRoutingOutcome.SKIPPED_DUPLICATE, bridge.submit(sig, state, bd(100)).outcome());
    }

    @Test
    void closeLong_noOpenRow_returnsSkippedNoOpenRow() {
        WtxStrategyState state = flatState().withAutoExecution(true);
        assertEquals(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW,
                bridge.submit(signal(WtxAction.CLOSE_LONG), state, bd(105)).outcome());
    }

    @Test
    void close_isScopedToTimeframe_doesNotTargetAnotherTimeframeRow() {
        // An open WTX long row exists on 5m only.
        repo.createIfAbsent(wtxRow("LONG", 2, ExecutionStatus.ACTIVE, "5m"));

        // A 10m close must NOT flatten the 5m row — there is no 10m open row.
        WtxStrategyState state10m = WtxStrategyState.initial("MCL", "10m", bd(10_000)).withAutoExecution(true);
        WtxRoutingOutcome outcome = bridge.submit(signal(WtxAction.CLOSE_LONG, "10m"), state10m, bd(105)).outcome();

        assertEquals(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW, outcome);
        assertEquals(ExecutionStatus.ACTIVE, repo.all().get(0).getStatus(), "the 5m row must be untouched");
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    // ── typed broker rejection outcomes (slice 1) ──────────────────────────

    @Test
    void openLong_brokerRejectsInsufficientMargin_returnsSkippedInsufficientMargin_rowStaysNonTerminal() {
        // IBKR rejects with the same shape as the prod 09:20Z bug: code=201, margin insufficient.
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN, 201,
                        "Equity with Loan Value [9757.44 USD] < Initial Margin [11729.16 USD]",
                        "IBKR margin insufficient (code=201)"));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, result.outcome());
        assertNotNull(result.errorMessage(), "errorMessage must carry the broker reject text for UI tooltip");
        assertTrue(result.errorMessage().toLowerCase().contains("margin"));

        // Row is NOT terminal — leaving ACTIVE so the next bar can retry once funds return.
        TradeExecutionRecord row = repo.all().get(0);
        assertFalse(row.getStatus() == ExecutionStatus.FAILED,
                "INSUFFICIENT_MARGIN must NOT mark the row FAILED");
    }

    @Test
    void openLong_brokerTimeout_returnsFailedTimeout_rowStaysNonTerminal_manualReconcileHint() {
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.TIMEOUT, null, null,
                        "IBKR order submission timed out without acknowledgement."));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.FAILED_TIMEOUT, result.outcome());
        TradeExecutionRecord row = repo.all().get(0);
        // Conservative behavior: row stays non-terminal (broker state unknown) — operator
        // reconciles manually. Hint must be in the statusReason for log diagnosis.
        assertFalse(row.getStatus() == ExecutionStatus.FAILED,
                "TIMEOUT must NOT terminal-fail the row — broker state unknown");
        assertNotNull(row.getStatusReason());
        assertTrue(row.getStatusReason().toLowerCase().contains("manual reconcile"),
                "statusReason must hint at manual reconciliation");
    }

    @Test
    void openLong_brokerTimeoutWithOrderId_returnsAckPending_andPersistsOrderIdForLateCallbacks() {
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.TIMEOUT, null, null,
                        "IBKR order submission timed out without acknowledgement.",
                        12345L));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.ACK_PENDING, result.outcome());
        TradeExecutionRecord row = repo.all().get(0);
        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        assertEquals(12345L, row.getEntryOrderId());
        assertEquals(12345, row.getIbkrOrderId());
        assertNotNull(row.getEntrySubmittedAt());
        assertTrue(row.getStatusReason().toLowerCase().contains("acknowledgement pending"));
    }

    @Test
    void openLong_brokerCancelled_returnsFailedBrokerReject_rowMarkedFailed() {
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.BROKER_REJECT, null,
                        "Order Cancelled (price doesn't conform)",
                        "IBKR order Cancelled"));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.FAILED_BROKER_REJECT, result.outcome());
        TradeExecutionRecord row = repo.all().get(0);
        assertEquals(ExecutionStatus.FAILED, row.getStatus(),
                "BROKER_REJECT terminally fails the row — broker explicitly refused, no recovery path");
    }

    @Test
    void reverseToShort_closeLegMarginCode_isBrokerReject_notNoMargin_priorRowStaysActive() {
        // Seed an open LONG that the reverse must flatten before going SHORT.
        TradeExecutionRecord priorLong = wtxRow("LONG", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorLong);

        // IBKR returns a margin code on the close (reducing) leg. This is spurious — a flatten
        // can never need margin — so the bridge must NOT surface it as NO MARGIN.
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN, 201,
                        "Equity 9757 < InitMargin 11729",
                        "IBKR margin insufficient (code=201)"));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridge.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        // Reducing-order margin code → retryable broker reject, never SKIPPED_INSUFFICIENT_MARGIN.
        assertEquals(WtxRoutingOutcome.FAILED_BROKER_REJECT, result.outcome());
        assertNotNull(result.errorMessage());
        // Prior row left ACTIVE — close leg failed, position remains visible for next bar.
        assertEquals(ExecutionStatus.ACTIVE, repo.byId(priorLong.getId()).getStatus(),
                "prior row stays ACTIVE when the close leg is rejected — retryable next bar");
        // Only one broker call (the close leg) — no open leg submission.
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());
    }

    @Test
    void reverseToShort_closeLegFlattens_openLegIbkrMarginReject_isFlattenOnly_notNoMargin() {
        // Exact prod repro (screenshot): a same-size 1→1 REVERSE skips the heuristic preflight
        // (delta 0), so IBKR itself rejects the OPEN leg with code 201 — but only AFTER the close
        // leg already flattened the prior position. The user is FLAT at the broker; the UI must NOT
        // show NO MARGIN, and the caller must correct virtual state to FLAT (not the never-opened
        // SHORT). Close leg succeeds (call #1), open leg margin-rejected (call #2).
        TradeExecutionRecord priorLong = wtxRow("LONG", 1, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorLong);

        when(ibkrOrderService.submitEntryOrder(any()))
                .thenReturn(new BrokerEntryOrderSubmission(777L, "Submitted", "ref", Instant.now()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN, 201,
                        "Equity 9757 < InitMargin 11729",
                        "IBKR margin insufficient (code=201)"));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(1), bd(1))
                .withConfiguredOrderQty(1);
        WtxRoutingResult result = bridge.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        // Broker is FLAT — flatten-only, never NO MARGIN.
        assertEquals(WtxRoutingOutcome.ROUTED_FLATTEN_ONLY, result.outcome());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().toLowerCase().contains("reversed to flat"),
                "errorMessage must explain the user was flattened, open leg not opened");
        // Prior LONG row was flattened (close leg sent).
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, repo.byId(priorLong.getId()).getStatus());
        // The never-opened SHORT leg is terminal-failed (we do NOT retry the open — flatten au minimum).
        TradeExecutionRecord openLeg = repo.all().stream()
                .filter(r -> !r.getId().equals(priorLong.getId()))
                .findFirst().orElseThrow();
        assertEquals(ExecutionStatus.FAILED, openLeg.getStatus(),
                "open leg that IBKR margin-rejected must be terminal — not left pending for retry");
        // Two broker calls: close (flatten) + open (rejected) attempt.
        verify(ibkrOrderService, times(2)).submitEntryOrder(any());
    }

    @Test
    void reverseToShort_closeLegTimeout_returnsFailedTimeout_priorRowNonTerminal() {
        TradeExecutionRecord priorLong = wtxRow("LONG", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorLong);

        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.TIMEOUT, null, null,
                        "IBKR order submission timed out without acknowledgement."));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridge.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        assertEquals(WtxRoutingOutcome.FAILED_TIMEOUT, result.outcome());
        // Prior row must stay non-terminal (broker state unknown — ack may have been lost).
        TradeExecutionRecord prior = repo.byId(priorLong.getId());
        assertFalse(prior.getStatus() == ExecutionStatus.FAILED,
                "close-leg TIMEOUT must keep prior row non-terminal — no double-flatten risk");
        // No open-leg attempt.
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());
    }

    @Test
    void reverseToShort_closeLegTimeoutWithOrderId_returnsAckPending_priorRowExitSubmittedForLateCallbacks() {
        TradeExecutionRecord priorLong = wtxRow("LONG", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorLong);

        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.TIMEOUT, null, null,
                        "IBKR order submission timed out without acknowledgement.",
                        54321L));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridge.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        assertEquals(WtxRoutingOutcome.ACK_PENDING, result.outcome());
        TradeExecutionRecord prior = repo.byId(priorLong.getId());
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, prior.getStatus());
        assertEquals(54321, prior.getIbkrOrderId());
        assertNotNull(prior.getExitSubmittedAt());
        assertTrue(prior.getStatusReason().toLowerCase().contains("acknowledgement pending"));
        // Reversal is lost: open leg not attempted, no fill-driven retry exists yet.
        // The hint must surface in the routing result so the UI tooltip carries it.
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().toLowerCase().contains("reversal lost"),
                "errorMessage must hint that the reversal was lost (open leg not attempted)");
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());
    }

    // ── pre-flight margin (slice 2) ────────────────────────────────────────

    @Test
    void preflightDenies_returnsSkippedInsufficientMargin_noBrokerCall() {
        // Bridge wired with a stub preflight that always denies — verifies the bridge
        // short-circuits BEFORE any broker call (the desired behavior for the prod bug).
        com.riskdesk.application.service.IbkrMarginPreflightService stub =
                org.mockito.Mockito.mock(com.riskdesk.application.service.IbkrMarginPreflightService.class);
        when(stub.canAffordOrder(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision
                        .deny("Equity 9757 < est. InitMargin 11729 (qty=4)"));
        WtxExecutionBridge bridgeWithPreflight = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, stub);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridgeWithPreflight.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, result.outcome());
        assertNotNull(result.errorMessage(), "deny reason must propagate to errorMessage for the UI tooltip");
        assertTrue(result.errorMessage().contains("InitMargin"));
        // Critical: no broker call at all — the whole point of preflight.
        verify(ibkrOrderService, never()).submitEntryOrder(any());
        assertTrue(repo.all().isEmpty(), "no execution row created when preflight denies");
    }

    @Test
    void preflightSkippedForSameSizeReverse_evenWhenDeniedItRoutesThrough() {
        // The prod false-denial: a same-size REVERSE has NET margin delta ≈ 0 at IBKR (close leg
        // releases exactly what the open leg consumes), so the gross-estimate preflight must not
        // gate it. Even if the preflight stub is told to deny, the bridge must route through.
        com.riskdesk.application.service.IbkrMarginPreflightService spy =
                org.mockito.Mockito.mock(com.riskdesk.application.service.IbkrMarginPreflightService.class);
        when(spy.canAffordOrder(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision
                        .deny("Equity 5000 < est. InitMargin 6600"));
        WtxExecutionBridge bridgeWithPreflight = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, spy);

        // Prior LONG of 2 to flatten + new SHORT of 2 → delta = 0 → preflight not consulted.
        repo.createIfAbsent(wtxRow("LONG", 2, ExecutionStatus.ACTIVE));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridgeWithPreflight.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        verify(spy, never()).canAffordOrder(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any());
        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        verify(ibkrOrderService, times(2)).submitEntryOrder(any());
    }

    @Test
    void preflightDeniedSizeIncreasingReverse_flattensToFlat_skipsOpenLeg() {
        // If the user bumped the panel qty before a REVERSE, the open leg is larger than the
        // close leg, so the open's NET margin delta may be unaffordable. The user still wants
        // out of the current position — "flatten au minimum". The bridge must fire the close
        // leg (go FLAT, protected) and skip ONLY the open leg, never leaving the user stuck.
        com.riskdesk.application.service.IbkrMarginPreflightService spy =
                org.mockito.Mockito.mock(com.riskdesk.application.service.IbkrMarginPreflightService.class);
        when(spy.canAffordOrder(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision
                        .deny("Equity 5000 < est. InitMargin 9900"));
        WtxExecutionBridge bridgeWithPreflight = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, spy);

        // Prior LONG of 1 to flatten; new SHORT sized to 5 contracts (panel qty was bumped).
        TradeExecutionRecord priorLong = wtxRow("LONG", 1, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorLong);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(5), bd(1))
                .withConfiguredOrderQty(5);
        WtxRoutingResult result = bridgeWithPreflight.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        // Preflight ran on delta=4 (5 − 1) and denied → open leg skipped, but the close leg fires.
        verify(spy, times(1)).canAffordOrder(any(), any(),
                org.mockito.ArgumentMatchers.eq(4), any());
        // ROUTED_FLATTEN_ONLY (not ROUTED): broker is FLAT and the caller must correct the
        // virtual strategy state back to FLAT rather than tracking the never-opened new side.
        assertEquals(WtxRoutingOutcome.ROUTED_FLATTEN_ONLY, result.outcome());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().toLowerCase().contains("reversed to flat"),
                "errorMessage must explain the user was flattened, open leg skipped for margin");
        // Exactly one broker order — the close (flatten) leg. The open leg was NOT submitted.
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());
        // Prior LONG row is now EXIT_SUBMITTED — the flatten was sent.
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, repo.byId(priorLong.getId()).getStatus());
    }

    @Test
    void preflightGatesFullQty_whenPriorRowIsAlreadyExitSubmitted() {
        // Codex regression — handleEntry's REVERSE branch only fires a close leg when the prior
        // row is not already EXIT_SUBMITTED. When a flatten is in flight from a previous bar, no
        // margin will be released in time for the new open leg, so the delta subtraction would
        // underestimate the real margin draw. The preflight must gate on the FULL positionQty
        // in that state, not the delta.
        com.riskdesk.application.service.IbkrMarginPreflightService spy =
                org.mockito.Mockito.mock(com.riskdesk.application.service.IbkrMarginPreflightService.class);
        when(spy.canAffordOrder(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision.allow());
        WtxExecutionBridge bridgeWithPreflight = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, spy);

        // Prior LONG of 2 is already EXIT_SUBMITTED (close leg in flight from a previous bar).
        TradeExecutionRecord priorExiting = wtxRow("LONG", 2, ExecutionStatus.EXIT_SUBMITTED);
        repo.createIfAbsent(priorExiting);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(5), bd(1))
                .withConfiguredOrderQty(5);
        bridgeWithPreflight.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        // Preflight must see the FULL open qty (5), not the delta (3) — the EXIT_SUBMITTED prior
        // releases no margin in this call.
        verify(spy, times(1)).canAffordOrder(any(), any(),
                org.mockito.ArgumentMatchers.eq(5), any());
    }

    @Test
    void exitSubmittedPriorButIbkrStillHolds_sizesReverseAgainstLivePosition_skipsPreflight() {
        // Prod cascade fix: a previous reverse's close leg ack-pended (prior row EXIT_SUBMITTED)
        // but IBKR STILL holds the position. A new same-size REVERSE_TO_SHORT must size its margin
        // delta against the LIVE broker position (1 − 1 = 0) and SKIP the preflight. The old code
        // returned 0 for any EXIT_SUBMITTED prior → priorQty=0 → preflightQty=1 → the heuristic
        // ran and produced a false NO MARGIN (the exact 10:00 prod denial).
        com.riskdesk.application.service.IbkrMarginPreflightService spy =
                org.mockito.Mockito.mock(com.riskdesk.application.service.IbkrMarginPreflightService.class);
        when(spy.canAffordOrder(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision
                        .deny("would have denied if consulted"));
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(1))); // IBKR still long 1
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, spy, portfolio);

        // Prior LONG whose close is already in flight (ack-pending from a previous bar).
        TradeExecutionRecord priorExiting = wtxRow("LONG", 1, ExecutionStatus.EXIT_SUBMITTED);
        repo.createIfAbsent(priorExiting);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(1), bd(1))
                .withConfiguredOrderQty(1);
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        // delta = positionQty(1) − liveIbkrPos(1) = 0 → the preflight is NEVER consulted.
        verify(spy, never()).canAffordOrder(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        // Open leg proceeds (the prior close is already in flight, so no new close leg fires).
        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
    }

    @Test
    void preflightSkippedForSizeDecreasingReverse() {
        // Symmetric case: REVERSE that shrinks the position releases margin. Delta ≤ 0 → no
        // preflight consultation, order goes through.
        com.riskdesk.application.service.IbkrMarginPreflightService spy =
                org.mockito.Mockito.mock(com.riskdesk.application.service.IbkrMarginPreflightService.class);
        when(spy.canAffordOrder(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision
                        .deny("would have denied"));
        WtxExecutionBridge bridgeWithPreflight = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, spy);

        // Prior LONG of 5; new SHORT of 2 → delta = −3 → preflight skipped.
        repo.createIfAbsent(wtxRow("LONG", 5, ExecutionStatus.ACTIVE));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridgeWithPreflight.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        verify(spy, never()).canAffordOrder(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any());
        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
    }

    @Test
    void preflightStillGatesPureOpen_denialBlocksOrder() {
        // Counterpart: a plain OPEN must still respect the preflight at full positionQty.
        // Without this guard the production bug at 09:20Z (Equity < InitMargin on a true OPEN)
        // would resurface.
        com.riskdesk.application.service.IbkrMarginPreflightService spy =
                org.mockito.Mockito.mock(com.riskdesk.application.service.IbkrMarginPreflightService.class);
        when(spy.canAffordOrder(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision
                        .deny("Equity 5000 < est. InitMargin 6600"));
        WtxExecutionBridge bridgeWithPreflight = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, spy);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridgeWithPreflight.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        // Pure OPEN → preflight runs on full qty (2).
        verify(spy, times(1)).canAffordOrder(any(), any(),
                org.mockito.ArgumentMatchers.eq(2), any());
        assertEquals(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    // ── IBKR live-position reconcile ───────────────────────────────────────

    @Test
    void reconcile_ibkrAlreadySameSide_skipsAsDuplicate_butCreatesTrackingRowForFutureClose() {
        // IBKR already long 2 MCL; WTX wants OPEN_LONG → SKIPPED_DUPLICATE (no stacking).
        // But a tracking row MUST be created so a subsequent CLOSE_LONG can flatten the broker
        // position via the normal handleClose path — otherwise the live position would be
        // invisible to WTX and only exitable manually.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(2)));
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.SKIPPED_DUPLICATE, result.outcome());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().toLowerCase().contains("already long"));
        verify(ibkrOrderService, never()).submitEntryOrder(any());

        // Tracking row exists with the broker-side direction and IBKR qty.
        assertEquals(1, repo.all().size(), "duplicate-skip must still create a tracking row");
        TradeExecutionRecord tracked = repo.all().get(0);
        assertTrue(tracked.getExecutionKey().startsWith("wtx-track:MCL:10m:"),
                "tracking row uses the wtx-track: prefix scoped to (instrument, timeframe)");
        assertEquals("LONG", tracked.getAction());
        assertEquals(2, tracked.getQuantity());
        assertEquals(ExecutionStatus.ACTIVE, tracked.getStatus(),
                "tracked row must be ACTIVE so handleClose can locate it");
    }

    @Test
    void reconcile_sameSideDuplicateOnTwoSignals_onlyCreatesOneTrackingRow() {
        // Two duplicate OPEN_LONG signals while IBKR holds long → exactly one wtx-track: row.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(2)));
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridgeWithReconcile.submit(signal(WtxAction.OPEN_LONG), state, bd(100));
        // Different signalTs to bypass the executionKey de-dup at the top of handleEntry.
        WtxSignal later = new WtxSignal("MCL", "10m", WtxSignalType.COMPRA, "LONG",
                bd(1), bd(0), true, WtxAction.OPEN_LONG, WtxEnrichmentSnapshot.empty(),
                Instant.parse("2026-05-13T15:00:00Z"), null, null);
        bridgeWithReconcile.submit(later, state, bd(100));

        assertEquals(1, repo.all().size(), "tracking row must be idempotent across duplicate signals");
    }

    @Test
    void reconcile_priorTrackingRowTerminal_freshIbkrPositionGetsNewTrackingRow() {
        // Regression: a stable wtx-track:<i>:<tf> key would block a new ACTIVE row once the
        // previous tracking row went terminal (CLOSED / FAILED / CANCELLED / REJECTED). After a
        // restart or a manual re-open the bridge would silently lose the live position again.
        // Idempotency must be based on "any non-terminal WTX row" instead.
        TradeExecutionRecord terminalPrior = wtxRow("LONG", 2, ExecutionStatus.CLOSED);
        terminalPrior.setExecutionKey("wtx-track:MCL:10m:1700000000");
        repo.createIfAbsent(terminalPrior);

        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(3)));
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(3), bd(1));
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.SKIPPED_DUPLICATE, result.outcome());
        // Two rows now: the terminal prior + a fresh ACTIVE tracking row for the live IBKR position.
        assertEquals(2, repo.all().size(),
                "terminal prior tracking row must not block creation of a new ACTIVE row");
        long activeCount = repo.all().stream()
                .filter(r -> r.getStatus() == ExecutionStatus.ACTIVE)
                .filter(r -> r.getExecutionKey() != null && r.getExecutionKey().startsWith("wtx-track:MCL:10m:"))
                .count();
        assertEquals(1, activeCount, "exactly one fresh ACTIVE wtx-track row must exist");
    }

    @Test
    void reconcile_sameSideTrackingRow_isReachableByLaterClose() {
        // Round-trip: duplicate-skip creates the tracking row, then a CLOSE flattens it.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(2)));
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        WtxStrategyState openState = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridgeWithReconcile.submit(signal(WtxAction.OPEN_LONG), openState, bd(100));
        assertEquals(1, repo.all().size());

        // Now the strategy decides to close.
        WtxStrategyState closeState = flatState().withAutoExecution(true);
        WtxRoutingResult closeResult = bridgeWithReconcile.submit(signal(WtxAction.CLOSE_LONG), closeState, bd(105));

        assertEquals(WtxRoutingOutcome.ROUTED, closeResult.outcome());
        TradeExecutionRecord row = repo.all().get(0);
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, row.getStatus(),
                "tracked row must transition to EXIT_SUBMITTED via the standard close path");
        // The flatten order is a SELL of 2 contracts (the IBKR-tracked qty).
        verify(ibkrOrderService).submitEntryOrder(argThat(r ->
                "SHORT".equals(r.action()) && r.quantity() == 2));
    }

    @Test
    void reconcile_ibkrHoldsOppositeSide_upgradesOpenToReverse_synthesizesPriorRow() {
        // IBKR holds short 2 MCL but WTX has no local row; WTX wants OPEN_LONG → bridge must
        // upgrade to REVERSE_TO_LONG and synthesize a phantom prior row so the close leg flattens
        // the broker side before opening the new long.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(-2)));
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        // Two orders: one to flatten the IBKR short (BUY = "LONG"), one to open the new long.
        verify(ibkrOrderService, times(2)).submitEntryOrder(argThat(r ->
                "LONG".equals(r.action()) && r.quantity() == 2));
        // Two rows now: the synthesized reconcile prior row + the new open row.
        assertEquals(2, repo.all().size());
        TradeExecutionRecord synthesized = repo.all().stream()
                .filter(r -> r.getExecutionKey() != null && r.getExecutionKey().startsWith("wtx-reconcile:"))
                .findFirst().orElseThrow(() -> new AssertionError("synthesized prior row missing"));
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, synthesized.getStatus());
        assertEquals("SHORT", synthesized.getAction(),
                "synthesized row carries the IBKR-side direction (the side being flattened)");
    }

    @Test
    void reconcile_ibkrFlat_unchangedBehaviour_normalOpen() {
        // IBKR returns a snapshot with no matching positions; bridge behaves exactly as it would
        // without reconcile (single open-leg order, one row).
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MNQM6", bd(0))); // wrong symbol
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());
        assertEquals(1, repo.all().size());
    }

    @Test
    void reconcile_ibkrFlat_reverseWithStaleRow_downgradesToSingleOpen_noNakedCloseLeg() {
        // PROD REPRO (screenshot): WTX still thinks it holds a SHORT (stale ACTIVE row) and fires
        // REVERSE_TO_LONG, but the user already flattened the position manually at the broker so
        // IBKR is FLAT. The old code passed the REVERSE through → a NAKED close leg (BUY to flatten
        // a short that's gone) PLUS the open leg = TWO orders, one resting unfilled in limit.
        // Now: IBKR-flat downgrades REVERSE → OPEN, so exactly ONE order (the new long) is sent and
        // the stale row is reconciled to CANCELLED.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(0))); // IBKR flat
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        // Stale prior SHORT row — never closed in the app because the user flattened at the broker.
        TradeExecutionRecord stale = wtxRow("SHORT", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(stale);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(1), bd(1)) // virtual state already flipped to the new side
                .withConfiguredOrderQty(1);
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.REVERSE_TO_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        // EXACTLY ONE order — the open leg. No naked close leg.
        verify(ibkrOrderService, times(1)).submitEntryOrder(argThat(r ->
                "LONG".equals(r.action()) && r.quantity() == 1));
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());

        // Stale row reconciled to terminal so a later CLOSE can't target the phantom.
        TradeExecutionRecord staleAfter = repo.all().stream()
                .filter(r -> r.getId().equals(stale.getId())).findFirst().orElseThrow();
        assertEquals(ExecutionStatus.CANCELLED, staleAfter.getStatus(),
                "stale row must be voided when IBKR is confirmed flat");
        // Exactly one non-terminal row remains — the new open leg.
        long active = repo.all().stream()
                .filter(r -> r.getStatus() != ExecutionStatus.CANCELLED
                        && r.getStatus() != ExecutionStatus.CLOSED).count();
        assertEquals(1, active, "only the new OPEN row stays active after the downgrade");
    }

    @Test
    void reconcile_ibkrFlat_reverseWithNoRow_opensSingleLong() {
        // REVERSE_TO_LONG with IBKR flat and no local row (prior side opened while auto-exec was
        // OFF, so nothing ever reached the broker). Must open a single long — no close leg.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(0))); // IBKR flat
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(1), bd(1))
                .withConfiguredOrderQty(1);
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.REVERSE_TO_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        verify(ibkrOrderService, times(1)).submitEntryOrder(argThat(r ->
                "LONG".equals(r.action()) && r.quantity() == 1));
        assertEquals(1, repo.all().size(), "single OPEN row, no synthesized/naked close leg");
    }

    @Test
    void close_ibkrFlat_skipsNakedFlatten_andVoidsStaleRow() {
        // CLOSE path (MAX_LOSS / NY-force / trailing): WTX has an ACTIVE long row but IBKR is flat
        // (manual close). A flatten now would be a NAKED SELL that opens an unintended short.
        // Must skip the order entirely and void the stale row.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(0))); // IBKR flat
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        repo.createIfAbsent(wtxRow("LONG", 2, ExecutionStatus.ACTIVE));

        WtxStrategyState state = flatState().withAutoExecution(true); // service already flattened virtual state
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.CLOSE_LONG), state, bd(105));

        assertEquals(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
        assertEquals(ExecutionStatus.CANCELLED, repo.all().get(0).getStatus(),
                "stale row must be voided — never flattened naked");
    }

    @Test
    void reconcile_snapshotUnavailable_reverseUnchanged_legacyTwoLegs() {
        // Snapshot unavailable (portfolio read returns null) → can't reconcile, so the REVERSE keeps
        // its legacy behaviour: flatten the WTX-tracked prior side, then open the new one (two legs).
        // Guards that ONLY the confirmed-flat path changed, not the unknown-snapshot path.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(null); // snapshot unavailable → livePos null
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        repo.createIfAbsent(wtxRow("SHORT", 1, ExecutionStatus.ACTIVE));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(1), bd(1))
                .withConfiguredOrderQty(1);
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.REVERSE_TO_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        // Two legs: close the tracked short (BUY) + open the new long (BUY).
        verify(ibkrOrderService, times(2)).submitEntryOrder(argThat(r ->
                "LONG".equals(r.action()) && r.quantity() == 1));
    }

    @Test
    void reconcile_ibkrFlat_inFlightEntryRow_skipsOpen_noVoidNoSecondOrder() {
        // Codex P1 (follow-up): when IBKR reads flat ONLY because a prior entry is still resting
        // UNFILLED (ENTRY_SUBMITTED), the bridge must NOT void that row AND must NOT open another —
        // otherwise the first resting entry and the new one can both fill → double exposure, two
        // non-terminal rows on one panel. Skip and let the in-flight order resolve first.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(0))); // entry unfilled → IBKR flat
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        TradeExecutionRecord inFlight = wtxRow("LONG", 1, ExecutionStatus.ENTRY_SUBMITTED);
        repo.createIfAbsent(inFlight);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(1), bd(1))
                .withConfiguredOrderQty(1);
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        assertEquals(WtxRoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT, result.outcome());
        // No second order — the open is skipped while the entry is in flight.
        verify(ibkrOrderService, never()).submitEntryOrder(any());
        // In-flight entry row preserved (NOT voided) and no new row created.
        assertEquals(1, repo.all().size(), "no new open row while an entry is in flight");
        TradeExecutionRecord after = repo.all().get(0);
        assertEquals(inFlight.getId(), after.getId());
        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, after.getStatus(),
                "in-flight ENTRY_SUBMITTED row must be preserved, not voided");
    }

    @Test
    void close_ibkrFlat_inFlightEntryRow_skipsFlatten_noNakedOrder_rowPreserved() {
        // Codex P1 (CLOSE symmetry): a CLOSE / MAX_LOSS / force-close against an ENTRY_SUBMITTED
        // (unfilled) row must NOT send a flatten — IBKR reads flat only because the entry hasn't
        // filled, so the opposite-side order would be NAKED and could open an unintended position.
        // Skip WITHOUT voiding (the resting order is still live & tracked), returning
        // SKIPPED_ENTRY_IN_FLIGHT so the caller keeps the position side.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(0)));
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        repo.createIfAbsent(wtxRow("LONG", 1, ExecutionStatus.ENTRY_SUBMITTED));

        WtxStrategyState state = flatState().withAutoExecution(true);
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.CLOSE_LONG), state, bd(105));

        assertEquals(WtxRoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, repo.all().get(0).getStatus(),
                "in-flight entry row must be preserved — not voided, not flattened");
    }

    @Test
    void reconcile_offsettingLegsNetZero_notTreatedAsFlat_reverseKeepsTwoLegs() {
        // Codex P1: rollover/calendar overlap holds +1 MCLM6 and -1 MCLU6 — these net to zero but are
        // LIVE legs. Must NOT be read as confirmed-flat: a REVERSE keeps its normal two-leg behaviour
        // (close the tracked leg + open the new side), not the flat downgrade to a single open, and the
        // local row is never voided as a phantom.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWithLegs(
                new String[]{"MCLM6", "MCLU6"}, new BigDecimal[]{bd(1), bd(-1)})); // net 0, both live
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        repo.createIfAbsent(wtxRow("LONG", 1, ExecutionStatus.ACTIVE)); // WTX tracks the front-month long leg

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.SHORT, bd(100), bd(1), bd(1))
                .withConfiguredOrderQty(1);
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.REVERSE_TO_SHORT), state, bd(100));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        // Two legs (close tracked long + open new short) — NOT downgraded to one open.
        verify(ibkrOrderService, times(2)).submitEntryOrder(any());
        long voided = repo.all().stream().filter(r -> r.getStatus() == ExecutionStatus.CANCELLED).count();
        assertEquals(0, voided, "offsetting live legs must not be voided as confirmed-flat drift");
    }

    @Test
    void close_offsettingLegsNetZero_notTreatedAsFlat_flattensTrackedLeg() {
        // CLOSE counterpart: offsetting live legs (net 0) must not hit the confirmed-flat void/skip —
        // run the normal close path and flatten the WTX-tracked leg.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWithLegs(
                new String[]{"MCLM6", "MCLU6"}, new BigDecimal[]{bd(1), bd(-1)}));
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        repo.createIfAbsent(wtxRow("LONG", 1, ExecutionStatus.ACTIVE));

        WtxStrategyState state = flatState().withAutoExecution(true);
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.CLOSE_LONG), state, bd(105));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        verify(ibkrOrderService, times(1)).submitEntryOrder(any()); // the flatten of the tracked leg
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, repo.all().get(0).getStatus(),
                "tracked leg is flattened normally, not voided as confirmed-flat");
    }

    @Test
    void reconcile_scopesPortfolioReadToConfiguredBrokerAccount() {
        // wtx.broker-account-id is configured to "DU777"; the bridge must request that account
        // from the portfolio service AND filter out positions belonging to other accounts so a
        // multi-account gateway can't trigger cross-account duplicate-skips.
        wtxProperties.setBrokerAccountId("DU777");

        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        // Snapshot mixes a same-symbol long position on a DIFFERENT account (DU111) — the bridge
        // must ignore it and submit a normal OPEN order on DU777.
        IbkrPositionView otherAccountPos = new IbkrPositionView(
                "DU111", 99999L, "MCLM6", "FUT",
                bd(2), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD");
        IbkrPortfolioSnapshot snap = new IbkrPortfolioSnapshot(
                true, "DU777", List.of(), bd(10000), bd(2000), bd(8000),
                bd(8000), bd(0), bd(0), bd(0), "USD", List.of(otherAccountPos), null);
        when(portfolio.getPortfolio(any())).thenReturn(snap);

        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        // Portfolio must have been queried for the configured account (not null).
        verify(portfolio).getPortfolio(org.mockito.ArgumentMatchers.eq("DU777"));
        // The other-account position must NOT trigger a duplicate-skip — a normal OPEN goes through.
        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());
    }

    @Test
    void reconcile_defaultPlaceholderAccount_usesGatewayDefault() {
        // brokerAccountId stays at the "wtx-default" placeholder — reconcile passes null to the
        // portfolio service (legacy "let the gateway pick" behaviour) and does not filter by
        // account on the way out.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MCLM6", bd(2)));
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridgeWithReconcile.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        verify(portfolio).getPortfolio(org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void reconcile_portfolioUnavailable_failsOpen_behavesLikeLegacy() {
        // Portfolio query throws — bridge must log + fall back to legacy behaviour, NOT block the order.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenThrow(new RuntimeException("ibkr disconnected"));
        WtxExecutionBridge bridgeWithReconcile = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, null, portfolio);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridgeWithReconcile.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());
    }

    // ── configurable order qty ─────────────────────────────────────────────

    @Test
    void configuredOrderQty_drivesBrokerSubmissionQuantity() {
        // The panel qty input — when set to 5 — must drive both the execution row size and the
        // IBKR order submission size.
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(5), bd(1))
                .withConfiguredOrderQty(5);
        bridge.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        verify(ibkrOrderService).submitEntryOrder(argThat(r ->
                "LONG".equals(r.action()) && r.quantity() == 5));
        assertEquals(5, repo.all().get(0).getQuantity());
    }

    @Test
    void preflightAllows_routesNormally_brokerCallOccurs() {
        com.riskdesk.application.service.IbkrMarginPreflightService stub =
                org.mockito.Mockito.mock(com.riskdesk.application.service.IbkrMarginPreflightService.class);
        when(stub.canAffordOrder(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision.allow());
        WtxExecutionBridge bridgeWithPreflight = new WtxExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, wtxProperties, stub);

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        WtxRoutingResult result = bridgeWithPreflight.submit(signal(WtxAction.OPEN_LONG), state, bd(100));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        verify(ibkrOrderService, times(1)).submitEntryOrder(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private WtxSignal signal(WtxAction action) {
        return signal(action, "10m");
    }

    private WtxSignal signal(WtxAction action, String timeframe) {
        return new WtxSignal("MCL", timeframe, WtxSignalType.COMPRA, "LONG",
                bd(1), bd(0), true, action, WtxEnrichmentSnapshot.empty(),
                Instant.parse("2026-05-13T14:00:00Z"), null, null);
    }

    private WtxStrategyState flatState() {
        return WtxStrategyState.initial("MCL", "10m", bd(10_000));
    }

    private TradeExecutionRecord wtxRow(String action, int qty, ExecutionStatus status) {
        return wtxRow(action, qty, status, "10m");
    }

    private TradeExecutionRecord wtxRow(String action, int qty, ExecutionStatus status, String timeframe) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setExecutionKey("wtx:MCL:" + timeframe + ":1:OPEN");
        r.setInstrument("MCL");
        r.setTimeframe(timeframe);
        r.setAction(action);
        r.setQuantity(qty);
        r.setTriggerSource(ExecutionTriggerSource.WTX_AUTO);
        r.setStatus(status);
        r.setNormalizedEntryPrice(bd(100));
        r.setBrokerAccountId("wtx-default");
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    /** Single-position IBKR portfolio snapshot — sized for the reconcile tests. */
    private static IbkrPortfolioSnapshot snapshotWith(String contractDesc, BigDecimal position) {
        IbkrPositionView pos = new IbkrPositionView(
                "DU123", 12345L, contractDesc, "FUT",
                position, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD");
        return new IbkrPortfolioSnapshot(
                true, "DU123", List.of(), bd(10000), bd(2000), bd(8000),
                bd(8000), bd(0), bd(0), bd(0), "USD", List.of(pos), null);
    }

    /** Multi-leg IBKR portfolio snapshot — for offsetting rollover/calendar reconcile tests. */
    private static IbkrPortfolioSnapshot snapshotWithLegs(String[] contractDescs, BigDecimal[] positions) {
        List<IbkrPositionView> legs = new ArrayList<>();
        for (int i = 0; i < contractDescs.length; i++) {
            legs.add(new IbkrPositionView(
                    "DU123", 10000L + i, contractDescs[i], "FUT",
                    positions[i], BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD"));
        }
        return new IbkrPortfolioSnapshot(
                true, "DU123", List.of(), bd(10000), bd(2000), bd(8000),
                bd(8000), bd(0), bd(0), bd(0), "USD", legs, null);
    }

    /** Minimal in-memory TradeExecutionRepositoryPort for bridge unit tests. */
    private static final class FakeRepo implements TradeExecutionRepositoryPort {
        private final java.util.Map<Long, TradeExecutionRecord> byId = new java.util.LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        List<TradeExecutionRecord> all() { return new ArrayList<>(byId.values()); }
        TradeExecutionRecord byId(Long id) { return byId.get(id); }

        @Override public TradeExecutionRecord createIfAbsent(TradeExecutionRecord e) {
            if (e.getExecutionKey() != null) {
                for (TradeExecutionRecord r : byId.values()) {
                    if (e.getExecutionKey().equals(r.getExecutionKey())) return r;
                }
            }
            if (e.getId() == null) e.setId(seq.getAndIncrement());
            byId.put(e.getId(), e);
            return e;
        }

        @Override public TradeExecutionRecord save(TradeExecutionRecord e) {
            if (e.getId() == null) e.setId(seq.getAndIncrement());
            byId.put(e.getId(), e);
            return e;
        }

        @Override public Optional<TradeExecutionRecord> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }
        @Override public Optional<TradeExecutionRecord> findByIdForUpdate(Long id) { return findById(id); }
        @Override public Optional<TradeExecutionRecord> findByMentorSignalReviewId(Long id) { return Optional.empty(); }
        @Override public List<TradeExecutionRecord> findByMentorSignalReviewIds(Collection<Long> ids) { return List.of(); }
        @Override public Optional<TradeExecutionRecord> findByIbkrOrderId(Integer id) { return Optional.empty(); }

        @Override public Optional<TradeExecutionRecord> findByExecutionKey(String key) {
            return byId.values().stream().filter(r -> key.equals(r.getExecutionKey())).findFirst();
        }

        @Override public Optional<TradeExecutionRecord> findActiveByInstrument(String instrument) {
            return byId.values().stream()
                    .filter(r -> instrument.equals(r.getInstrument()))
                    .filter(r -> !terminal(r.getStatus()))
                    .reduce((a, b) -> b);
        }

        @Override public Optional<TradeExecutionRecord> findActiveByInstrumentAndTriggerSource(
                String instrument, ExecutionTriggerSource src) {
            return byId.values().stream()
                    .filter(r -> instrument.equals(r.getInstrument()))
                    .filter(r -> r.getTriggerSource() == src)
                    .filter(r -> !terminal(r.getStatus()))
                    .reduce((a, b) -> b);
        }

        @Override public Optional<TradeExecutionRecord> findActiveByInstrumentAndTimeframeAndTriggerSource(
                String instrument, String timeframe, ExecutionTriggerSource src) {
            return byId.values().stream()
                    .filter(r -> instrument.equals(r.getInstrument()))
                    .filter(r -> timeframe.equals(r.getTimeframe()))
                    .filter(r -> r.getTriggerSource() == src)
                    .filter(r -> !terminal(r.getStatus()))
                    .reduce((a, b) -> b);
        }

        @Override public List<TradeExecutionRecord> findPendingByTriggerSource(ExecutionTriggerSource src) {
            return List.of();
        }
        @Override public List<TradeExecutionRecord> findByTriggerSourceAndStatus(ExecutionTriggerSource src, ExecutionStatus status) {
            return byId.values().stream()
                .filter(r -> r.getTriggerSource() == src && r.getStatus() == status)
                .toList();
        }
        @Override public List<TradeExecutionRecord> findAllActive() {
            return byId.values().stream().filter(r -> !terminal(r.getStatus())).toList();
        }

        private static boolean terminal(ExecutionStatus s) {
            return s == ExecutionStatus.CLOSED || s == ExecutionStatus.CANCELLED
                    || s == ExecutionStatus.REJECTED || s == ExecutionStatus.FAILED;
        }
    }
}

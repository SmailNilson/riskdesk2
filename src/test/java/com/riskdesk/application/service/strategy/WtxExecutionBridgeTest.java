package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.engine.strategy.wtx.WtxAction;
import com.riskdesk.domain.engine.strategy.wtx.WtxEnrichmentSnapshot;
import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignalType;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        // The flatten order is a SHORT (sell) of the original 2 contracts
        verify(ibkrOrderService).submitEntryOrder(argThat(r ->
                "SHORT".equals(r.action()) && r.quantity() == 2));
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
    void reverseToLong_retiresPriorRow_andOpensNewLongRow_withDoubledOrderQty() {
        // Seed an open WTX short row that the reverse must flatten
        TradeExecutionRecord priorShort = wtxRow("SHORT", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorShort);

        // After applyAction the service hands the bridge the NEW long position state
        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridge.submit(signal(WtxAction.REVERSE_TO_LONG), state, bd(100));

        assertEquals(2, repo.all().size());
        TradeExecutionRecord prior = repo.byId(priorShort.getId());
        // Prior row is terminally CLOSED: the reverse is one broker order tracked on the NEW
        // row, so the prior row can never get its own fill callback — a non-terminal status
        // would strand it. Closed only AFTER the reverse order was accepted.
        assertEquals(ExecutionStatus.CLOSED, prior.getStatus(),
                "prior short row must be terminally closed by the accepted reverse");
        assertNotNull(prior.getClosedAt());

        TradeExecutionRecord fresh = repo.all().stream()
                .filter(r -> r.getStatus() == ExecutionStatus.ENTRY_SUBMITTED)
                .findFirst().orElseThrow();
        assertEquals("LONG", fresh.getAction());
        assertEquals(2, fresh.getQuantity(), "row quantity is the resulting position size");
        assertEquals(999, fresh.getIbkrOrderId(), "new row carries the reverse order id for fill tracking");
        // IBKR order is doubled: flatten 2 short + open 2 long = 4
        verify(ibkrOrderService).submitEntryOrder(argThat(r ->
                "LONG".equals(r.action()) && r.quantity() == 4));
    }

    @Test
    void reverseToLong_submissionFails_leavesPriorRowUntouched() {
        TradeExecutionRecord priorShort = wtxRow("SHORT", 2, ExecutionStatus.ACTIVE);
        repo.createIfAbsent(priorShort);
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IllegalStateException("IBKR rejected"));

        WtxStrategyState state = flatState().withAutoExecution(true)
                .withPosition(WtxPosition.LONG, bd(100), bd(2), bd(1));
        bridge.submit(signal(WtxAction.REVERSE_TO_LONG), state, bd(100));

        // Prior row must stay ACTIVE — the live position keeps its execution row so the
        // next bar can retry the reverse. The new row records the failed attempt.
        TradeExecutionRecord prior = repo.byId(priorShort.getId());
        assertEquals(ExecutionStatus.ACTIVE, prior.getStatus(),
                "prior row must NOT be retired when the reverse submission fails");
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
        bridge.submit(sig, state, bd(100));   // first reverse goes through
        bridge.submit(sig, state, bd(100));   // duplicate signalTs+action → executionKey collision

        // The duplicate is skipped: no third row, exactly one IBKR submission.
        assertEquals(2, repo.all().size(), "duplicate reverse must not create a third row");
        verify(ibkrOrderService).submitEntryOrder(any());
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

    // ── helpers ────────────────────────────────────────────────────────────

    private WtxSignal signal(WtxAction action) {
        return new WtxSignal("MCL", "10m", WtxSignalType.COMPRA, "LONG",
                bd(1), bd(0), true, action, WtxEnrichmentSnapshot.empty(),
                Instant.parse("2026-05-13T14:00:00Z"));
    }

    private WtxStrategyState flatState() {
        return WtxStrategyState.initial("MCL", bd(10_000));
    }

    private TradeExecutionRecord wtxRow(String action, int qty, ExecutionStatus status) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setExecutionKey("wtx:MCL:1:OPEN");
        r.setInstrument("MCL");
        r.setTimeframe("10m");
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

        @Override public List<TradeExecutionRecord> findPendingByTriggerSource(ExecutionTriggerSource src) {
            return List.of();
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

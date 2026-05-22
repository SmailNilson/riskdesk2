package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrMarginPreflightService;
import com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookProfile;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookSignal;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookStrategyState;
import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.config.PlaybookStrategyProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class PlaybookExecutionBridgeTest {

    private FakeRepo repo;
    private IbkrOrderService ibkrOrderService;
    private IbkrProperties ibkrProperties;
    private PlaybookStrategyProperties playbookProperties;
    private IbkrMarginPreflightService marginPreflight;
    private PlaybookExecutionBridge bridge;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        ibkrOrderService = mock(IbkrOrderService.class);
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenReturn(new BrokerEntryOrderSubmission(1001L, "Submitted", "ref", Instant.now()));
        ibkrProperties = new IbkrProperties();
        ibkrProperties.setEnabled(true);
        playbookProperties = new PlaybookStrategyProperties();
        playbookProperties.setEnabled(true);
        playbookProperties.setBrokerAccountId("playbook-test-acc");
        marginPreflight = mock(IbkrMarginPreflightService.class);
        when(marginPreflight.canAffordOrder(any(), any(), anyInt(), any()))
                .thenReturn(PreflightDecision.allow());

        bridge = new PlaybookExecutionBridge(
                ibkrOrderService, repo, ibkrProperties, playbookProperties, marginPreflight
        );
    }

    @Test
    void submitEntry_autoExecutionDisabled_returnsSkippedAutoOff() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(false);
        PlaybookSignal signal = sampleSignal();

        WtxRoutingResult result = bridge.submitEntry(signal, state);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_AUTO_OFF, result.outcome());
        assertTrue(repo.all().isEmpty());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void submitEntry_ibkrDisabled_returnsSkippedIbkrDisabled() {
        ibkrProperties.setEnabled(false);
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);
        PlaybookSignal signal = sampleSignal();

        WtxRoutingResult result = bridge.submitEntry(signal, state);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_IBKR_DISABLED, result.outcome());
        assertTrue(repo.all().isEmpty());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void submitEntry_duplicateExecutionKey_returnsSkippedDuplicate() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);
        PlaybookSignal signal = sampleSignal();

        // Seed duplicate row
        TradeExecutionRecord row = new TradeExecutionRecord();
        row.setExecutionKey("playbook:MCL:10m:" + signal.id());
        repo.save(row);

        WtxRoutingResult result = bridge.submitEntry(signal, state);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_DUPLICATE, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void submitEntry_missingReferencePrice_returnsSkippedNoPrice() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);
        PlaybookSignal signal = new PlaybookSignal(
                UUID.randomUUID(), "MCL", "10m", Instant.now(), "LONG", 5, "ZONE_RETEST",
                null, bd(45), bd(52), bd(53), null, null
        );

        WtxRoutingResult result = bridge.submitEntry(signal, state);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_NO_PRICE, result.outcome());
        assertTrue(repo.all().isEmpty());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void submitEntry_preflightDenies_returnsSkippedInsufficientMargin() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true)
                .withConfiguredOrderQty(3);
        PlaybookSignal signal = sampleSignal();

        when(marginPreflight.canAffordOrder(any(), any(), anyInt(), any()))
                .thenReturn(PreflightDecision.deny("Denied by preflight"));

        WtxRoutingResult result = bridge.submitEntry(signal, state);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, result.outcome());
        assertEquals("Denied by preflight", result.errorMessage());
        assertTrue(repo.all().isEmpty());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void submitEntry_preflightAllows_routesNormally() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true)
                .withConfiguredOrderQty(3);
        PlaybookSignal signal = sampleSignal();

        WtxRoutingResult result = bridge.submitEntry(signal, state);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());
        assertEquals(1, repo.all().size());

        TradeExecutionRecord row = repo.all().get(0);
        assertEquals("LONG", row.getAction());
        assertEquals(3, row.getQuantity());
        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        assertEquals(1001, row.getIbkrOrderId());
        assertEquals(ExecutionTriggerSource.PLAYBOOK_AUTO, row.getTriggerSource());

        verify(ibkrOrderService).submitEntryOrder(argThat(r ->
                "LONG".equals(r.action()) && r.quantity() == 3 && r.limitPrice().compareTo(bd(50.00)) == 0
        ));
    }

    @Test
    void submitEntry_brokerRejectsInsufficientMargin_returnsSkippedInsufficientMargin_andUpdatesStatus() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);
        PlaybookSignal signal = sampleSignal();

        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN, 201, "Margin violation", "Margin violation"
                ));

        WtxRoutingResult result = bridge.submitEntry(signal, state);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, result.outcome());
        assertEquals(1, repo.all().size());

        TradeExecutionRecord row = repo.all().get(0);
        assertEquals(ExecutionStatus.PENDING_ENTRY_SUBMISSION, row.getStatus());
        assertTrue(row.getStatusReason().contains("insufficient margin"));
    }

    @Test
    void submitEntry_brokerTimeoutWithOrderId_returnsAckPending() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);
        PlaybookSignal signal = sampleSignal();

        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.TIMEOUT, null, "Timeout msg", "Timeout msg", 9988L
                ));

        WtxRoutingResult result = bridge.submitEntry(signal, state);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.ACK_PENDING, result.outcome());
        assertEquals(1, repo.all().size());

        TradeExecutionRecord row = repo.all().get(0);
        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, row.getStatus());
        assertEquals(9988, row.getIbkrOrderId());
        assertTrue(row.getStatusReason().contains("acknowledgement pending"));
    }

    @Test
    void submitEntry_brokerTimeoutWithoutOrderId_returnsFailedTimeout() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);
        PlaybookSignal signal = sampleSignal();

        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.TIMEOUT, null, "Timeout msg", "Timeout msg"
                ));

        WtxRoutingResult result = bridge.submitEntry(signal, state);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.FAILED_TIMEOUT, result.outcome());
        assertEquals(1, repo.all().size());

        TradeExecutionRecord row = repo.all().get(0);
        assertEquals(ExecutionStatus.PENDING_ENTRY_SUBMISSION, row.getStatus());
        assertTrue(row.getStatusReason().contains("timeout"));
    }

    @Test
    void submitEntry_brokerReject_returnsFailedBrokerReject_rowMarkedFailed() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);
        PlaybookSignal signal = sampleSignal();

        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.BROKER_REJECT, null, "Cancelled", "Cancelled"
                ));

        WtxRoutingResult result = bridge.submitEntry(signal, state);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.FAILED_BROKER_REJECT, result.outcome());
        assertEquals(1, repo.all().size());

        TradeExecutionRecord row = repo.all().get(0);
        assertEquals(ExecutionStatus.FAILED, row.getStatus());
        assertTrue(row.getStatusReason().contains("rejected"));
    }

    @Test
    void submitClose_autoExecutionDisabled_returnsSkippedAutoOff() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(false);

        WtxRoutingResult result = bridge.submitClose(state, bd(50.0));

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_AUTO_OFF, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void submitClose_ibkrDisabled_returnsSkippedIbkrDisabled() {
        ibkrProperties.setEnabled(false);
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);

        WtxRoutingResult result = bridge.submitClose(state, bd(50.0));

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_IBKR_DISABLED, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void submitClose_noOpenRow_returnsSkippedNoOpenRow() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);

        WtxRoutingResult result = bridge.submitClose(state, bd(50.0));

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void submitClose_duplicateOrAlreadyExitSubmitted_returnsSkippedDuplicate() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);

        TradeExecutionRecord activeRow = playbookRow("LONG", 2, ExecutionStatus.EXIT_SUBMITTED);
        repo.save(activeRow);

        WtxRoutingResult result = bridge.submitClose(state, bd(50.0));

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_DUPLICATE, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void submitClose_missingPrice_returnsSkippedNoPrice() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);

        TradeExecutionRecord activeRow = playbookRow("LONG", 2, ExecutionStatus.ACTIVE);
        activeRow.setNormalizedEntryPrice(null); // Price is null on active row and none passed as parameter
        repo.save(activeRow);

        WtxRoutingResult result = bridge.submitClose(state, null);

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_NO_PRICE, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void submitClose_routesNormally() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);

        TradeExecutionRecord activeRow = playbookRow("LONG", 2, ExecutionStatus.ACTIVE);
        repo.save(activeRow);

        WtxRoutingResult result = bridge.submitClose(state, bd(51.25));

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome());

        TradeExecutionRecord updated = repo.byId(activeRow.getId());
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, updated.getStatus());
        assertEquals(1001, updated.getIbkrOrderId());

        verify(ibkrOrderService).submitEntryOrder(argThat(r ->
                "SHORT".equals(r.action()) && r.quantity() == 2 && r.limitPrice().compareTo(bd(51.25)) == 0
        ));
    }

    @Test
    void submitClose_brokerRejectsMargin_returnsSkippedInsufficientMargin() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);

        TradeExecutionRecord activeRow = playbookRow("LONG", 2, ExecutionStatus.ACTIVE);
        repo.save(activeRow);

        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN, 201, "Margin error", "Margin error"
                ));

        WtxRoutingResult result = bridge.submitClose(state, bd(51.25));

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, result.outcome());

        TradeExecutionRecord updated = repo.byId(activeRow.getId());
        assertEquals(ExecutionStatus.ACTIVE, updated.getStatus()); // Stays ACTIVE
        assertTrue(updated.getStatusReason().contains("failed: Margin error"));
    }

    @Test
    void submitClose_brokerTimeoutWithOrderId_returnsAckPending() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true);

        TradeExecutionRecord activeRow = playbookRow("LONG", 2, ExecutionStatus.ACTIVE);
        repo.save(activeRow);

        when(ibkrOrderService.submitEntryOrder(any()))
                .thenThrow(new IbkrOrderRejectionException(
                        IbkrOrderRejectionException.Kind.TIMEOUT, null, "Timeout msg", "Timeout msg", 8877L
                ));

        WtxRoutingResult result = bridge.submitClose(state, bd(51.25));

        assertNotNull(result);
        assertEquals(WtxRoutingOutcome.ACK_PENDING, result.outcome());

        TradeExecutionRecord updated = repo.byId(activeRow.getId());
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, updated.getStatus());
        assertEquals(8877, updated.getIbkrOrderId());
        assertTrue(updated.getStatusReason().contains("acknowledgement pending"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private PlaybookSignal sampleSignal() {
        return new PlaybookSignal(
                UUID.randomUUID(), "MCL", "10m", Instant.now(), "LONG", 5, "ZONE_RETEST",
                bd(50.00), bd(45), bd(52), bd(53), null, null
        );
    }

    private TradeExecutionRecord playbookRow(String action, int qty, ExecutionStatus status) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setExecutionKey("playbook:MCL:10m:mock-sig-id");
        r.setInstrument("MCL");
        r.setTimeframe("10m");
        r.setAction(action);
        r.setQuantity(qty);
        r.setTriggerSource(ExecutionTriggerSource.PLAYBOOK_AUTO);
        r.setStatus(status);
        r.setNormalizedEntryPrice(bd(50.0));
        r.setBrokerAccountId("playbook-test-acc");
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
        @Override public List<TradeExecutionRecord> findAllActive() {
            return byId.values().stream().filter(r -> !terminal(r.getStatus())).toList();
        }

        private static boolean terminal(ExecutionStatus s) {
            return s == ExecutionStatus.CLOSED || s == ExecutionStatus.CANCELLED
                    || s == ExecutionStatus.REJECTED || s == ExecutionStatus.FAILED;
        }
    }
}

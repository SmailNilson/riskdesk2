package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.config.WtxRsiStrategyProperties;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stuck-close dead-lock regression for the WTX-RSI bridge — mirrors
 * {@code WtxExecutionBridgeTest#closeShort_stuckExitSubmitted_*} (PR #409).
 *
 * <p>A marketable close that gapped out of the book (or whose ack / fill callback was dropped) can leave
 * the execution row stuck in {@code EXIT_SUBMITTED} while IBKR STILL holds the position. The old
 * unconditional duplicate-skip then dead-locked the instrument: every later CLOSE returned
 * {@code SKIPPED_DUPLICATE} here and every same-side OPEN returned {@code SKIPPED_DUPLICATE} from the
 * entry reconcile, so the position could be neither exited nor reversed and bled (the "NON EXÉCUTÉ /
 * DUPLICATE" rows). {@code submitClose} now consults broker truth and re-fires a fresh marketable close
 * once the row is past the grace AND IBKR confirms the position is still open — conservatively skipping
 * within the grace, when broker truth is unavailable / flat, or when the retry is disabled.
 */
class IbkrWtxRsiExecutionBridgeTest {

    private FakeRepo repo;
    private IbkrOrderService ibkrOrderService;
    private IbkrProperties ibkrProperties;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        ibkrOrderService = mock(IbkrOrderService.class);
        // Default: the broker leaves the (re-fired) close resting → row stays EXIT_SUBMITTED.
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenReturn(new BrokerEntryOrderSubmission(999L, "Submitted", "ref", Instant.now()));
        ibkrProperties = new IbkrProperties();
        ibkrProperties.setEnabled(true);
    }

    @Test
    void closeShort_stuckExitSubmitted_ibkrStillHolds_refiresFreshMarketableClose() {
        // A prior marketable close gapped out / lost its fill callback, so the row is stuck EXIT_SUBMITTED
        // while IBKR STILL holds the short. Without a retry every later CLOSE returned SKIPPED_DUPLICATE and
        // every same-side OPEN returned SKIPPED_DUPLICATE from the reconcile — the position could be neither
        // exited nor reversed and bled. Once the close is past the grace AND IBKR confirms the position is
        // still open, re-fire a fresh flatten.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MNQM6", bd(-2))); // IBKR still SHORT 2
        IbkrWtxRsiExecutionBridge bridge = bridgeWith(portfolio, new WtxRsiStrategyProperties());

        TradeExecutionRecord stuck = wtxrsiRow("SHORT", 2, ExecutionStatus.EXIT_SUBMITTED);
        stuck.setExitSubmittedAt(Instant.now().minusSeconds(120)); // past the 45s default grace
        repo.createIfAbsent(stuck);

        WtxRoutingResult result = bridge.submitClose(
                shortState(), WtxRsiSignalRecord.Action.CLOSE_SHORT, bd(30050));

        assertEquals(WtxRoutingOutcome.ROUTED, result.outcome(), "stuck close must be re-fired, not skipped");
        // A fresh flatten is submitted — a BUY ("LONG") to cover the short, at the close quantity.
        verify(ibkrOrderService, times(1)).submitEntryOrder(argThat(r ->
                "LONG".equals(r.action()) && r.quantity() == 2));
        assertEquals(ExecutionStatus.EXIT_SUBMITTED, repo.byId(stuck.getId()).getStatus(),
                "the re-fired close leaves the row non-terminal until its own fill reconciles");
    }

    @Test
    void closeShort_freshExitSubmitted_ibkrStillHolds_skipsDuplicateWithinGrace() {
        // Within the grace window the marketable close is genuinely in flight (fills in seconds) — a fresh
        // close must NOT be double-submitted on top of it, even though IBKR still shows the position.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MNQM6", bd(-2)));
        IbkrWtxRsiExecutionBridge bridge = bridgeWith(portfolio, new WtxRsiStrategyProperties());

        TradeExecutionRecord fresh = wtxrsiRow("SHORT", 2, ExecutionStatus.EXIT_SUBMITTED);
        fresh.setExitSubmittedAt(Instant.now()); // just submitted — inside the grace
        repo.createIfAbsent(fresh);

        WtxRoutingResult result = bridge.submitClose(
                shortState(), WtxRsiSignalRecord.Action.CLOSE_SHORT, bd(30050));

        assertEquals(WtxRoutingOutcome.SKIPPED_DUPLICATE, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void closeShort_stuckExitSubmitted_ibkrFlat_skipsNoNakedRefire() {
        // EXIT_SUBMITTED but IBKR is flat → the close already completed (a lost fill callback). We must NOT
        // re-fire a naked flatten; the flat-but-stuck row is finalized to CLOSED out-of-band by the
        // StaleCloseReconciler. Stays a duplicate-skip here.
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("UNRELATED", bd(3))); // no MNQ leg → flat
        IbkrWtxRsiExecutionBridge bridge = bridgeWith(portfolio, new WtxRsiStrategyProperties());

        TradeExecutionRecord stuck = wtxrsiRow("SHORT", 2, ExecutionStatus.EXIT_SUBMITTED);
        stuck.setExitSubmittedAt(Instant.now().minusSeconds(120));
        repo.createIfAbsent(stuck);

        WtxRoutingResult result = bridge.submitClose(
                shortState(), WtxRsiSignalRecord.Action.CLOSE_SHORT, bd(30050));

        assertEquals(WtxRoutingOutcome.SKIPPED_DUPLICATE, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void closeShort_stuckExitSubmitted_retryDisabled_skipsDuplicate() {
        // Kill-switch: riskdesk.wtxrsi.stale-close-retry-seconds = 0 restores the legacy skip-only behaviour.
        WtxRsiStrategyProperties noRetry = new WtxRsiStrategyProperties();
        noRetry.setStaleCloseRetrySeconds(0);
        IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);
        when(portfolio.getPortfolio(any())).thenReturn(snapshotWith("MNQM6", bd(-2)));
        IbkrWtxRsiExecutionBridge bridge = bridgeWith(portfolio, noRetry);

        TradeExecutionRecord stuck = wtxrsiRow("SHORT", 2, ExecutionStatus.EXIT_SUBMITTED);
        stuck.setExitSubmittedAt(Instant.now().minusSeconds(600));
        repo.createIfAbsent(stuck);

        WtxRoutingResult result = bridge.submitClose(
                shortState(), WtxRsiSignalRecord.Action.CLOSE_SHORT, bd(30050));

        assertEquals(WtxRoutingOutcome.SKIPPED_DUPLICATE, result.outcome());
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private IbkrWtxRsiExecutionBridge bridgeWith(IbkrPortfolioService portfolio, WtxRsiStrategyProperties props) {
        return new IbkrWtxRsiExecutionBridge(ibkrOrderService, repo, ibkrProperties, portfolio, props);
    }

    /** SHORT position with qty 2 — the close direction is derived from this. */
    private static WtxRsiStrategyState shortState() {
        return WtxRsiStrategyState.initial("MNQ", "5m").withPosition(
                WtxRsiPosition.SHORT, bd(30000), bd(2), bd(30100), bd(29900));
    }

    private static TradeExecutionRecord wtxrsiRow(String action, int qty, ExecutionStatus status) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setExecutionKey("wtxrsi:MNQ:5m:1:OPEN_" + action);
        r.setInstrument("MNQ");
        r.setTimeframe("5m");
        r.setAction(action);
        r.setQuantity(qty);
        r.setTriggerSource(ExecutionTriggerSource.WTXRSI_AUTO);
        r.setStatus(status);
        r.setNormalizedEntryPrice(bd(30000));
        r.setBrokerAccountId("wtxrsi-default");
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
        @Override public Optional<TradeExecutionRecord> findByPermId(Long permId) { return Optional.empty(); }

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

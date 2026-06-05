package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.execution.DailyLossCapGuard;
import com.riskdesk.application.execution.DefaultOrderRouter;
import com.riskdesk.application.execution.OrderRouter;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.application.service.strategy.WtxIntentTranslator;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiRiskPlan;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignal;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.config.ExecutionProperties;
import com.riskdesk.infrastructure.config.WtxRsiStrategyProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Routes WTX+RSI signals to IBKR via {@link IbkrOrderService}.
 *
 * <p>Slice 1 contract — keep it minimal so the orchestrator can ship:
 * <ul>
 *   <li>OPEN: one limit order at the rounded entry price. Protective stop is held
 *       <i>internally</i> on {@code WtxRsiStrategyState.stopLoss} and enforced by
 *       the orchestrator on subsequent bar closes (no broker-side stop yet).</li>
 *   <li>CLOSE: one market-equivalent exit order against the open execution row of
 *       this (instrument, timeframe) — looked up by {@link ExecutionTriggerSource#WTXRSI_AUTO}.</li>
 *   <li>Idempotence: execution key is {@code wtxrsi:<instrument>:<timeframe>:<signalTs>:<action>}.
 *       {@code createIfAbsent} blocks duplicate rows.</li>
 * </ul>
 *
 * <p>Bracket orders (entry + protective stop in a single IBKR atomic submit) are
 * <b>net-new and explicitly deferred</b> — see {@code docs/WTX_RSI_STRATEGY.md}.
 *
 * <p>This bean is only built when {@code riskdesk.wtxrsi.enabled=true}. The
 * orchestrator pulls it via {@code ObjectProvider} so the strategy can run
 * (in simulation-only mode) even when the bridge is absent.
 */
@Service
@ConditionalOnProperty(name = "riskdesk.wtxrsi.enabled", havingValue = "true")
public class IbkrWtxRsiExecutionBridge implements WtxRsiExecutionBridge {

    private static final Logger log = LoggerFactory.getLogger(IbkrWtxRsiExecutionBridge.class);
    private static final String REQUESTED_BY = "wtxrsi-strategy";
    private static final String BROKER_ACCOUNT_FALLBACK = "wtxrsi-default";

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrProperties ibkrProperties;
    /** Unified execution core (P3). Nullable: when present AND {@code riskdesk.execution.unified-router.enabled}
     *  is ON (default OFF), WTX-RSI routes through it instead of the legacy submitOpen/submitClose path. */
    private final OrderRouter orderRouter;
    /** Migration kill-switch holder. Nullable in the test constructor. */
    private final ExecutionProperties executionProperties;
    /** P4 daily loss cap. Nullable in tests; when tripped, new OPENs are refused (closes aren't). */
    private final DailyLossCapGuard lossCapGuard;
    /**
     * Live IBKR portfolio reader. Nullable: when absent the bridge skips the stuck-close broker-truth
     * check (legacy skip-only behaviour). When present, a close stuck in {@code EXIT_SUBMITTED} past the
     * grace window is re-fired only if IBKR confirms the position is still open on that row's side — the
     * dead-lock fix mirrored from {@link com.riskdesk.application.service.strategy.WtxExecutionBridge}.
     */
    private final IbkrPortfolioService ibkrPortfolioService;
    /** Strategy config — supplies the stuck-close retry grace. Nullable in the legacy test constructor. */
    private final WtxRsiStrategyProperties wtxRsiProperties;

    /** Test-only legacy constructor — production uses the 8-arg variant via Spring autowiring. */
    public IbkrWtxRsiExecutionBridge(
            IbkrOrderService ibkrOrderService,
            TradeExecutionRepositoryPort executionRepository,
            IbkrProperties ibkrProperties) {
        this(ibkrOrderService, executionRepository, ibkrProperties, null, null, null, null, null);
    }

    /** Test-only — IBKR portfolio reconcile + grace properties wired, unified router disabled. */
    public IbkrWtxRsiExecutionBridge(
            IbkrOrderService ibkrOrderService,
            TradeExecutionRepositoryPort executionRepository,
            IbkrProperties ibkrProperties,
            IbkrPortfolioService ibkrPortfolioService,
            WtxRsiStrategyProperties wtxRsiProperties) {
        this(ibkrOrderService, executionRepository, ibkrProperties, null, null, null,
                ibkrPortfolioService, wtxRsiProperties);
    }

    /** Test-only — unified-router path; portfolio reconcile + grace properties absent. */
    public IbkrWtxRsiExecutionBridge(
            IbkrOrderService ibkrOrderService,
            TradeExecutionRepositoryPort executionRepository,
            IbkrProperties ibkrProperties,
            OrderRouter orderRouter,
            ExecutionProperties executionProperties,
            DailyLossCapGuard lossCapGuard) {
        this(ibkrOrderService, executionRepository, ibkrProperties, orderRouter, executionProperties,
                lossCapGuard, null, null);
    }

    @Autowired
    public IbkrWtxRsiExecutionBridge(
            IbkrOrderService ibkrOrderService,
            TradeExecutionRepositoryPort executionRepository,
            IbkrProperties ibkrProperties,
            OrderRouter orderRouter,
            ExecutionProperties executionProperties,
            DailyLossCapGuard lossCapGuard,
            IbkrPortfolioService ibkrPortfolioService,
            WtxRsiStrategyProperties wtxRsiProperties) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.orderRouter = orderRouter;
        this.executionProperties = executionProperties;
        this.lossCapGuard = lossCapGuard;
        this.ibkrPortfolioService = ibkrPortfolioService;
        this.wtxRsiProperties = wtxRsiProperties;
    }

    private boolean unifiedRouterEnabled() {
        return orderRouter != null && executionProperties != null && executionProperties.isUnifiedRouterEnabled();
    }

    @Override
    public WtxRoutingResult submitOpen(
            WtxRsiSignal signal, WtxRsiRiskPlan plan,
            WtxRsiStrategyState state, BigDecimal referencePrice) {

        if (!ibkrProperties.isEnabled()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_IBKR_DISABLED);
        }
        if (plan.contracts() <= 0) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_QTY);
        }
        if (plan.entryPrice() == null) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_PRICE);
        }
        if (lossCapGuard != null && lossCapGuard.blocksNewEntries()) {
            // P4 — daily loss cap tripped: no new entries (closes via submitClose stay allowed). Covers both
            // the legacy and unified-router paths below (the router also gates, harmlessly).
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_AUTO_OFF);
        }

        // P3 — unified-router migration: when the flag is ON, route the OPEN through the shared OrderRouter
        // (broker-truth reconcile, idempotence, row persistence, synchronous-fill). Zero effect while OFF.
        if (unifiedRouterEnabled()) {
            return routeOpenViaRouter(signal, plan, state);
        }

        String executionKey = executionKey(state.instrument(), state.timeframe(),
                signal.timestamp(), signal.side() == WtxRsiSignal.Side.LONG ? "OPEN_LONG" : "OPEN_SHORT");

        if (executionRepository.findByExecutionKey(executionKey).isPresent()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_DUPLICATE);
        }

        String brokerAction = signal.side() == WtxRsiSignal.Side.LONG ? "LONG" : "SHORT";

        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey(executionKey);
        candidate.setBrokerAccountId(BROKER_ACCOUNT_FALLBACK);
        candidate.setInstrument(state.instrument());
        candidate.setTimeframe(state.timeframe());
        candidate.setAction(brokerAction);
        candidate.setQuantity(plan.contracts());
        candidate.setTriggerSource(ExecutionTriggerSource.WTXRSI_AUTO);
        candidate.setRequestedBy(REQUESTED_BY);
        candidate.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        candidate.setStatusReason("WTXRSI OPEN armed");
        candidate.setNormalizedEntryPrice(plan.entryPrice());
        candidate.setVirtualStopLoss(plan.stopLoss());
        candidate.setVirtualTakeProfit(plan.takeProfit());
        candidate.setCreatedAt(Instant.now());
        candidate.setUpdatedAt(Instant.now());

        TradeExecutionRecord persisted = executionRepository.createIfAbsent(candidate);

        try {
            BrokerEntryOrderSubmission sub = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                    persisted.getId(),
                    persisted.getExecutionKey(),
                    persisted.getBrokerAccountId(),
                    persisted.getInstrument(),
                    brokerAction,
                    plan.contracts(),
                    plan.entryPrice()
            ));
            // P2 — synchronous fill: when the broker reports the entry already Filled at submit return, mark
            // the row ACTIVE NOW instead of waiting on an orderStatus(Filled) callback that can be dropped
            // (root cause R2). Mirrors DefaultOrderRouter.submitPersistedEntry. Otherwise ENTRY_SUBMITTED.
            boolean filled = "Filled".equalsIgnoreCase(sub.brokerOrderStatus());
            Instant submittedAt = sub.submittedAt() != null ? sub.submittedAt() : Instant.now();
            persisted.setEntryOrderId(sub.brokerOrderId());
            if (sub.brokerOrderId() != null) {
                persisted.setIbkrOrderId(Math.toIntExact(sub.brokerOrderId()));
            }
            persisted.setStatus(filled ? ExecutionStatus.ACTIVE : ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("WTXRSI OPEN " + (filled ? "filled" : "submitted") + ": " + sub.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submittedAt);
            if (filled && persisted.getEntryFilledAt() == null) {
                persisted.setEntryFilledAt(submittedAt);
            }
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("WTX-RSI [{} {}] OPEN {} — side={} qty={} entry={} brokerOrderId={}",
                    state.instrument(), state.timeframe(), filled ? "filled" : "submitted", brokerAction,
                    plan.contracts(), plan.entryPrice(), sub.brokerOrderId());
            return WtxRoutingResult.of(WtxRoutingOutcome.ROUTED);
        } catch (RuntimeException e) {
            String msg = truncate(e.getMessage(), 200);
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason("WTXRSI OPEN failed: " + msg);
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.error("WTX-RSI [{} {}] OPEN failed: {}", state.instrument(), state.timeframe(), msg, e);
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED, msg);
        }
    }

    @Override
    public WtxRoutingResult submitClose(
            WtxRsiStrategyState state, WtxRsiSignalRecord.Action action, BigDecimal referencePrice) {

        if (!ibkrProperties.isEnabled()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_IBKR_DISABLED);
        }
        if (state.currentPosition() == WtxRsiPosition.FLAT
                || state.entryQty() == null
                || state.entryQty().signum() <= 0) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW);
        }

        // P3 — unified-router migration: route the CLOSE through the shared OrderRouter when the flag is ON.
        if (unifiedRouterEnabled()) {
            return routeCloseViaRouter(state, action, referencePrice);
        }

        Optional<TradeExecutionRecord> openRow = executionRepository
                .findActiveByInstrumentAndTimeframeAndTriggerSource(
                        state.instrument(), state.timeframe(), ExecutionTriggerSource.WTXRSI_AUTO);
        if (openRow.isEmpty()) {
            log.info("WTX-RSI [{} {}] CLOSE requested ({}) but no open WTX-RSI execution row found — "
                    + "skipping IBKR submission to avoid a naked order",
                    state.instrument(), state.timeframe(), action);
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW);
        }
        TradeExecutionRecord row = openRow.get();
        // Guard against a second flatten while the first close is still in flight.
        // ExecutionFillTrackingService reconciles EXIT_SUBMITTED → CLOSED on the
        // broker fill callback, so a duplicate submit would race the reconciler
        // and could leave a phantom row behind.
        //
        // BUT a marketable close that gapped out of the book (or whose ack / fill callback was lost) can
        // leave the row stuck in EXIT_SUBMITTED while IBKR STILL holds the position. The old unconditional
        // skip then DEAD-LOCKED the instrument: every later CLOSE returned SKIPPED_DUPLICATE here and every
        // same-side OPEN returned SKIPPED_DUPLICATE from the entry reconcile, so the position could be
        // neither exited nor reversed and bled unbounded — surfaced in the UI as a row stuck on
        // "NON EXÉCUTÉ / DUPLICATE", and the marketable toggle made no difference because the skip happens
        // BEFORE any order is priced. {@link #stuckCloseNeedsRetry} only returns true once the close has
        // been EXIT_SUBMITTED past the retry grace AND broker truth confirms the position is still open on
        // this row's side — in which case we fall through and re-fire a FRESH marketable flatten (a per-bar
        // exit ref keyed on lastCandleTs). Within the grace, or when broker truth is unavailable / confirmed
        // flat, we keep the duplicate-skip so a genuinely in-flight close is never double-submitted.
        // (StaleCloseReconciler owns the flat-but-stuck case.)
        if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            if (!stuckCloseNeedsRetry(row, readIbkrPositionState(state.instrument()))) {
                log.info("WTX-RSI [{} {}] CLOSE requested ({}) but row {} is already EXIT_SUBMITTED — "
                        + "skipping duplicate flatten",
                        state.instrument(), state.timeframe(), action, row.getId());
                return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_DUPLICATE);
            }
            log.warn("WTX-RSI [{} {}] CLOSE {} — prior flatten stuck (row {} EXIT_SUBMITTED past grace, "
                    + "IBKR still holds the position) — re-firing a fresh marketable close to break the dead-lock",
                    state.instrument(), state.timeframe(), action, row.getId());
            // fall through to submit a fresh close leg on this same row
        }

        int qty = row.getQuantity() != null && row.getQuantity() > 0
                ? row.getQuantity() : state.entryQty().intValue();
        BigDecimal exitPrice = referencePrice != null ? referencePrice : row.getNormalizedEntryPrice();
        if (exitPrice == null) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_PRICE);
        }
        String closeAction = state.currentPosition() == WtxRsiPosition.LONG ? "SHORT" : "LONG";
        // Per-bar discriminator for the retry-safe exit orderRef (see WtxExecutionBridge#submitCloseLeg):
        // stable within a bar (idempotent in-flight), fresh next bar (a retry after a terminal-non-filled
        // close gets a new ref instead of matching the dead order).
        long exitAttempt = (state.lastCandleTs() != null ? state.lastCandleTs() : Instant.now()).toEpochMilli();

        // CRITICAL: transition the EXISTING open row to EXIT_SUBMITTED. Do NOT
        // create a new row — ExecutionFillTrackingService only marks EXIT_SUBMITTED
        // rows as CLOSED; a fresh ENTRY_SUBMITTED row would reconcile to ACTIVE,
        // leaving the original row non-terminal and inflating the open-position
        // count. Same contract as WtxExecutionBridge#submitCloseLeg.
        try {
            BrokerEntryOrderSubmission sub = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                    row.getId(),
                    // Distinct, retry-safe exit orderRef — see WtxExecutionBridge#submitCloseLeg: ":exit"
                    // stops placeLimitOrder's idempotency returning the completed entry order; the per-bar
                    // discriminator stops a close retry matching the previous terminal-non-filled close.
                    row.getExecutionKey() + DefaultOrderRouter.EXIT_ORDER_REF_SUFFIX + ":" + exitAttempt,
                    row.getBrokerAccountId(),
                    row.getInstrument(),
                    closeAction,
                    qty,
                    exitPrice));
            // P2 — synchronous fill: a marketable close usually comes back Filled at submit return. Mark the
            // row CLOSED NOW rather than EXIT_SUBMITTED — otherwise the orderStatus(Filled) callback, which can
            // arrive before the close orderId is persisted, is dropped (root cause R2) and the row stays a
            // phantom EXIT_SUBMITTED. Mirrors DefaultOrderRouter.submitCloseLeg. A resting close stays EXIT_SUBMITTED.
            boolean filled = "Filled".equalsIgnoreCase(sub.brokerOrderStatus());
            Instant now = Instant.now();
            row.setStatus(filled ? ExecutionStatus.CLOSED : ExecutionStatus.EXIT_SUBMITTED);
            if (sub.brokerOrderId() != null) {
                // Fill tracker locates rows by ibkrOrderId — must be on the SAME
                // row we're transitioning so the fill callback reconciles here.
                row.setIbkrOrderId(Math.toIntExact(sub.brokerOrderId()));
            }
            row.setStatusReason("WTXRSI " + action.name()
                    + " — IBKR close " + (filled ? "filled" : "submitted") + ": " + sub.brokerOrderStatus()
                    + " (broker order " + sub.brokerOrderId() + ")");
            row.setExitSubmittedAt(sub.submittedAt() != null ? sub.submittedAt() : now);
            if (filled && row.getClosedAt() == null) {
                row.setClosedAt(now);
            }
            row.setUpdatedAt(now);
            executionRepository.save(row);
            log.info("WTX-RSI [{} {}] CLOSE {} — direction={} qty={} executionId={} brokerOrderId={}",
                    state.instrument(), state.timeframe(), filled ? "filled" : "submitted", closeAction, qty, row.getId(), sub.brokerOrderId());
            return WtxRoutingResult.of(WtxRoutingOutcome.ROUTED);
        } catch (RuntimeException e) {
            // Keep the row non-terminal on failure so the open position stays
            // visible and the close is retryable on the next bar / next signal.
            String msg = truncate(e.getMessage(), 200);
            row.setStatusReason("WTXRSI " + action.name() + " close failed: " + msg);
            row.setUpdatedAt(Instant.now());
            executionRepository.save(row);
            log.error("WTX-RSI [{} {}] CLOSE failed (row {} left non-terminal for retry): {}",
                    state.instrument(), state.timeframe(), row.getId(), msg, e);
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED, msg);
        }
    }

    /**
     * True when an {@code EXIT_SUBMITTED} close is STUCK and must be re-fired rather than skipped as a
     * duplicate flatten: it has been non-terminal for longer than the retry grace AND IBKR still holds a
     * live position on this row's side, so the flatten clearly never completed (a marketable close that
     * gapped out and died, or a lost ack / fill). Re-firing a fresh marketable close is the only way to
     * flatten — otherwise the instrument dead-locks (every later CLOSE skips here as a duplicate, every
     * same-side OPEN skips in the entry reconcile as a duplicate) and the position bleeds. Mirrors
     * {@code WtxExecutionBridge.stuckCloseNeedsRetry}.
     *
     * <p>Deliberately conservative — returns {@code false} when:
     * <ul>
     *   <li>broker truth is unavailable ({@code net == null}) — never re-fire on a guess;</li>
     *   <li>IBKR is flat, or holds the OPPOSITE side — the close completed (the flat-but-stuck row is
     *       finalized to CLOSED by {@code StaleCloseReconciler}), or this is a deeper divergence the
     *       entry-path reconcile owns; flattening here could open an unintended position;</li>
     *   <li>still within the grace window — a genuinely in-flight marketable close fills in seconds, so a
     *       fresh close must NOT be double-submitted on top of it.</li>
     * </ul>
     * Grace is {@code riskdesk.wtxrsi.stale-close-retry-seconds} (0 disables the retry → legacy skip-only).
     */
    private boolean stuckCloseNeedsRetry(TradeExecutionRecord row, IbkrPositionState ibkr) {
        if (row.getStatus() != ExecutionStatus.EXIT_SUBMITTED) {
            return false;
        }
        BigDecimal net = ibkr.net();
        if (net == null || net.signum() == 0) {
            return false; // unknown or flat → not a re-fire case
        }
        // Only re-fire when IBKR still holds the SAME side this row tracks: flattening a short is a BUY,
        // so it must never run while IBKR is (somehow) long, which would stack rather than flatten.
        boolean rowIsLong = "LONG".equalsIgnoreCase(row.getAction());
        boolean stillHoldsSameSide = rowIsLong ? net.signum() > 0 : net.signum() < 0;
        if (!stillHoldsSameSide) {
            return false;
        }
        int graceSeconds = wtxRsiProperties != null ? wtxRsiProperties.getStaleCloseRetrySeconds() : 0;
        if (graceSeconds <= 0) {
            return false; // retry disabled (or properties not wired)
        }
        Instant since = row.getExitSubmittedAt() != null ? row.getExitSubmittedAt() : row.getUpdatedAt();
        if (since == null) {
            return false;
        }
        return Duration.between(since, Instant.now()).getSeconds() >= graceSeconds;
    }

    /**
     * IBKR live position for an instrument symbol, read from a SINGLE portfolio snapshot. Mirrors
     * {@code WtxExecutionBridge.readIbkrPositionState}, scoped to the wtxrsi {@code "wtxrsi-default"}
     * account placeholder. {@code net} is {@code null} when the portfolio service is not wired or the
     * snapshot is unavailable ("unknown — fall back to legacy skip-only"); {@code confirmedFlat} is true
     * only on a connected snapshot with no matching nonzero leg (stricter than {@code net == 0}, so
     * offsetting rollover/calendar legs that net to zero are NOT treated as flat).
     */
    private record IbkrPositionState(BigDecimal net, boolean confirmedFlat) {}

    private IbkrPositionState readIbkrPositionState(String instrument) {
        if (ibkrPortfolioService == null) return new IbkrPositionState(null, false);
        // Scope the read to the same account the bridge submits on (null for the "wtxrsi-default"
        // placeholder → the gateway's default managed account), so reconcile and submission agree.
        String accountId = effectiveBrokerAccountId();
        IbkrPortfolioSnapshot snapshot;
        try {
            snapshot = ibkrPortfolioService.getPortfolio(accountId);
        } catch (RuntimeException e) {
            log.debug("WTX-RSI reconcile: portfolio snapshot unavailable for {} (account={}) — {}",
                    instrument, accountId, e.getMessage());
            return new IbkrPositionState(null, false);
        }
        if (snapshot == null || !snapshot.connected() || snapshot.positions() == null) {
            return new IbkrPositionState(null, false);
        }
        String symbol = ibkrSymbol(instrument);
        if (symbol == null) return new IbkrPositionState(null, false);
        BigDecimal total = BigDecimal.ZERO;
        boolean anyNonzeroLeg = false;
        for (IbkrPositionView pos : snapshot.positions()) {
            if (pos == null || pos.position() == null) continue;
            // Second-line account filter: some gateways return positions across every attached account
            // even when getPortfolio is given a specific id. No-op when no account is configured.
            if (accountId != null && pos.accountId() != null && !accountId.equals(pos.accountId())) {
                continue;
            }
            if (matchesSymbol(pos.contractDesc(), symbol)) {
                total = total.add(pos.position());
                if (pos.position().signum() != 0) anyNonzeroLeg = true;
            }
        }
        // Confirmed flat = connected snapshot with NO nonzero matching leg. Offsetting legs that net to
        // zero (anyNonzeroLeg && total == 0) are LIVE positions, hence not flat.
        return new IbkrPositionState(total, !anyNonzeroLeg);
    }

    /**
     * The broker account id reconcile scopes to — {@code null} for the {@code "wtxrsi-default"} placeholder
     * ("let the gateway pick the default managed account"), so the position read and the order submission
     * stay on the same account. wtxrsi has no configurable per-strategy account, so this is always the
     * placeholder → null; kept as a method to mirror {@code WtxExecutionBridge} and as a single choke point
     * if a real account is ever added.
     */
    private String effectiveBrokerAccountId() {
        return null;
    }

    /** IBKR ticker for an instrument symbol. Differs for E6 (IBKR symbol "6E"). */
    private static String ibkrSymbol(String instrument) {
        if (instrument == null) return null;
        return "E6".equals(instrument) ? "6E" : instrument;
    }

    /**
     * Matches an IBKR {@code contractDesc} (the gateway's localSymbol, e.g. "MNQH6", "6EM6") against an
     * instrument symbol by leading-symbol prefix — the cheapest reliable match across native + client
     * portal gateways. Mirrors {@code WtxExecutionBridge.matchesSymbol}.
     */
    private static boolean matchesSymbol(String contractDesc, String symbol) {
        if (contractDesc == null || symbol == null) return false;
        return contractDesc.toUpperCase().trim().startsWith(symbol);
    }

    /**
     * P3 — route a WTX-RSI OPEN through the unified {@link OrderRouter}. Builds a {@link TradeIntent#open}
     * under {@link ExecutionTriggerSource#WTXRSI_AUTO} and maps the router's neutral outcome back to the
     * strategy-facing {@link WtxRoutingOutcome} (reusing {@link WtxIntentTranslator#toWtxOutcome}). The
     * account is passed null so the router reads the REAL default managed account (its position filter is a
     * no-op on null) and persists {@link DefaultOrderRouter#DEFAULT_BROKER_ACCOUNT}; legacy "wtxrsi-default"
     * rows are re-pointed at cutover so exits still locate them.
     */
    private WtxRoutingResult routeOpenViaRouter(WtxRsiSignal signal, WtxRsiRiskPlan plan, WtxRsiStrategyState state) {
        Instrument instrument;
        try {
            instrument = Instrument.valueOf(state.instrument());
        } catch (IllegalArgumentException | NullPointerException e) {
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED, "Unknown instrument: " + state.instrument());
        }
        boolean isLong = signal.side() == WtxRsiSignal.Side.LONG;
        String key = executionKey(state.instrument(), state.timeframe(), signal.timestamp(),
                isLong ? "OPEN_LONG" : "OPEN_SHORT");
        TradeIntent intent = TradeIntent.open(key, ExecutionTriggerSource.WTXRSI_AUTO, instrument,
                state.timeframe(), isLong ? Side.LONG : Side.SHORT, plan.contracts(), plan.entryPrice(), null);
        return routeAndMap(intent, state, "OPEN " + (isLong ? "LONG" : "SHORT"));
    }

    /**
     * P3 — route a WTX-RSI CLOSE through the unified {@link OrderRouter}. The held side comes from the
     * strategy state; the router confirms it against broker truth (a stale side can't INCREASE the live
     * position). Quantity is a fallback only — the router caps it to what the row/broker actually hold.
     */
    private WtxRoutingResult routeCloseViaRouter(WtxRsiStrategyState state, WtxRsiSignalRecord.Action action,
                                                 BigDecimal referencePrice) {
        Instrument instrument;
        try {
            instrument = Instrument.valueOf(state.instrument());
        } catch (IllegalArgumentException | NullPointerException e) {
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED, "Unknown instrument: " + state.instrument());
        }
        if (referencePrice == null) {
            // The router prices the close limit off the intent — without a reference price it cannot.
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_PRICE);
        }
        boolean isLong = state.currentPosition() == WtxRsiPosition.LONG;
        // Stable within a bar (idempotent in-flight), fresh next bar — matches the legacy exit discriminator.
        Instant ts = state.lastCandleTs() != null ? state.lastCandleTs() : Instant.now();
        String key = executionKey(state.instrument(), state.timeframe(), ts, isLong ? "CLOSE_LONG" : "CLOSE_SHORT");
        int qty = state.entryQty().intValue();
        TradeIntent intent = TradeIntent.close(key, ExecutionTriggerSource.WTXRSI_AUTO, instrument,
                state.timeframe(), isLong ? Side.LONG : Side.SHORT, qty, referencePrice, null);
        return routeAndMap(intent, state, action.name());
    }

    private WtxRoutingResult routeAndMap(TradeIntent intent, WtxRsiStrategyState state, String label) {
        RoutingResult result = orderRouter.route(intent);
        WtxRoutingOutcome outcome = WtxIntentTranslator.toWtxOutcome(result.outcome());
        log.info("WTX-RSI [{} {}] unified-router {} → {} (core {})",
                state.instrument(), state.timeframe(), label, outcome, result.outcome());
        return WtxRoutingResult.of(outcome, result.outcome().isFailure() ? result.message() : null);
    }

    /**
     * P3 cutover — re-point non-terminal legacy {@code wtxrsi-default} rows to the router's
     * {@link DefaultOrderRouter#DEFAULT_BROKER_ACCOUNT} so the router's account-scoped open-row lookup can
     * locate them after the flag flips; otherwise a CLOSE against an existing position would return
     * {@code SKIPPED_NO_OPEN_ROW} and leave the live IBKR position open. Flag-gated (no-op OFF) and
     * idempotent (only the placeholder is touched).
     */
    @EventListener(ApplicationReadyEvent.class)
    void normalizeLegacyDefaultAccountRowsForCutover() {
        if (!unifiedRouterEnabled()) {
            return;
        }
        int normalized = 0;
        for (TradeExecutionRecord row : executionRepository.findAllActive()) {
            if (row.getTriggerSource() == ExecutionTriggerSource.WTXRSI_AUTO
                    && BROKER_ACCOUNT_FALLBACK.equals(row.getBrokerAccountId())) {
                row.setBrokerAccountId(DefaultOrderRouter.DEFAULT_BROKER_ACCOUNT);
                row.setUpdatedAt(Instant.now());
                executionRepository.save(row);
                normalized++;
            }
        }
        if (normalized > 0) {
            log.info("WTX-RSI unified-router cutover: normalized {} 'wtxrsi-default' row(s) to '{}'",
                    normalized, DefaultOrderRouter.DEFAULT_BROKER_ACCOUNT);
        }
    }

    private static String executionKey(String instrument, String timeframe, Instant ts, String action) {
        return "wtxrsi:" + instrument + ":" + timeframe + ":" + ts.toEpochMilli() + ":" + action;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

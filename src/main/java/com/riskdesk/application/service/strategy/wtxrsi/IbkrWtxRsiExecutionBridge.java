package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.execution.DefaultOrderRouter;
import com.riskdesk.application.execution.OrderRouter;
import com.riskdesk.application.service.IbkrOrderService;
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
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    /** Test-only legacy constructor — production uses the 5-arg variant via Spring autowiring. */
    public IbkrWtxRsiExecutionBridge(
            IbkrOrderService ibkrOrderService,
            TradeExecutionRepositoryPort executionRepository,
            IbkrProperties ibkrProperties) {
        this(ibkrOrderService, executionRepository, ibkrProperties, null, null);
    }

    @Autowired
    public IbkrWtxRsiExecutionBridge(
            IbkrOrderService ibkrOrderService,
            TradeExecutionRepositoryPort executionRepository,
            IbkrProperties ibkrProperties,
            OrderRouter orderRouter,
            ExecutionProperties executionProperties) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.orderRouter = orderRouter;
        this.executionProperties = executionProperties;
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
        if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            log.info("WTX-RSI [{} {}] CLOSE requested ({}) but row {} is already EXIT_SUBMITTED — "
                    + "skipping duplicate flatten",
                    state.instrument(), state.timeframe(), action, row.getId());
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_DUPLICATE);
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

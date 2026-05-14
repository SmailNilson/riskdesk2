package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.engine.strategy.wtx.WtxAction;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * Routes WTX strategy actions to IBKR via the existing {@link IbkrOrderService}.
 *
 * Only invoked when {@link WtxStrategyState#autoExecutionEnabled()} is true for the instrument.
 * The user opts in per instrument via the WTX panel; the default is OFF for safety.
 *
 * Lifecycle contract — every {@link TradeExecutionRecord} the bridge writes mirrors the
 * convention of the rest of the execution stack:
 * <ul>
 *   <li>{@code action} carries the broker-side direction token {@code LONG}/{@code SHORT},
 *       NOT the WTX enum name — this is what {@code IbGatewayBrokerGateway} interprets
 *       correctly and what {@code ActivePositionView} resolves to direction + PnL sign.</li>
 *   <li>OPEN / REVERSE create one entry row (status {@code ENTRY_SUBMITTED}); the
 *       {@code quantity} is the resulting position size, while the IBKR order quantity is
 *       doubled for a REVERSE so the broker flattens the opposite side in one fill.</li>
 *   <li>CLOSE actions never create a new row — they locate the bridge's own open WTX
 *       execution and transition it to {@code CLOSED}. REVERSE also closes the prior row
 *       before opening the new one. This keeps the Active Positions feed in sync with the
 *       WTX position state instead of leaking phantom "active" close orders.</li>
 * </ul>
 *
 * Idempotence: entry rows use executionKey {@code wtx:<instrument>:<signalTs>:<action>} and
 * {@code createIfAbsent} honours the unique constraint on the key.
 */
@Service
@ConditionalOnProperty(name = "riskdesk.wtx.enabled", havingValue = "true")
public class WtxExecutionBridge {

    private static final Logger log = LoggerFactory.getLogger(WtxExecutionBridge.class);

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrProperties ibkrProperties;
    private final WtxStrategyProperties wtxProperties;

    public WtxExecutionBridge(IbkrOrderService ibkrOrderService,
                              TradeExecutionRepositoryPort executionRepository,
                              IbkrProperties ibkrProperties,
                              WtxStrategyProperties wtxProperties) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.wtxProperties = wtxProperties;
    }

    public void submit(WtxSignal signal, WtxStrategyState state) {
        submit(signal, state, null);
    }

    public void submit(WtxSignal signal, WtxStrategyState state, BigDecimal referencePrice) {
        if (signal == null || state == null) return;
        if (!state.autoExecutionEnabled()) return;
        if (!ibkrProperties.isEnabled()) {
            log.warn("WTX [{}] auto-execution skipped — IBKR disabled in backend", state.instrument());
            return;
        }

        WtxAction action = signal.suggestedAction();
        if (action == null || action == WtxAction.NONE) return;

        Instrument instrument;
        try {
            instrument = Instrument.valueOf(state.instrument());
        } catch (IllegalArgumentException e) {
            log.warn("WTX execution bridge: unknown instrument {}", state.instrument());
            return;
        }

        switch (action) {
            case CLOSE_LONG, CLOSE_SHORT, CLOSE_ALL -> handleClose(signal, state, instrument, action, referencePrice);
            case OPEN_LONG, OPEN_SHORT, REVERSE_TO_LONG, REVERSE_TO_SHORT ->
                    handleEntry(signal, state, instrument, action, referencePrice);
            default -> log.debug("WTX [{}] no IBKR routing for action {}", state.instrument(), action);
        }
    }

    /**
     * OPEN / REVERSE — create a single entry row and submit the broker order.
     * For a REVERSE, the prior WTX execution row is retired only AFTER the broker
     * accepts the new reverse order — a duplicate signal, missing price, or
     * submission failure must never strand the live position with its only
     * execution row already terminal.
     */
    private void handleEntry(WtxSignal signal, WtxStrategyState state, Instrument instrument,
                             WtxAction action, BigDecimal referencePrice) {
        boolean isReverse = action == WtxAction.REVERSE_TO_LONG || action == WtxAction.REVERSE_TO_SHORT;

        // Capture (read-only) the prior WTX row for a reverse — do NOT retire it yet.
        // It is transitioned only once the reverse order is accepted (see success branch).
        Optional<TradeExecutionRecord> priorForReverse = isReverse
                ? findOpenWtxExecution(state.instrument())
                : Optional.empty();

        String executionKey = "wtx:" + state.instrument() + ":" + signal.signalTs().getEpochSecond() + ":" + action.name();
        if (executionRepository.findByExecutionKey(executionKey).isPresent()) {
            log.debug("WTX [{}] duplicate execution for {} — skipping", state.instrument(), executionKey);
            return;
        }

        BigDecimal price = referencePrice != null ? referencePrice : state.entryPrice();
        if (price == null) {
            log.warn("WTX [{}] missing reference price for {} — cannot submit", state.instrument(), action);
            return;
        }

        int positionQty = positionQuantity(state);
        if (positionQty <= 0) {
            log.debug("WTX [{}] non-positive quantity {} — skipping", state.instrument(), positionQty);
            return;
        }
        // REVERSE flips the book in one fill: close opposite (positionQty) + open new (positionQty).
        int orderQty = positionQty * (isReverse ? 2 : 1);
        String orderAction = mapToOrderAction(action);

        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey(executionKey);
        candidate.setMentorSignalReviewId(null);
        candidate.setReviewAlertKey(null);
        candidate.setReviewRevision(null);
        candidate.setBrokerAccountId(firstNonBlank(wtxProperties.getBrokerAccountId(), "wtx-default"));
        candidate.setInstrument(state.instrument());
        candidate.setTimeframe(signal.timeframe());
        // Broker-side direction token: "LONG"/"SHORT" — the value IbGatewayBrokerGateway
        // understands (only "SHORT" maps to Action.SELL; anything else is a BUY) and which
        // ActivePositionView also resolves to the correct direction + PnL sign.
        candidate.setAction(orderAction);
        // Resulting position size — not the (possibly doubled) broker order quantity.
        candidate.setQuantity(positionQty);
        candidate.setTriggerSource(ExecutionTriggerSource.WTX_AUTO);
        candidate.setRequestedBy("wtx-strategy");
        candidate.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        candidate.setStatusReason("WTX " + action.name() + " armed");
        candidate.setNormalizedEntryPrice(normalizeToTick(price, instrument));
        candidate.setVirtualStopLoss(state.trailingStopPrice());
        candidate.setVirtualTakeProfit(null);
        candidate.setCreatedAt(Instant.now());
        candidate.setUpdatedAt(Instant.now());

        TradeExecutionRecord persisted = executionRepository.createIfAbsent(candidate);

        try {
            BrokerEntryOrderSubmission submission = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                    persisted.getId(),
                    persisted.getExecutionKey(),
                    persisted.getBrokerAccountId(),
                    persisted.getInstrument(),
                    orderAction,
                    orderQty,
                    persisted.getNormalizedEntryPrice()
            ));
            persisted.setEntryOrderId(submission.brokerOrderId());
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("WTX " + action.name() + " submitted: " + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submission.submittedAt() != null ? submission.submittedAt() : Instant.now());
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("WTX [{}] IBKR entry submitted — action={} positionQty={} orderQty={} orderAction={} brokerOrderId={}",
                    state.instrument(), action, positionQty, orderQty, orderAction, submission.brokerOrderId());
            // Reverse order accepted by the broker — only now retire the prior position's row.
            priorForReverse.ifPresent(prior -> markPriorRowExitSubmitted(prior,
                    "WTX reversed by " + action.name() + " (broker order " + submission.brokerOrderId() + ")"));
        } catch (RuntimeException e) {
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason("WTX " + action.name() + " failed: " + truncate(e.getMessage(), 200));
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            // priorForReverse is intentionally left untouched — the live position keeps its
            // active execution row so the next bar can retry the reverse.
            log.error("WTX [{}] IBKR submission failed for {} — {}",
                    state.instrument(), action, e.getMessage(), e);
        }
    }

    /**
     * CLOSE — submit the broker-side flatten order against the bridge's own open WTX
     * execution row and transition that row to {@code EXIT_SUBMITTED} (non-terminal).
     * Never creates a new row, so an ATR / max-loss / NY-force exit can't leak a phantom
     * "active" close order. The row stays visible until the broker fill is reconciled
     * downstream — marking it {@code CLOSED} here would hide a still-open position and
     * strand the row (the fill tracker skips terminal rows). When no open WTX row exists
     * the close is logged and skipped — submitting a naked order would risk opening an
     * unintended position.
     */
    private void handleClose(WtxSignal signal, WtxStrategyState state, Instrument instrument,
                             WtxAction action, BigDecimal referencePrice) {
        Optional<TradeExecutionRecord> open = findOpenWtxExecution(state.instrument());
        if (open.isEmpty()) {
            log.warn("WTX [{}] close requested ({}) but no open WTX execution row found — "
                    + "skipping IBKR submission to avoid a naked order", state.instrument(), action);
            return;
        }
        TradeExecutionRecord row = open.get();
        // Guard against a second flatten while the first close is still in flight. The fill
        // tracker reconciles EXIT_SUBMITTED -> CLOSED once the broker confirms the fill.
        if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            log.debug("WTX [{}] close requested ({}) but execution {} is already EXIT_SUBMITTED — "
                    + "skipping duplicate flatten", state.instrument(), action, row.getId());
            return;
        }
        int qty = row.getQuantity() != null && row.getQuantity() > 0 ? row.getQuantity() : positionQuantity(state);
        if (qty <= 0) {
            log.debug("WTX [{}] non-positive close quantity {} — skipping", state.instrument(), qty);
            return;
        }
        String orderAction = mapToOrderAction(action);
        BigDecimal price = referencePrice != null ? referencePrice : row.getNormalizedEntryPrice();
        if (price == null) {
            log.warn("WTX [{}] missing reference price for {} — cannot submit close", state.instrument(), action);
            return;
        }

        try {
            BrokerEntryOrderSubmission submission = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                    row.getId(),
                    row.getExecutionKey(),
                    row.getBrokerAccountId(),
                    row.getInstrument(),
                    orderAction,
                    qty,
                    normalizeToTick(price, instrument)
            ));
            Instant now = Instant.now();
            // Non-terminal: the broker has only ACCEPTED the close, not confirmed the fill.
            // EXIT_SUBMITTED keeps the row visible in Active Positions; ExecutionFillTrackingService
            // transitions it to CLOSED on the Filled callback (located via the executionKey orderRef).
            row.setStatus(ExecutionStatus.EXIT_SUBMITTED);
            row.setStatusReason("WTX " + action.name() + " — IBKR close submitted: " + submission.brokerOrderStatus()
                    + " (broker order " + submission.brokerOrderId() + ")");
            row.setExitSubmittedAt(now);
            row.setUpdatedAt(now);
            executionRepository.save(row);
            log.info("WTX [{}] IBKR close submitted — action={} qty={} orderAction={} executionId={} brokerOrderId={}",
                    state.instrument(), action, qty, orderAction, row.getId(), submission.brokerOrderId());
        } catch (RuntimeException e) {
            // Keep the row non-terminal so the open position stays visible and the close is retryable.
            row.setStatusReason("WTX " + action.name() + " close failed: " + truncate(e.getMessage(), 200));
            row.setUpdatedAt(Instant.now());
            executionRepository.save(row);
            log.error("WTX [{}] IBKR close submission failed for {} — {}",
                    state.instrument(), action, e.getMessage(), e);
        }
    }

    /**
     * Retires a superseded WTX execution row to EXIT_SUBMITTED (non-terminal) without
     * touching the broker — used for the prior row of a REVERSE, whose flatten leg rides
     * on the already-accepted reverse order. Kept non-terminal so it stays visible until
     * downstream fill reconciliation closes it; never marked CLOSED here because the broker
     * has not yet confirmed the fill.
     */
    private void markPriorRowExitSubmitted(TradeExecutionRecord row, String reason) {
        Instant now = Instant.now();
        row.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        row.setStatusReason(reason);
        row.setExitSubmittedAt(now);
        row.setUpdatedAt(now);
        executionRepository.save(row);
        log.info("WTX [{}] prior execution row {} → EXIT_SUBMITTED — {}", row.getInstrument(), row.getId(), reason);
    }

    /** The bridge's own (WTX_AUTO) most-recent non-terminal execution for an instrument. */
    private Optional<TradeExecutionRecord> findOpenWtxExecution(String instrument) {
        return executionRepository.findActiveByInstrumentAndTriggerSource(
                instrument, ExecutionTriggerSource.WTX_AUTO);
    }

    /**
     * Maps a WTX action to the broker-side direction token carried on {@code TradeExecutionRecord.action}
     * and {@code BrokerEntryOrderRequest.action}.
     *
     * Returns "LONG" / "SHORT" — the convention {@code IbGatewayBrokerGateway} understands
     * (only "SHORT" maps to {@code Action.SELL}; anything else is a BUY) and which mentor
     * reviews already use. "BUY"/"SELL" would be silently treated as a BUY by the native
     * gateway, so a CLOSE_LONG would increase the long instead of flattening it.
     */
    private String mapToOrderAction(WtxAction action) {
        return switch (action) {
            case OPEN_LONG, REVERSE_TO_LONG, CLOSE_SHORT -> "LONG";   // buy side
            case OPEN_SHORT, REVERSE_TO_SHORT, CLOSE_LONG -> "SHORT"; // sell side
            default -> "";
        };
    }

    /** Resulting WTX position size (contracts). Never the doubled REVERSE order quantity. */
    private int positionQuantity(WtxStrategyState state) {
        BigDecimal baseQty = state.entryQty();
        if (baseQty == null || baseQty.signum() <= 0) {
            baseQty = BigDecimal.ONE;
        }
        return baseQty.intValue();
    }

    private BigDecimal normalizeToTick(BigDecimal price, Instrument instrument) {
        BigDecimal tickSize = instrument.getTickSize();
        return price.divide(tickSize, 0, RoundingMode.HALF_UP)
                .multiply(tickSize)
                .setScale(tickSize.scale(), RoundingMode.HALF_UP);
    }

    private String firstNonBlank(String a, String fallback) {
        return a == null || a.isBlank() ? fallback : a;
    }

    private String truncate(String value, int max) {
        if (value == null) return "(no message)";
        return value.length() <= max ? value : value.substring(0, max);
    }
}

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
 * convention of the rest of the execution stack, and every broker order is a 1:1
 * {@code order ↔ row} pair the standard fill tracker can reconcile:
 * <ul>
 *   <li>{@code action} carries the broker-side direction token {@code LONG}/{@code SHORT},
 *       NOT the WTX enum name — this is what {@code IbGatewayBrokerGateway} interprets
 *       correctly and what {@code ActivePositionView} resolves to direction + PnL sign.</li>
 *   <li>OPEN creates one entry row ({@code ENTRY_SUBMITTED}) for one broker order.</li>
 *   <li>CLOSE never creates a new row — it locates the bridge's own open WTX execution and
 *       transitions it to {@code EXIT_SUBMITTED}; the fill tracker reconciles it to
 *       {@code CLOSED} on the Filled callback.</li>
 *   <li>REVERSE is decomposed into TWO independent orders: a close leg against the prior
 *       row (→ {@code EXIT_SUBMITTED}) and an open leg for the new row (→ {@code ENTRY_SUBMITTED}).
 *       Each leg carries its own {@code ibkrOrderId} so both rows are reconciled by the
 *       standard fill tracker — no "one order, two rows" impedance mismatch.</li>
 *   <li>The broker order id is persisted on {@code ibkrOrderId} at submission time for every
 *       leg, because {@code ExecutionFillTrackingService.onOrderStatus} locates rows only by
 *       {@code orderId}.</li>
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
     * OPEN / REVERSE — open a new WTX position row + broker order.
     *
     * A REVERSE is decomposed into TWO independent 1:1 broker-order ↔ execution-row pairs:
     * first a close leg against the prior row (see {@link #submitCloseLeg}), then this open
     * leg for the new position. Modelling it as two real orders — instead of one doubled
     * order — means each row is reconciled by the standard fill tracker via its own
     * {@code ibkrOrderId}; the prior row is never stranded as a terminal-before-fill or an
     * orphaned non-terminal row. All open-leg validation runs before the close leg, so a
     * duplicate / missing-price reverse never fires a close that can't be followed by an open.
     */
    private void handleEntry(WtxSignal signal, WtxStrategyState state, Instrument instrument,
                             WtxAction action, BigDecimal referencePrice) {
        boolean isReverse = action == WtxAction.REVERSE_TO_LONG || action == WtxAction.REVERSE_TO_SHORT;

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
        String orderAction = mapToOrderAction(action);

        // REVERSE: flatten the prior position with its own close-leg order BEFORE opening the
        // new one. Every open-leg precondition is already validated above, so the close leg
        // only fires once we know the open will proceed. If the close leg is rejected we abort
        // — nothing is opened and the live position keeps its active row for the next bar.
        if (isReverse) {
            Optional<TradeExecutionRecord> prior = findOpenWtxExecution(state.instrument());
            if (prior.isPresent() && prior.get().getStatus() != ExecutionStatus.EXIT_SUBMITTED) {
                TradeExecutionRecord priorRow = prior.get();
                int priorQty = priorRow.getQuantity() != null && priorRow.getQuantity() > 0
                        ? priorRow.getQuantity() : positionQty;
                BigDecimal priorPrice = referencePrice != null ? referencePrice : priorRow.getNormalizedEntryPrice();
                if (priorPrice == null) {
                    log.warn("WTX [{}] missing price for {} close leg — aborting reverse", state.instrument(), action);
                    return;
                }
                boolean closed = submitCloseLeg(priorRow, instrument, orderAction, priorQty, priorPrice,
                        "WTX reversed by " + action.name());
                if (!closed) {
                    log.warn("WTX [{}] reverse close leg rejected — new position not opened", state.instrument());
                    return;
                }
            }
        }

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
                    positionQty,
                    persisted.getNormalizedEntryPrice()
            ));
            persisted.setEntryOrderId(submission.brokerOrderId());
            // Persist the broker order id on ibkrOrderId too: ExecutionFillTrackingService.onOrderStatus
            // locates rows ONLY by orderId (it receives no orderRef), so without this an early
            // orderStatus(Filled) before execDetails would be dropped and the row would never
            // leave ENTRY_SUBMITTED.
            persisted.setIbkrOrderId(toIbkrOrderId(submission.brokerOrderId()));
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("WTX " + action.name() + " submitted: " + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submission.submittedAt() != null ? submission.submittedAt() : Instant.now());
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("WTX [{}] IBKR open leg submitted — action={} positionQty={} orderAction={} brokerOrderId={}",
                    state.instrument(), action, positionQty, orderAction, submission.brokerOrderId());
        } catch (RuntimeException e) {
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason("WTX " + action.name() + " failed: " + truncate(e.getMessage(), 200));
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.error("WTX [{}] IBKR submission failed for {} — {}",
                    state.instrument(), action, e.getMessage(), e);
        }
    }

    /**
     * CLOSE — submit the broker-side flatten order against the bridge's own open WTX
     * execution row. Never creates a new row, so an ATR / max-loss / NY-force exit can't
     * leak a phantom "active" close order. When no open WTX row exists the close is logged
     * and skipped — submitting a naked order would risk opening an unintended position.
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
        BigDecimal price = referencePrice != null ? referencePrice : row.getNormalizedEntryPrice();
        if (price == null) {
            log.warn("WTX [{}] missing reference price for {} — cannot submit close", state.instrument(), action);
            return;
        }
        submitCloseLeg(row, instrument, mapToOrderAction(action), qty, price, "WTX " + action.name());
    }

    /**
     * Submits a broker-side flatten order against an existing WTX execution row and
     * transitions it to {@code EXIT_SUBMITTED} (non-terminal). Shared by {@link #handleClose}
     * and the close leg of a REVERSE.
     *
     * <p>The row stays non-terminal until {@code ExecutionFillTrackingService} reconciles the
     * fill to {@code CLOSED}. That callback is located ONLY by {@code orderId}
     * ({@code onOrderStatus} receives no {@code orderRef}), so the broker order id is persisted
     * on {@code ibkrOrderId} here — otherwise an early {@code Filled} status arriving before
     * {@code execDetails} would be dropped and the row would stay {@code EXIT_SUBMITTED} forever.</p>
     *
     * @return {@code true} when the broker accepted the order; {@code false} on submission failure
     *         (the row is left non-terminal so the position stays visible and the close is retryable).
     */
    private boolean submitCloseLeg(TradeExecutionRecord row, Instrument instrument,
                                   String orderAction, int qty, BigDecimal price, String reasonPrefix) {
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
            row.setStatus(ExecutionStatus.EXIT_SUBMITTED);
            row.setIbkrOrderId(toIbkrOrderId(submission.brokerOrderId()));
            row.setStatusReason(reasonPrefix + " — IBKR close submitted: " + submission.brokerOrderStatus()
                    + " (broker order " + submission.brokerOrderId() + ")");
            row.setExitSubmittedAt(now);
            row.setUpdatedAt(now);
            executionRepository.save(row);
            log.info("WTX [{}] IBKR close leg submitted — orderAction={} qty={} executionId={} brokerOrderId={}",
                    row.getInstrument(), orderAction, qty, row.getId(), submission.brokerOrderId());
            return true;
        } catch (RuntimeException e) {
            // Keep the row non-terminal so the open position stays visible and the close is retryable.
            row.setStatusReason(reasonPrefix + " close failed: " + truncate(e.getMessage(), 200));
            row.setUpdatedAt(Instant.now());
            executionRepository.save(row);
            log.error("WTX [{}] IBKR close leg failed — {}", row.getInstrument(), e.getMessage(), e);
            return false;
        }
    }

    /** IBKR order ids fit in the int range; {@code ibkrOrderId} is the Integer key the fill tracker uses. */
    private Integer toIbkrOrderId(Long brokerOrderId) {
        return brokerOrderId == null ? null : brokerOrderId.intValue();
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

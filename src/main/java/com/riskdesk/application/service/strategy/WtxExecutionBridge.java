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
 *   <li>{@code action} carries the broker-side {@code BUY}/{@code SELL} string, NOT the WTX
 *       enum name — {@code ActivePositionView} derives direction + PnL sign from it.</li>
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
     * For a REVERSE, the prior WTX execution is closed first so the broker net
     * position and the Active Positions feed stay consistent.
     */
    private void handleEntry(WtxSignal signal, WtxStrategyState state, Instrument instrument,
                             WtxAction action, BigDecimal referencePrice) {
        boolean isReverse = action == WtxAction.REVERSE_TO_LONG || action == WtxAction.REVERSE_TO_SHORT;

        if (isReverse) {
            findOpenWtxExecution(state.instrument()).ifPresent(prior ->
                    closeExecutionRow(prior, "WTX reversed by " + action.name()));
        }

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
        String ibkrSide = mapToIbkrSide(action);

        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey(executionKey);
        candidate.setMentorSignalReviewId(null);
        candidate.setReviewAlertKey(null);
        candidate.setReviewRevision(null);
        candidate.setBrokerAccountId(firstNonBlank(wtxProperties.getBrokerAccountId(), "wtx-default"));
        candidate.setInstrument(state.instrument());
        candidate.setTimeframe(signal.timeframe());
        // Broker-side action so ActivePositionView derives the correct direction + PnL sign.
        candidate.setAction(ibkrSide);
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
                    ibkrSide,
                    orderQty,
                    persisted.getNormalizedEntryPrice()
            ));
            persisted.setEntryOrderId(submission.brokerOrderId());
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("WTX " + action.name() + " submitted: " + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submission.submittedAt() != null ? submission.submittedAt() : Instant.now());
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("WTX [{}] IBKR entry submitted — action={} positionQty={} orderQty={} ibkrSide={} brokerOrderId={}",
                    state.instrument(), action, positionQty, orderQty, ibkrSide, submission.brokerOrderId());
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
     * execution row and transition that row to {@code CLOSED}. Never creates a new row,
     * so an ATR / max-loss / NY-force exit can't leak a phantom "active" close order.
     * When no open WTX row exists the close is logged and skipped — submitting a naked
     * order would risk opening an unintended position.
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
        int qty = row.getQuantity() != null && row.getQuantity() > 0 ? row.getQuantity() : positionQuantity(state);
        if (qty <= 0) {
            log.debug("WTX [{}] non-positive close quantity {} — skipping", state.instrument(), qty);
            return;
        }
        String ibkrSide = mapToIbkrSide(action);
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
                    ibkrSide,
                    qty,
                    normalizeToTick(price, instrument)
            ));
            Instant now = Instant.now();
            row.setStatus(ExecutionStatus.CLOSED);
            row.setStatusReason("WTX " + action.name() + " — IBKR close submitted: " + submission.brokerOrderStatus());
            row.setExitSubmittedAt(now);
            row.setClosedAt(now);
            row.setUpdatedAt(now);
            executionRepository.save(row);
            log.info("WTX [{}] IBKR close submitted — action={} qty={} ibkrSide={} closedExecutionId={} brokerOrderId={}",
                    state.instrument(), action, qty, ibkrSide, row.getId(), submission.brokerOrderId());
        } catch (RuntimeException e) {
            // Keep the row non-terminal so the open position stays visible and the close is retryable.
            row.setStatusReason("WTX " + action.name() + " close failed: " + truncate(e.getMessage(), 200));
            row.setUpdatedAt(Instant.now());
            executionRepository.save(row);
            log.error("WTX [{}] IBKR close submission failed for {} — {}",
                    state.instrument(), action, e.getMessage(), e);
        }
    }

    /** Transitions an existing WTX execution row to CLOSED without touching the broker. */
    private void closeExecutionRow(TradeExecutionRecord row, String reason) {
        Instant now = Instant.now();
        row.setStatus(ExecutionStatus.CLOSED);
        row.setStatusReason(reason);
        row.setExitSubmittedAt(now);
        row.setClosedAt(now);
        row.setUpdatedAt(now);
        executionRepository.save(row);
        log.info("WTX [{}] execution row {} closed — {}", row.getInstrument(), row.getId(), reason);
    }

    /** The bridge's own (WTX_AUTO) most-recent non-terminal execution for an instrument. */
    private Optional<TradeExecutionRecord> findOpenWtxExecution(String instrument) {
        return executionRepository.findActiveByInstrumentAndTriggerSource(
                instrument, ExecutionTriggerSource.WTX_AUTO);
    }

    private String mapToIbkrSide(WtxAction action) {
        return switch (action) {
            case OPEN_LONG, REVERSE_TO_LONG, CLOSE_SHORT -> "BUY";
            case OPEN_SHORT, REVERSE_TO_SHORT, CLOSE_LONG -> "SELL";
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

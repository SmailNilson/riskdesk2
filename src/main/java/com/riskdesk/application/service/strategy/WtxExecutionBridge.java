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
 * Idempotence: a {@link TradeExecutionRecord} is created with executionKey {@code wtx:<instrument>:<signalTs>:<action>}.
 * The repository's createIfAbsent honours the unique constraint on the key.
 *
 * REVERSE_* actions are submitted as a single double-quantity order — IBKR transparently flattens the
 * opposite side and opens the new direction in one fill.
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

        String ibkrAction = mapToIbkrSide(action);
        int qty = orderQuantity(action, state);
        if (qty <= 0) {
            log.debug("WTX [{}] non-positive quantity {} — skipping", state.instrument(), qty);
            return;
        }

        Instrument instrument;
        try {
            instrument = Instrument.valueOf(state.instrument());
        } catch (IllegalArgumentException e) {
            log.warn("WTX execution bridge: unknown instrument {}", state.instrument());
            return;
        }

        String executionKey = "wtx:" + state.instrument() + ":" + signal.signalTs().getEpochSecond() + ":" + action.name();
        Optional<TradeExecutionRecord> existing = executionRepository.findByExecutionKey(executionKey);
        if (existing.isPresent()) {
            log.debug("WTX [{}] duplicate execution for {} — skipping", state.instrument(), executionKey);
            return;
        }

        BigDecimal price = referencePrice != null ? referencePrice : state.entryPrice();
        if (price == null) {
            log.warn("WTX [{}] missing reference price for {} — cannot submit", state.instrument(), action);
            return;
        }

        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey(executionKey);
        candidate.setMentorSignalReviewId(null);
        candidate.setReviewAlertKey(null);
        candidate.setReviewRevision(null);
        candidate.setBrokerAccountId(firstNonBlank(wtxProperties.getBrokerAccountId(), "wtx-default"));
        candidate.setInstrument(state.instrument());
        candidate.setTimeframe(signal.timeframe());
        candidate.setAction(action.name());
        candidate.setQuantity(qty);
        candidate.setTriggerSource(ExecutionTriggerSource.WTX_AUTO);
        candidate.setRequestedBy("wtx-strategy");
        candidate.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        candidate.setStatusReason("WTX auto-execution armed");
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
                    ibkrAction,
                    qty,
                    persisted.getNormalizedEntryPrice()
            ));
            persisted.setEntryOrderId(submission.brokerOrderId());
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("WTX entry submitted: " + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submission.submittedAt() != null ? submission.submittedAt() : Instant.now());
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("WTX [{}] IBKR order submitted — action={} qty={} ibkrSide={} brokerOrderId={}",
                    state.instrument(), action, qty, ibkrAction, submission.brokerOrderId());
        } catch (RuntimeException e) {
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason("WTX entry failed: " + truncate(e.getMessage(), 200));
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.error("WTX [{}] IBKR submission failed for {} — {}",
                    state.instrument(), action, e.getMessage(), e);
        }
    }

    private String mapToIbkrSide(WtxAction action) {
        return switch (action) {
            case OPEN_LONG, REVERSE_TO_LONG, CLOSE_SHORT -> "BUY";
            case OPEN_SHORT, REVERSE_TO_SHORT, CLOSE_LONG -> "SELL";
            default -> "";
        };
    }

    private int orderQuantity(WtxAction action, WtxStrategyState state) {
        BigDecimal baseQty = state.entryQty();
        if (baseQty == null || baseQty.signum() <= 0) {
            baseQty = BigDecimal.ONE;
        }
        // REVERSE: close opposite (qty) + open new (qty) = 2x
        boolean isReverse = action == WtxAction.REVERSE_TO_LONG || action == WtxAction.REVERSE_TO_SHORT;
        return baseQty.intValue() * (isReverse ? 2 : 1);
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

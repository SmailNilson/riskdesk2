package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrMarginPreflightService;
import com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookSignal;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.config.PlaybookStrategyProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * Routes Playbook strategy actions to IBKR via the existing IbkrOrderService.
 * Uses limit orders at the mechanical plan's entry price to guarantee risk/reward.
 */
@Service
@ConditionalOnProperty(name = "riskdesk.playbook.enabled", havingValue = "true")
public class PlaybookExecutionBridge {

    private static final Logger log = LoggerFactory.getLogger(PlaybookExecutionBridge.class);

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrProperties ibkrProperties;
    private final PlaybookStrategyProperties properties;
    private final IbkrMarginPreflightService marginPreflight;

    @Autowired
    public PlaybookExecutionBridge(IbkrOrderService ibkrOrderService,
                                   TradeExecutionRepositoryPort executionRepository,
                                   IbkrProperties ibkrProperties,
                                   PlaybookStrategyProperties properties,
                                   @Autowired(required = false) IbkrMarginPreflightService marginPreflight) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.properties = properties;
        this.marginPreflight = marginPreflight;
    }

    /**
     * Submits an entry Limit order for Playbook Strategy.
     */
    public WtxRoutingResult submitEntry(PlaybookSignal signal, PlaybookStrategyState state) {
        if (signal == null || state == null) return null;
        if (!state.autoExecutionEnabled()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_AUTO_OFF);
        }
        if (!ibkrProperties.isEnabled()) {
            log.info("Playbook [{} {}] routing skipped — IBKR disabled in backend", state.instrument(), state.timeframe());
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_IBKR_DISABLED);
        }

        String tf = signal.timeframe();
        String executionKey = "playbook:" + state.instrument() + ":" + tf + ":" + signal.id().toString();
        if (executionRepository.findByExecutionKey(executionKey).isPresent()) {
            log.info("Playbook [{} {}] routing skipped — duplicate execution for {}", state.instrument(), tf, executionKey);
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_DUPLICATE);
        }

        BigDecimal price = signal.entryPrice();
        if (price == null) {
            log.info("Playbook [{} {}] routing skipped — missing reference price", state.instrument(), tf);
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_PRICE);
        }

        int positionQty = state.configuredOrderQty() > 0 ? state.configuredOrderQty() : 2;
        String orderAction = "LONG".equalsIgnoreCase(signal.direction()) ? "LONG" : "SHORT";

        Instrument instrument;
        try {
            instrument = Instrument.valueOf(state.instrument());
        } catch (IllegalArgumentException e) {
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED, "Unknown instrument: " + state.instrument());
        }

        if (marginPreflight != null) {
            PreflightDecision decision = marginPreflight.canAffordOrder(instrument, orderAction, positionQty, price);
            if (!decision.allowed()) {
                log.warn("Playbook [{} {}] routing denied by pre-flight — {}", state.instrument(), tf, decision.denyReason());
                return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, truncate(decision.denyReason(), 200));
            }
        }

        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey(executionKey);
        candidate.setBrokerAccountId(firstNonBlank(properties.getBrokerAccountId(), "playbook-default"));
        candidate.setInstrument(state.instrument());
        candidate.setTimeframe(tf);
        candidate.setAction(orderAction);
        candidate.setQuantity(positionQty);
        candidate.setTriggerSource(ExecutionTriggerSource.PLAYBOOK_AUTO);
        candidate.setRequestedBy("playbook-strategy");
        candidate.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        candidate.setStatusReason("Playbook entry armed");
        candidate.setNormalizedEntryPrice(normalizeToTick(price, instrument));
        candidate.setVirtualStopLoss(signal.stopLoss());
        candidate.setVirtualTakeProfit(signal.takeProfit1());
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
            persisted.setIbkrOrderId(toIbkrOrderId(submission.brokerOrderId()));
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("Playbook entry submitted: " + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submission.submittedAt() != null ? submission.submittedAt() : Instant.now());
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("Playbook [{} {}] IBKR entry submitted — orderAction={} qty={} brokerOrderId={}",
                    state.instrument(), tf, orderAction, positionQty, submission.brokerOrderId());
            return WtxRoutingResult.of(WtxRoutingOutcome.ROUTED);
        } catch (IbkrOrderRejectionException e) {
            String brokerText = e.brokerMessage() != null ? e.brokerMessage() : e.getMessage();
            String shortMsg = truncate(brokerText, 200);
            WtxRoutingOutcome outcome;
            switch (e.kind()) {
                case INSUFFICIENT_MARGIN -> {
                    outcome = WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN;
                    persisted.setStatusReason("Playbook entry skipped — insufficient margin: " + shortMsg);
                }
                case TIMEOUT -> {
                    Long brokerOrderId = e.brokerOrderId();
                    if (brokerOrderId != null) {
                        outcome = WtxRoutingOutcome.ACK_PENDING;
                        persistAckPending(persisted, brokerOrderId, ExecutionStatus.ENTRY_SUBMITTED);
                        persisted.setStatusReason("Playbook entry sent to IBKR; acknowledgement pending (broker order " + brokerOrderId + ")");
                    } else {
                        outcome = WtxRoutingOutcome.FAILED_TIMEOUT;
                        persisted.setStatusReason("Playbook entry timeout — ack lost, manual reconcile required");
                    }
                }
                case BROKER_REJECT, CANCELLED, UNKNOWN -> {
                    outcome = WtxRoutingOutcome.FAILED_BROKER_REJECT;
                    persisted.setStatus(ExecutionStatus.FAILED);
                    persisted.setStatusReason("Playbook entry rejected: " + shortMsg);
                }
                default -> {
                    outcome = WtxRoutingOutcome.FAILED;
                    persisted.setStatus(ExecutionStatus.FAILED);
                    persisted.setStatusReason("Playbook entry failed: " + shortMsg);
                }
            }
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            return WtxRoutingResult.of(outcome, shortMsg);
        } catch (RuntimeException e) {
            String msg = truncate(e.getMessage(), 200);
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason("Playbook entry failed: " + msg);
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED, msg);
        }
    }

    /**
     * Submits a close Limit order for Playbook Strategy.
     */
    public WtxRoutingResult submitClose(PlaybookStrategyState state, BigDecimal referencePrice) {
        if (state == null) return null;
        if (!state.autoExecutionEnabled()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_AUTO_OFF);
        }
        if (!ibkrProperties.isEnabled()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_IBKR_DISABLED);
        }

        Optional<TradeExecutionRecord> open = executionRepository.findActiveByInstrumentAndTimeframeAndTriggerSource(
                state.instrument(), state.timeframe(), ExecutionTriggerSource.PLAYBOOK_AUTO);
        if (open.isEmpty()) {
            log.info("Playbook [{} {}] close requested but no active tracking row found", state.instrument(), state.timeframe());
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW);
        }

        TradeExecutionRecord row = open.get();
        if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_DUPLICATE);
        }

        int qty = row.getQuantity() != null && row.getQuantity() > 0 ? row.getQuantity() : state.configuredOrderQty();
        BigDecimal price = referencePrice != null ? referencePrice : row.getNormalizedEntryPrice();
        if (price == null) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_PRICE);
        }

        String orderAction = "LONG".equalsIgnoreCase(row.getAction()) ? "SHORT" : "LONG";

        try {
            Instrument instrument = Instrument.valueOf(state.instrument());
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
            row.setStatusReason("Playbook close submitted: " + submission.brokerOrderStatus()
                    + " (broker order " + submission.brokerOrderId() + ")");
            row.setExitSubmittedAt(now);
            row.setUpdatedAt(now);
            executionRepository.save(row);
            log.info("Playbook [{} {}] IBKR close submitted — orderAction={} qty={} executionId={} brokerOrderId={}",
                    row.getInstrument(), row.getTimeframe(), orderAction, qty, row.getId(), submission.brokerOrderId());
            return WtxRoutingResult.of(WtxRoutingOutcome.ROUTED);
        } catch (IbkrOrderRejectionException e) {
            String brokerText = e.brokerMessage() != null ? e.brokerMessage() : e.getMessage();
            String shortMsg = truncate(brokerText, 200);
            Long brokerOrderId = e.brokerOrderId();
            WtxRoutingOutcome outcome = switch (e.kind()) {
                case INSUFFICIENT_MARGIN -> WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN;
                case TIMEOUT -> brokerOrderId != null
                        ? WtxRoutingOutcome.ACK_PENDING
                        : WtxRoutingOutcome.FAILED_TIMEOUT;
                case BROKER_REJECT, CANCELLED -> WtxRoutingOutcome.FAILED_BROKER_REJECT;
                default -> WtxRoutingOutcome.FAILED;
            };
            row.setUpdatedAt(Instant.now());
            if (outcome == WtxRoutingOutcome.ACK_PENDING) {
                persistAckPending(row, brokerOrderId, ExecutionStatus.EXIT_SUBMITTED);
                row.setStatusReason("Playbook close sent to IBKR; acknowledgement pending (broker order " + brokerOrderId + ")");
            } else {
                row.setStatusReason("Playbook close failed: " + shortMsg);
            }
            executionRepository.save(row);
            return WtxRoutingResult.of(outcome, shortMsg);
        } catch (RuntimeException e) {
            String shortMsg = truncate(e.getMessage(), 200);
            row.setStatusReason("Playbook close failed: " + shortMsg);
            row.setUpdatedAt(Instant.now());
            executionRepository.save(row);
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED, shortMsg);
        }
    }

    private void persistAckPending(TradeExecutionRecord row, Long brokerOrderId, ExecutionStatus submittedStatus) {
        row.setIbkrOrderId(toIbkrOrderId(brokerOrderId));
        row.setStatus(submittedStatus);
        Instant now = Instant.now();
        if (submittedStatus == ExecutionStatus.ENTRY_SUBMITTED) {
            row.setEntryOrderId(brokerOrderId);
            if (row.getEntrySubmittedAt() == null) {
                row.setEntrySubmittedAt(now);
            }
        } else if (submittedStatus == ExecutionStatus.EXIT_SUBMITTED) {
            if (row.getExitSubmittedAt() == null) {
                row.setExitSubmittedAt(now);
            }
        }
    }

    private Integer toIbkrOrderId(Long brokerOrderId) {
        return brokerOrderId == null ? null : brokerOrderId.intValue();
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

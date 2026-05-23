package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiRiskPlan;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignal;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    public IbkrWtxRsiExecutionBridge(
            IbkrOrderService ibkrOrderService,
            TradeExecutionRepositoryPort executionRepository,
            IbkrProperties ibkrProperties) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
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
            persisted.setEntryOrderId(sub.brokerOrderId());
            if (sub.brokerOrderId() != null) {
                persisted.setIbkrOrderId(Math.toIntExact(sub.brokerOrderId()));
            }
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("WTXRSI OPEN submitted: " + sub.brokerOrderStatus());
            persisted.setEntrySubmittedAt(sub.submittedAt() != null ? sub.submittedAt() : Instant.now());
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("WTX-RSI [{} {}] OPEN submitted — side={} qty={} entry={} brokerOrderId={}",
                    state.instrument(), state.timeframe(), brokerAction,
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
        Optional<TradeExecutionRecord> openRow = executionRepository
                .findActiveByInstrumentAndTimeframeAndTriggerSource(
                        state.instrument(), state.timeframe(), ExecutionTriggerSource.WTXRSI_AUTO);
        if (openRow.isEmpty()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW);
        }

        TradeExecutionRecord row = openRow.get();
        String closeAction = state.currentPosition() == WtxRsiPosition.LONG ? "SHORT" : "LONG";
        int qty = state.entryQty().intValue();
        BigDecimal exitPrice = referencePrice;
        String executionKey = executionKey(state.instrument(), state.timeframe(),
                Instant.now(), action.name());

        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey(executionKey);
        candidate.setBrokerAccountId(row.getBrokerAccountId());
        candidate.setInstrument(state.instrument());
        candidate.setTimeframe(state.timeframe());
        candidate.setAction(closeAction);
        candidate.setQuantity(qty);
        candidate.setTriggerSource(ExecutionTriggerSource.WTXRSI_AUTO);
        candidate.setRequestedBy(REQUESTED_BY);
        candidate.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        candidate.setStatusReason("WTXRSI " + action.name() + " armed");
        candidate.setNormalizedEntryPrice(exitPrice);
        candidate.setCreatedAt(Instant.now());
        candidate.setUpdatedAt(Instant.now());
        TradeExecutionRecord closeRow = executionRepository.createIfAbsent(candidate);

        try {
            BrokerEntryOrderSubmission sub = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                    closeRow.getId(), closeRow.getExecutionKey(), closeRow.getBrokerAccountId(),
                    closeRow.getInstrument(), closeAction, qty, exitPrice));
            closeRow.setEntryOrderId(sub.brokerOrderId());
            if (sub.brokerOrderId() != null) {
                closeRow.setIbkrOrderId(Math.toIntExact(sub.brokerOrderId()));
            }
            closeRow.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            closeRow.setStatusReason("WTXRSI " + action.name() + " submitted: " + sub.brokerOrderStatus());
            closeRow.setEntrySubmittedAt(sub.submittedAt() != null ? sub.submittedAt() : Instant.now());
            closeRow.setUpdatedAt(Instant.now());
            executionRepository.save(closeRow);
            log.info("WTX-RSI [{} {}] CLOSE submitted — direction={} qty={} brokerOrderId={}",
                    state.instrument(), state.timeframe(), closeAction, qty, sub.brokerOrderId());
            return WtxRoutingResult.of(WtxRoutingOutcome.ROUTED);
        } catch (RuntimeException e) {
            String msg = truncate(e.getMessage(), 200);
            closeRow.setStatus(ExecutionStatus.FAILED);
            closeRow.setStatusReason("WTXRSI CLOSE failed: " + msg);
            closeRow.setUpdatedAt(Instant.now());
            executionRepository.save(closeRow);
            log.error("WTX-RSI [{} {}] CLOSE failed: {}",
                    state.instrument(), state.timeframe(), msg, e);
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED, msg);
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

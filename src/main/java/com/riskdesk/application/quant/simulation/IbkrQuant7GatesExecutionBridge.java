package com.riskdesk.application.quant.simulation;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
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
 * IBKR implementation of {@link Quant7GatesExecutionBridge}. Built only when
 * {@code riskdesk.quant.sim-exec.enabled=true}; mirrors the contract of
 * {@code IbkrWtxRsiExecutionBridge} (one entry row per OPEN; CLOSE transitions
 * the existing row to {@code EXIT_SUBMITTED}, never creates a new one).
 *
 * <p>Reconciliation is delegated to the shared, source-agnostic
 * {@code ExecutionFillTrackingService} (it locates rows by {@code ibkrOrderId}).</p>
 */
@Service
@ConditionalOnProperty(name = "riskdesk.quant.sim-exec.enabled", havingValue = "true")
public class IbkrQuant7GatesExecutionBridge implements Quant7GatesExecutionBridge {

    private static final Logger log = LoggerFactory.getLogger(IbkrQuant7GatesExecutionBridge.class);

    private static final String REQUESTED_BY = "quant-sim";
    /** The quant pipeline is a 5m evaluator — scope execution rows to that timeframe. */
    private static final String TIMEFRAME = "5m";

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrProperties ibkrProperties;
    private final QuantSimExecutionProperties props;
    private final QuantSimExecutionState toggleState;

    public IbkrQuant7GatesExecutionBridge(IbkrOrderService ibkrOrderService,
                                          TradeExecutionRepositoryPort executionRepository,
                                          IbkrProperties ibkrProperties,
                                          QuantSimExecutionProperties props,
                                          QuantSimExecutionState toggleState) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.props = props;
        this.toggleState = toggleState;
        // Fail fast at startup: a live-order bridge without a broker account is a
        // misconfiguration, not a runtime condition to discover on the first signal.
        if (props.getBrokerAccountId() == null || props.getBrokerAccountId().isBlank()) {
            throw new IllegalStateException(
                "riskdesk.quant.sim-exec.broker-account-id is required when riskdesk.quant.sim-exec.enabled=true");
        }
    }

    @Override
    public RoutingResult submitOpen(Quant7GatesSimulation opened) {
        if (opened == null) return RoutingResult.of(RoutingOutcome.SKIPPED_NO_PRICE, "null simulation");
        Instrument instrument = opened.instrument();
        String dir = opened.direction() == Quant7GatesSimulation.Direction.LONG ? "LONG" : "SHORT";

        if (!ibkrProperties.isEnabled()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_IBKR_DISABLED);
        }
        if (!props.isAllowed(instrument.name())) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_AUTO_OFF, instrument.name() + " not in sim-exec allowlist");
        }
        if (!toggleState.isEnabled(instrument)) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_AUTO_OFF, "Auto-IBKR toggle OFF for " + instrument.name());
        }
        int qty = props.getDefaultQuantity();
        if (qty <= 0) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_NO_QTY);
        }
        BigDecimal entry = normalize(opened.entryPrice(), instrument);
        if (entry == null) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_NO_PRICE);
        }

        String executionKey = executionKey(instrument, dir, opened.openedAt());
        if (executionRepository.findByExecutionKey(executionKey).isPresent()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_DUPLICATE);
        }

        // One position per instrument. If a quant-sim row is already live, skip:
        // an EXIT_SUBMITTED row means a close is in flight (same-tick re-entry) —
        // the simulation retries on the next tick once the row reconciles CLOSED.
        Optional<TradeExecutionRecord> active = executionRepository
            .findActiveByInstrumentAndTimeframeAndTriggerSource(
                instrument.name(), TIMEFRAME, ExecutionTriggerSource.QUANT_SIM_AUTO);
        if (active.isPresent()) {
            return active.get().getStatus() == ExecutionStatus.EXIT_SUBMITTED
                ? RoutingResult.of(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT, "close in flight on " + instrument.name())
                : RoutingResult.of(RoutingOutcome.SKIPPED_DUPLICATE, "position already open on " + instrument.name());
        }

        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey(executionKey);
        candidate.setBrokerAccountId(props.getBrokerAccountId());
        candidate.setInstrument(instrument.name());
        candidate.setTimeframe(TIMEFRAME);
        candidate.setAction(dir);
        candidate.setQuantity(qty);
        candidate.setTriggerSource(ExecutionTriggerSource.QUANT_SIM_AUTO);
        candidate.setRequestedBy(REQUESTED_BY);
        candidate.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        candidate.setStatusReason("QUANT-SIM OPEN armed — " + opened.entryReason());
        candidate.setNormalizedEntryPrice(entry);
        candidate.setVirtualStopLoss(normalize(opened.stopLoss(), instrument));
        candidate.setVirtualTakeProfit(normalize(opened.takeProfit1(), instrument));
        candidate.setCreatedAt(Instant.now());
        candidate.setUpdatedAt(Instant.now());

        TradeExecutionRecord persisted = executionRepository.createIfAbsent(candidate);

        try {
            BrokerEntryOrderSubmission sub = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                persisted.getId(),
                persisted.getExecutionKey(),
                persisted.getBrokerAccountId(),
                persisted.getInstrument(),
                dir,
                qty,
                entry));
            persisted.setEntryOrderId(sub.brokerOrderId());
            if (sub.brokerOrderId() != null) {
                persisted.setIbkrOrderId(Math.toIntExact(sub.brokerOrderId()));
            }
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("QUANT-SIM OPEN submitted: " + sub.brokerOrderStatus());
            persisted.setEntrySubmittedAt(sub.submittedAt() != null ? sub.submittedAt() : Instant.now());
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("QUANT-SIM [{}] OPEN submitted — side={} qty={} entry={} brokerOrderId={} executionId={}",
                instrument, dir, qty, entry, sub.brokerOrderId(), persisted.getId());
            return RoutingResult.tracked(RoutingOutcome.ROUTED, persisted.getId(), sub.brokerOrderId());
        } catch (RuntimeException e) {
            String msg = truncate(e.getMessage(), 200);
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason("QUANT-SIM OPEN failed: " + msg);
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.error("QUANT-SIM [{}] OPEN failed: {}", instrument, msg, e);
            return RoutingResult.tracked(RoutingOutcome.FAILED, msg, persisted.getId(), null);
        }
    }

    @Override
    public RoutingResult submitClose(Quant7GatesSimulation closed) {
        if (closed == null) return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW, "null simulation");
        Instrument instrument = closed.instrument();

        // NOTE: deliberately NOT gated by the toggle/allowlist — a live position
        // must always be closable even after the operator disarms the toggle.
        if (!ibkrProperties.isEnabled()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_IBKR_DISABLED);
        }

        Optional<TradeExecutionRecord> openRow = executionRepository
            .findActiveByInstrumentAndTimeframeAndTriggerSource(
                instrument.name(), TIMEFRAME, ExecutionTriggerSource.QUANT_SIM_AUTO);
        if (openRow.isEmpty()) {
            // No mirrored position (e.g. the OPEN was skipped) — nothing to flatten.
            return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        }
        TradeExecutionRecord row = openRow.get();
        // Only flatten the row THIS paper trade opened: the live position side must
        // match the closing simulation's direction. The harness can hold both a
        // LONG and a SHORT paper sim at once (it calls tryOpen for both sides), but
        // only one is ever mirrored (one-position-per-instrument). A direction
        // mismatch means this paper sim was never mirrored — flattening the active
        // row would close the wrong trade or add to the live position. (Codex P1-B.)
        String positionSide = row.getAction();
        if (positionSide == null || !positionSide.equalsIgnoreCase(closed.direction().name())) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                "active " + positionSide + " row does not match closing " + closed.direction() + " sim");
        }

        BigDecimal exitPrice = closed.exitPrice() != null
            ? normalize(closed.exitPrice(), instrument)
            : row.getNormalizedEntryPrice();
        return doFlatten(row, exitPrice, "CLOSE (" + closed.status() + ")");
    }

    @Override
    public RoutingResult flatten(TradeExecutionRecord row) {
        if (row == null) return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        if (!ibkrProperties.isEnabled()) return RoutingResult.of(RoutingOutcome.SKIPPED_IBKR_DISABLED);
        // Reconciler retry — no live exit price available, fall back to the entry
        // limit (same fallback the close path already uses).
        return doFlatten(row, row.getNormalizedEntryPrice(), "reconcile-flatten");
    }

    /**
     * Shared flatten core. Transitions the EXISTING open row to
     * {@code EXIT_SUBMITTED} — NEVER a new row: the fill tracker only marks
     * EXIT_SUBMITTED rows CLOSED; a fresh ENTRY_SUBMITTED row would reconcile to
     * ACTIVE and inflate the open-position count (same contract as
     * IbkrWtxRsiExecutionBridge). The flatten side is derived from the row's own
     * action (the true live position), not from any caller-supplied direction.
     */
    private RoutingResult doFlatten(TradeExecutionRecord row, BigDecimal exitPrice, String reasonTag) {
        if (isTerminal(row.getStatus())) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW, "row already terminal");
        }
        // Guard against a second flatten while the first close is still in flight.
        if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_DUPLICATE, "flatten already in flight on " + row.getInstrument());
        }
        if (exitPrice == null) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_NO_PRICE);
        }
        int qty = row.getQuantity() != null && row.getQuantity() > 0 ? row.getQuantity() : props.getDefaultQuantity();
        // Flatten: send the opposite side of the live position (from the row, not the caller).
        String closeAction = "LONG".equalsIgnoreCase(row.getAction()) ? "SHORT" : "LONG";

        try {
            BrokerEntryOrderSubmission sub = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                row.getId(),
                row.getExecutionKey(),
                row.getBrokerAccountId(),
                row.getInstrument(),
                closeAction,
                qty,
                exitPrice));
            Instant now = Instant.now();
            row.setStatus(ExecutionStatus.EXIT_SUBMITTED);
            if (sub.brokerOrderId() != null) {
                row.setIbkrOrderId(Math.toIntExact(sub.brokerOrderId()));
            }
            row.setStatusReason("QUANT-SIM " + reasonTag + " submitted: " + sub.brokerOrderStatus());
            row.setExitSubmittedAt(sub.submittedAt() != null ? sub.submittedAt() : now);
            row.setUpdatedAt(now);
            executionRepository.save(row);
            log.info("QUANT-SIM [{}] {} submitted — direction={} qty={} executionId={} brokerOrderId={}",
                row.getInstrument(), reasonTag, closeAction, qty, row.getId(), sub.brokerOrderId());
            return RoutingResult.tracked(RoutingOutcome.ROUTED, row.getId(), sub.brokerOrderId());
        } catch (RuntimeException e) {
            // Keep the row non-terminal so the open position stays visible and the
            // flatten is retried by the reconciler until it actually fills.
            String msg = truncate(e.getMessage(), 200);
            row.setStatusReason("QUANT-SIM " + reasonTag + " failed: " + msg);
            row.setUpdatedAt(Instant.now());
            executionRepository.save(row);
            log.error("QUANT-SIM [{}] {} failed (row {} left non-terminal for retry): {}",
                row.getInstrument(), reasonTag, row.getId(), msg, e);
            return RoutingResult.tracked(RoutingOutcome.FAILED, msg, row.getId(), null);
        }
    }

    private static boolean isTerminal(ExecutionStatus s) {
        return s == ExecutionStatus.CLOSED || s == ExecutionStatus.CANCELLED
            || s == ExecutionStatus.REJECTED || s == ExecutionStatus.FAILED;
    }

    private static String executionKey(Instrument instrument, String dir, Instant ts) {
        return "quant-sim:" + instrument.name() + ":" + dir + ":" + ts.toEpochMilli() + ":OPEN";
    }

    /** Round a price to the instrument tick. Returns {@code null} for non-positive / non-finite input. */
    private static BigDecimal normalize(double price, Instrument instrument) {
        if (!Double.isFinite(price) || price <= 0.0) return null;
        BigDecimal tick = instrument.getTickSize();
        return BigDecimal.valueOf(price)
            .divide(tick, 0, RoundingMode.HALF_UP)
            .multiply(tick)
            .setScale(tick.scale(), RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

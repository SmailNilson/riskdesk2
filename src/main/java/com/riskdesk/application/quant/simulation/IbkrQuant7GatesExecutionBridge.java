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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
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
    /**
     * Placeholder broker account. IBKR's gateway resolves any non-managed value to
     * the session's default managed account ({@code resolveAccountId}), so — like
     * {@code IbkrWtxRsiExecutionBridge} — the mirror needs no configured account.
     */
    private static final String BROKER_ACCOUNT_FALLBACK = "quant-sim-default";
    /** The quant pipeline is a 5m evaluator — scope execution rows to that timeframe. */
    private static final String TIMEFRAME = "5m";
    private static final ZoneId NY = ZoneId.of("America/New_York");
    /** CME daily break — the pre-close entry cutoff window ends here. */
    private static final LocalTime SESSION_RESET = LocalTime.of(17, 0);

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrProperties ibkrProperties;
    private final QuantSimExecutionProperties props;
    private final QuantSimExecutionState toggleState;
    private final Clock clock;

    public IbkrQuant7GatesExecutionBridge(IbkrOrderService ibkrOrderService,
                                          TradeExecutionRepositoryPort executionRepository,
                                          IbkrProperties ibkrProperties,
                                          QuantSimExecutionProperties props,
                                          QuantSimExecutionState toggleState,
                                          Clock clock) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.props = props;
        this.toggleState = toggleState;
        this.clock = clock;
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
        if (inPreCloseCutoff()) {
            // No fresh resting DAY entries right before the CME break — they could
            // fill in the final minutes and carry a position through the close.
            return RoutingResult.of(RoutingOutcome.SKIPPED_AUTO_OFF, "pre-close entry cutoff");
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
        candidate.setBrokerAccountId(BROKER_ACCOUNT_FALLBACK);
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
            // P2 — synchronous fill: when the broker reports the entry already Filled at submit return,
            // mark the row ACTIVE NOW instead of waiting on an orderStatus(Filled) callback that can be
            // dropped (root cause R2 — the callback can arrive before the orderId is persisted). Mirrors
            // DefaultOrderRouter.submitPersistedEntry. Otherwise ENTRY_SUBMITTED (resting limit).
            boolean filled = "Filled".equalsIgnoreCase(sub.brokerOrderStatus());
            Instant submittedAt = sub.submittedAt() != null ? sub.submittedAt() : Instant.now();
            persisted.setEntryOrderId(sub.brokerOrderId());
            if (sub.brokerOrderId() != null) {
                persisted.setIbkrOrderId(Math.toIntExact(sub.brokerOrderId()));
            }
            persisted.setStatus(filled ? ExecutionStatus.ACTIVE : ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("QUANT-SIM OPEN " + (filled ? "filled" : "submitted") + ": " + sub.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submittedAt);
            if (filled && persisted.getEntryFilledAt() == null) {
                persisted.setEntryFilledAt(submittedAt);
            }
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("QUANT-SIM [{}] OPEN {} — side={} qty={} entry={} brokerOrderId={} executionId={}",
                instrument, filled ? "filled" : "submitted", dir, qty, entry, sub.brokerOrderId(), persisted.getId());
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

        BigDecimal refPrice = closed.exitPrice() != null
            ? normalize(closed.exitPrice(), instrument)
            : row.getNormalizedEntryPrice();
        return doFlatten(row, refPrice, "CLOSE (" + closed.status() + ")");
    }

    @Override
    public RoutingResult flatten(TradeExecutionRecord row, BigDecimal marketPrice) {
        if (row == null) return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        if (!ibkrProperties.isEnabled()) return RoutingResult.of(RoutingOutcome.SKIPPED_IBKR_DISABLED);
        // Reconciler / session-close retry — flatten at the current market price
        // (crossed marketable in doFlatten). Fall back to the entry limit only when
        // no live price is available.
        BigDecimal refPrice = marketPrice != null ? marketPrice : row.getNormalizedEntryPrice();
        return doFlatten(row, refPrice, "reconcile-flatten");
    }

    /**
     * Shared flatten core. Transitions the EXISTING open row to
     * {@code EXIT_SUBMITTED} — NEVER a new row: the fill tracker only marks
     * EXIT_SUBMITTED rows CLOSED; a fresh ENTRY_SUBMITTED row would reconcile to
     * ACTIVE and inflate the open-position count (same contract as
     * IbkrWtxRsiExecutionBridge). The flatten side is derived from the row's own
     * action (the true live position), not from any caller-supplied direction.
     */
    private RoutingResult doFlatten(TradeExecutionRecord row, BigDecimal refPrice, String reasonTag) {
        if (isTerminal(row.getStatus())) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW, "row already terminal");
        }
        // Guard against a second flatten while the first close is still in flight.
        if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_DUPLICATE, "flatten already in flight on " + row.getInstrument());
        }
        int qty = row.getQuantity() != null && row.getQuantity() > 0 ? row.getQuantity() : props.getDefaultQuantity();
        // Flatten: send the opposite side of the live position (from the row, not the caller).
        String closeAction = "LONG".equalsIgnoreCase(row.getAction()) ? "SHORT" : "LONG";
        // Price the close marketable — cross the reference price toward the fill
        // side so a losing position is actually flattened, not left resting on the
        // wrong side of the market (Codex P1-C).
        BigDecimal limit = marketableLimit(refPrice, closeAction, row.getInstrument());
        if (limit == null) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_NO_PRICE);
        }

        try {
            BrokerEntryOrderSubmission sub = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                row.getId(),
                row.getExecutionKey(),
                row.getBrokerAccountId(),
                row.getInstrument(),
                closeAction,
                qty,
                limit));
            // P2 — synchronous fill: a marketable close (crossed limit) usually comes back Filled at submit
            // return. Mark the row CLOSED NOW rather than EXIT_SUBMITTED — otherwise the orderStatus(Filled)
            // callback, which can arrive before the close orderId is persisted, is dropped (root cause R2)
            // and the row stays a phantom EXIT_SUBMITTED until the reconciler cleans it up. Mirrors
            // DefaultOrderRouter.submitCloseLeg. A non-Filled (resting) close stays EXIT_SUBMITTED.
            boolean filled = "Filled".equalsIgnoreCase(sub.brokerOrderStatus());
            Instant now = Instant.now();
            row.setStatus(filled ? ExecutionStatus.CLOSED : ExecutionStatus.EXIT_SUBMITTED);
            if (sub.brokerOrderId() != null) {
                row.setIbkrOrderId(Math.toIntExact(sub.brokerOrderId()));
            }
            row.setStatusReason("QUANT-SIM " + reasonTag + " " + (filled ? "filled" : "submitted") + ": " + sub.brokerOrderStatus());
            row.setExitSubmittedAt(sub.submittedAt() != null ? sub.submittedAt() : now);
            if (filled && row.getClosedAt() == null) {
                row.setClosedAt(now);
            }
            row.setUpdatedAt(now);
            executionRepository.save(row);
            log.info("QUANT-SIM [{}] {} {} — direction={} qty={} executionId={} brokerOrderId={}",
                row.getInstrument(), reasonTag, filled ? "filled" : "submitted", closeAction, qty, row.getId(), sub.brokerOrderId());
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

    /**
     * Cross the reference price by {@code flattenCrossTicks} toward the fill side
     * so the close limit is marketable: a SELL (flatten a LONG) is priced below
     * the market, a BUY (flatten a SHORT) above. Returns {@code null} when no
     * reference price is available.
     */
    private BigDecimal marketableLimit(BigDecimal refPrice, String closeAction, String instrumentName) {
        if (refPrice == null) return null;
        Instrument instr;
        try {
            instr = Instrument.valueOf(instrumentName);
        } catch (IllegalArgumentException | NullPointerException e) {
            return refPrice; // cannot resolve tick — use the reference price as-is
        }
        BigDecimal cross = instr.getTickSize().multiply(BigDecimal.valueOf(Math.max(0, props.getFlattenCrossTicks())));
        BigDecimal raw = "SHORT".equals(closeAction) ? refPrice.subtract(cross) : refPrice.add(cross);
        return normalize(raw.doubleValue(), instr);
    }

    /**
     * True inside the pre-close cutoff window [noNewEntriesAfter, 17:00 ET): new
     * entries are refused so no fresh resting DAY order is placed right before the
     * CME break (Codex P1-D).
     */
    private boolean inPreCloseCutoff() {
        LocalTime cutoff = props.getNoNewEntriesAfter();
        if (cutoff == null) return false;
        LocalTime nowEt = LocalTime.now(clock.withZone(NY));
        return !nowEt.isBefore(cutoff) && nowEt.isBefore(SESSION_RESET);
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

package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort.CreateOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * The single entry point through which every strategy submits to IBKR. Owns the shared mechanics —
 * startup gate, idempotence, execution-row persistence, broker submission and typed error mapping —
 * by reusing the existing {@link IbkrOrderService} and {@link TradeExecutionRepositoryPort}; it does
 * not duplicate them. Generalises the proven {@code WtxExecutionBridge.handleEntry} flow.
 *
 * <p>Implements all four {@code IntentKind}s — {@code OPEN}, {@code CLOSE}, {@code FLATTEN} and the
 * two-leg {@code REVERSE} (close-then-open). The router is not yet wired to any strategy (that is the
 * migration step — see docs/PLAN_ORDER_ROUTER_IMPL.md).</p>
 */
@Service
public class DefaultOrderRouter implements OrderRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultOrderRouter.class);

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrProperties ibkrProperties;
    private final ExecutionReadinessGate readinessGate;
    private final ExecutionReconciler reconciler;

    public DefaultOrderRouter(IbkrOrderService ibkrOrderService,
                              TradeExecutionRepositoryPort executionRepository,
                              IbkrProperties ibkrProperties,
                              ExecutionReadinessGate readinessGate,
                              ExecutionReconciler reconciler) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.readinessGate = readinessGate;
        this.reconciler = reconciler;
    }

    @Override
    public RoutingResult route(TradeIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("intent is required");
        }
        if (!readinessGate.isReady()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_RECONCILING,
                "execution core is still reconciling broker truth at startup");
        }
        if (!ibkrProperties.isEnabled()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_IBKR_DISABLED,
                "IBKR is disabled in the backend configuration");
        }
        return switch (intent.kind()) {
            case OPEN, REVERSE -> routeEntry(intent);
            case CLOSE -> executeClose(intent);
            case FLATTEN -> executeFlatten(intent);
        };
    }

    /**
     * Entry orchestration for OPEN / REVERSE. Reconciles against broker position truth first: may
     * downgrade REVERSE→OPEN (broker flat), upgrade OPEN→REVERSE (broker holds the opposite) or skip a
     * duplicate (broker already on the wanted side). A confirmed-flat broker with a stale local row voids
     * the phantom (or skips an in-flight entry) before opening fresh.
     */
    private RoutingResult routeEntry(TradeIntent intent) {
        BrokerPositionState pos = reconciler.readPositionState(intent.brokerAccountId(), intent.instrument());

        // In-flight / phantom precondition: IBKR confirmed flat but we hold a non-terminal local row.
        if (pos.confirmedFlat()) {
            Optional<TradeExecutionRecord> existing = executionRepository.findActiveByInstrumentAndTimeframeAndTriggerSource(
                intent.instrument().name(), intent.timeframe(), intent.source());
            if (existing.isPresent()) {
                TradeExecutionRecord row = existing.get();
                if (row.getStatus() == ExecutionStatus.ACTIVE) {
                    // Filled position the broker no longer holds (manual close / drift). Void it so the new
                    // open is the sole active row and a later close can't flatten it naked.
                    voidRow(row, "OrderRouter " + intent.kind() + " — IBKR flat; stale ACTIVE row voided before entry");
                } else {
                    // An entry order is resting UNFILLED at the broker — opening another risks a double fill.
                    return RoutingResult.tracked(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT,
                        "entry still in flight (" + row.getStatus() + ") — open skipped to avoid double fill",
                        row.getId(), row.getEntryOrderId());
                }
            }
        }

        ReconcilePlan plan = reconciler.reconcile(intent, pos);
        return switch (plan) {
            case ReconcilePlan.Skip s -> RoutingResult.of(s.outcome(), s.message());
            case ReconcilePlan.Open o -> submitEntry(intent, false);
            case ReconcilePlan.Reverse r -> executeReverse(intent, r.toSide(), pos);
            // reconcile never produces Close/Flatten for an OPEN/REVERSE intent.
            case ReconcilePlan.Close c -> throw new IllegalStateException(intent.kind() + " reconciled to Close");
            case ReconcilePlan.Flatten f -> throw new IllegalStateException(intent.kind() + " reconciled to Flatten");
        };
    }

    /**
     * REVERSE — flatten the opposite position, THEN open on {@code toSide}. Two 1:1 legs: the close leg
     * fires FIRST (on the prior local row, or a phantom synthesised from the live IBKR position). If the
     * close ack-pends or is rejected the reverse is ABORTED — the open leg is never attempted (firing it
     * would stack on the un-flattened position). When the close succeeds but the open leg is then rejected,
     * the broker is FLAT — surfaced as {@code ROUTED_FLATTEN_ONLY} (protected, not an error).
     */
    private RoutingResult executeReverse(TradeIntent intent, Side toSide, BrokerPositionState pos) {
        Optional<TradeExecutionRecord> prior = executionRepository.findActiveByInstrumentAndTimeframeAndTriggerSource(
            intent.instrument().name(), intent.timeframe(), intent.source());
        if (prior.isEmpty() && pos.available() && !pos.confirmedFlat()) {
            // No local row but the broker is NOT flat — there is a live position we must flatten first.
            if (pos.net().signum() != 0) {
                // Directional position opened outside us / drift — synthesise a phantom so the close leg
                // flattens the broker side instead of the open stacking on top of it.
                Side heldSide = pos.net().signum() > 0 ? Side.LONG : Side.SHORT;
                int qty = Math.max(1, pos.net().abs().intValue());
                prior = Optional.of(synthesizePhantom(intent, heldSide, qty));
            } else {
                // net==0 but NOT flat = offsetting live legs (rollover / calendar overlap) with no local
                // row. We can't synthesise a single close, and opening would stack on top of the live legs.
                // Skip rather than over-trade (per-leg flatten would be needed — out of scope here).
                return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                    "IBKR holds offsetting live legs (net 0, not flat) and no local row — reverse skipped to avoid stacking");
            }
        }

        boolean closeLegFired = false;
        if (prior.isPresent() && prior.get().getStatus() != ExecutionStatus.EXIT_SUBMITTED) {
            TradeExecutionRecord priorRow = prior.get();
            int priorQty = priorRow.getQuantity() != null && priorRow.getQuantity() > 0
                ? priorRow.getQuantity() : intent.quantity();
            String closeAction = "LONG".equalsIgnoreCase(priorRow.getAction()) ? "SHORT" : "LONG"; // opposite of held
            RoutingResult close = submitCloseLeg(priorRow, intent.instrument(), closeAction, priorQty,
                intent.limitPrice(), "OrderRouter REVERSE close");
            if (close.outcome() == RoutingOutcome.ACK_PENDING) {
                // The close ack-pends: we can't safely fire the open (no fill-driven retry) — the reversal
                // is effectively lost until a reconcile. Surface it loudly.
                log.error("OrderRouter REVERSE close leg ack-pending — open leg NOT attempted, reversal LOST "
                    + "until reconcile: {}", intent.idempotencyKey());
                return RoutingResult.tracked(RoutingOutcome.ACK_PENDING,
                    "reversal lost — close ack pending, open leg not attempted",
                    close.executionId(), close.brokerOrderId());
            }
            if (close.outcome().isFailure()) {
                // Close rejected — abort the reverse; the prior position keeps its (non-terminal) row.
                return close;
            }
            closeLegFired = true;
        }

        return submitEntry(intent, closeLegFired);
    }

    /** Persist a phantom ACTIVE row representing a live IBKR position with no local row, so a close leg
     *  can flatten it. DB-only; a distinct executionKey so it does not collide with the open leg's row. */
    private TradeExecutionRecord synthesizePhantom(TradeIntent intent, Side heldSide, int qty) {
        Instant now = Instant.now();
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setExecutionKey(intent.idempotencyKey() + ":reconcile-close");
        r.setInstrument(intent.instrument().name());
        r.setTimeframe(intent.timeframe());
        r.setAction(heldSide == Side.LONG ? "LONG" : "SHORT");
        r.setQuantity(qty);
        r.setTriggerSource(intent.source());
        r.setBrokerAccountId(intent.brokerAccountId());
        r.setRequestedBy(intent.source().name());
        r.setStatus(ExecutionStatus.ACTIVE);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return executionRepository.createIfAbsent(r);
    }

    /**
     * Submit a fresh entry leg (OPEN, or the open leg of a REVERSE). De-dups via createIfAbsentTracked (no
     * lock, no transaction held across the broker submit). {@code reverseFlattened} = the reverse close leg
     * already flattened the broker, so a subsequent NON-timeout open rejection means the broker is FLAT
     * (protected) → {@code ROUTED_FLATTEN_ONLY}, not a plain failure.
     */
    private RoutingResult submitEntry(TradeIntent intent, boolean reverseFlattened) {
        CreateOutcome createResult = executionRepository.createIfAbsentTracked(toPendingRecord(intent));
        TradeExecutionRecord persisted = createResult.record();
        if (!createResult.created()) {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_DUPLICATE, "execution already exists",
                persisted.getId(), persisted.getEntryOrderId());
        }

        try {
            BrokerEntryOrderSubmission submission = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                persisted.getId(),
                persisted.getExecutionKey(),
                persisted.getBrokerAccountId(),
                persisted.getInstrument(),
                brokerAction(intent),
                persisted.getQuantity(),
                persisted.getNormalizedEntryPrice()));

            // BOTH ids must be set: entryOrderId for the audit, ibkrOrderId (Integer) so the fill
            // tracker can locate this row on the orderStatus/execDetails callbacks.
            persisted.setEntryOrderId(submission.brokerOrderId());
            persisted.setIbkrOrderId(toIbkrOrderId(submission.brokerOrderId()));
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("OrderRouter " + intent.kind() + " submitted: " + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submission.submittedAt() != null ? submission.submittedAt() : Instant.now());
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);

            RoutingOutcome outcome = "PendingSubmit".equalsIgnoreCase(submission.brokerOrderStatus())
                ? RoutingOutcome.ACK_PENDING
                : RoutingOutcome.ROUTED;
            return RoutingResult.tracked(outcome, persisted.getId(), submission.brokerOrderId());

        } catch (IbkrOrderRejectionException e) {
            // Reverse already flattened + a non-timeout open rejection → the broker is FLAT (protected).
            // Surface ROUTED_FLATTEN_ONLY (the caller re-derives virtual state to FLAT) and terminal-fail
            // the never-opened leg. A TIMEOUT is different: broker state is unknown, fall through.
            if (reverseFlattened && e.kind() != IbkrOrderRejectionException.Kind.TIMEOUT) {
                persisted.setStatus(ExecutionStatus.FAILED);
                persisted.setStatusReason(truncate("OrderRouter REVERSE flattened — open leg not opened ("
                    + e.kind() + "): " + e.getMessage(), 256));
                persisted.setUpdatedAt(Instant.now());
                executionRepository.save(persisted);
                log.warn("OrderRouter REVERSE flattened, open leg rejected ({}) — broker FLAT, protected: {}",
                    e.kind(), intent.idempotencyKey());
                return RoutingResult.tracked(RoutingOutcome.ROUTED_FLATTEN_ONLY,
                    "reversed to flat — open leg not opened (" + e.kind() + ")", persisted.getId(), null);
            }

            RoutingOutcome outcome = mapRejection(e);
            // Keep the row NON-TERMINAL whenever the order is — or may be — live at the broker:
            // ACK_PENDING (id present, late ack) AND FAILED_TIMEOUT (no id, broker state UNKNOWN). Late
            // orderStatus/execDetails callbacks (or the stale-entry reconciler) must still resolve it,
            // and terminal-failing here would allow a retry against an order the broker may already hold.
            persisted.setStatus(outcome.mustTrackExecutionRow()
                ? ExecutionStatus.ENTRY_SUBMITTED : ExecutionStatus.FAILED);
            persisted.setStatusReason(truncate("OrderRouter " + intent.kind() + " " + e.kind() + ": " + e.getMessage(), 256));
            if (e.brokerOrderId() != null) {
                persisted.setEntryOrderId(e.brokerOrderId());
                persisted.setIbkrOrderId(toIbkrOrderId(e.brokerOrderId()));
                persisted.setEntrySubmittedAt(Instant.now());
            }
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("OrderRouter {} {} ({}) — {}", intent.kind(), outcome, e.kind(), intent.idempotencyKey());
            return RoutingResult.tracked(outcome, persisted.getId(), e.brokerOrderId());

        } catch (RuntimeException e) {
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason(truncate("OrderRouter " + intent.kind() + " failed: " + e.getMessage(), 256));
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.warn("OrderRouter {} failed for {}: {}", intent.kind(), intent.idempotencyKey(), e.getMessage());
            return RoutingResult.tracked(RoutingOutcome.FAILED, e.getMessage(), persisted.getId(), null);
        }
    }

    /** CLOSE — exit this strategy's open row on the intent's side. */
    private RoutingResult executeClose(TradeIntent intent) {
        return executeExit(intent, false, "OrderRouter CLOSE");
    }

    /** FLATTEN — exit this strategy's open row regardless of side (held side derived from the row). */
    private RoutingResult executeFlatten(TradeIntent intent) {
        return executeExit(intent, true, "OrderRouter FLATTEN");
    }

    /**
     * Single-leg exit (CLOSE / FLATTEN) — port of {@code WtxExecutionBridge.handleClose}. Reads broker
     * position truth: when IBKR is confirmed flat a still-ACTIVE row is a phantom (closed outside us) and
     * is voided DB-only, and an unfilled in-flight entry is left alone (no naked flatten). Otherwise a
     * single close leg is submitted on the existing row.
     */
    private RoutingResult executeExit(TradeIntent intent, boolean flatten, String reasonPrefix) {
        var active = executionRepository.findActiveByInstrumentAndTimeframeAndTriggerSource(
            intent.instrument().name(), intent.timeframe(), intent.source());
        if (active.isEmpty()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                "no open execution row to " + (flatten ? "flatten" : "close"));
        }
        TradeExecutionRecord row = active.get();
        if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            // A close is already resting at the broker (awaiting fill). A second reducing order could
            // over-close and flip the position once both fill — skip (matches WtxExecutionBridge.handleClose).
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_DUPLICATE,
                "close already in flight (EXIT_SUBMITTED) — duplicate exit skipped", row.getId(), row.getEntryOrderId());
        }
        BrokerPositionState pos = reconciler.readPositionState(intent.brokerAccountId(), intent.instrument());
        if (pos.confirmedFlat()) {
            if (row.getStatus() == ExecutionStatus.ACTIVE) {
                // Phantom: a filled position the broker no longer holds (manual close / drift). Void it
                // (DB-only, no broker call) so a later open is never flattened naked.
                voidRow(row, reasonPrefix + " — IBKR already flat; stale row voided, no naked order");
                return RoutingResult.tracked(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                    "IBKR already flat — exit skipped", row.getId(), row.getEntryOrderId());
            }
            // Entry still resting unfilled while IBKR reads flat — don't fire a naked exit.
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT,
                "entry still in flight (" + row.getStatus() + ") — exit skipped", row.getId(), row.getEntryOrderId());
        }
        // Close the HELD side: FLATTEN derives it from the row's open action; CLOSE from the intent side.
        String closeAction = flatten
            ? ("LONG".equalsIgnoreCase(row.getAction()) ? "SHORT" : "LONG")
            : brokerAction(intent);
        int qty = row.getQuantity() != null && row.getQuantity() > 0 ? row.getQuantity() : intent.quantity();
        return submitCloseLeg(row, intent.instrument(), closeAction, qty, intent.limitPrice(), reasonPrefix);
    }

    /**
     * Submit a close/exit leg on an existing row, mutating it to {@code EXIT_SUBMITTED}. Reuses
     * {@code ibkrOrderService.submitEntryOrder} with the opposite action. A close is NEVER terminal-failed
     * on a reject: the position is still open, so the row stays as-is for the next bar to retry.
     */
    private RoutingResult submitCloseLeg(TradeExecutionRecord row, Instrument instrument, String closeAction,
                                         int qty, BigDecimal price, String reasonPrefix) {
        try {
            BrokerEntryOrderSubmission sub = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                row.getId(), row.getExecutionKey(), row.getBrokerAccountId(), row.getInstrument(),
                closeAction, qty, normalizeToTick(price, instrument)));
            Instant now = Instant.now();
            row.setStatus(ExecutionStatus.EXIT_SUBMITTED);
            row.setIbkrOrderId(toIbkrOrderId(sub.brokerOrderId()));
            row.setStatusReason(reasonPrefix + " — IBKR close submitted: " + sub.brokerOrderStatus());
            row.setExitSubmittedAt(now);
            row.setUpdatedAt(now);
            executionRepository.save(row);
            RoutingOutcome outcome = "PendingSubmit".equalsIgnoreCase(sub.brokerOrderStatus())
                ? RoutingOutcome.ACK_PENDING : RoutingOutcome.ROUTED;
            return RoutingResult.tracked(outcome, row.getId(), sub.brokerOrderId());
        } catch (IbkrOrderRejectionException e) {
            RoutingOutcome outcome = mapCloseRejection(e);
            Instant now = Instant.now();
            if (e.brokerOrderId() != null) {
                // Close reached the broker (late ack) — mark EXIT_SUBMITTED so the fill tracker resolves it.
                row.setStatus(ExecutionStatus.EXIT_SUBMITTED);
                row.setIbkrOrderId(toIbkrOrderId(e.brokerOrderId()));
                row.setExitSubmittedAt(now);
            }
            // else: the close never reached the broker — leave the row non-terminal (still open) to retry.
            row.setStatusReason(truncate(reasonPrefix + " close " + e.kind() + ": " + e.getMessage(), 256));
            row.setUpdatedAt(now);
            executionRepository.save(row);
            log.warn("OrderRouter close leg {} ({}) for execution {}", outcome, e.kind(), row.getId());
            return RoutingResult.tracked(outcome, row.getId(), e.brokerOrderId());
        }
    }

    /**
     * Close-leg rejection mapping. A reducing order never needs margin, so a spurious INSUFFICIENT_MARGIN
     * on a close is treated as a broker reject; otherwise mirror the entry mapping (timeout, read-only).
     */
    private static RoutingOutcome mapCloseRejection(IbkrOrderRejectionException e) {
        return switch (e.kind()) {
            case TIMEOUT -> e.brokerOrderId() != null ? RoutingOutcome.ACK_PENDING : RoutingOutcome.FAILED_TIMEOUT;
            case INSUFFICIENT_MARGIN, BROKER_REJECT, CANCELLED -> looksReadOnly(e)
                ? RoutingOutcome.FAILED_READ_ONLY : RoutingOutcome.FAILED_BROKER_REJECT;
            case UNKNOWN -> RoutingOutcome.FAILED;
        };
    }

    /** Void a stale local row (DB-only, no broker call). */
    private void voidRow(TradeExecutionRecord row, String reason) {
        row.setStatus(ExecutionStatus.CANCELLED);
        row.setStatusReason(truncate(reason, 256));
        row.setUpdatedAt(Instant.now());
        executionRepository.save(row);
    }

    private TradeExecutionRecord toPendingRecord(TradeIntent intent) {
        Instant now = Instant.now();
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setExecutionKey(intent.idempotencyKey());
        r.setInstrument(intent.instrument().name());
        r.setTimeframe(intent.timeframe());
        r.setAction(brokerAction(intent));
        r.setQuantity(intent.quantity());
        r.setTriggerSource(intent.source());
        r.setBrokerAccountId(intent.brokerAccountId());
        r.setRequestedBy(intent.source().name());
        r.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        r.setNormalizedEntryPrice(normalizeToTick(intent.limitPrice(), intent.instrument()));
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }

    /** Broker action token ("LONG"/"SHORT") for the order, derived from intent kind + side. */
    private static String brokerAction(TradeIntent intent) {
        Side side = intent.side();
        return switch (intent.kind()) {
            case OPEN, REVERSE -> side == Side.LONG ? "LONG" : "SHORT";   // BUY a long / SELL a short
            case CLOSE -> side == Side.LONG ? "SHORT" : "LONG";           // closing a long is a SELL
            case FLATTEN -> throw new IllegalStateException("FLATTEN requires the held side (broker-truth) — later step");
        };
    }

    private static RoutingOutcome mapRejection(IbkrOrderRejectionException e) {
        return switch (e.kind()) {
            case INSUFFICIENT_MARGIN -> RoutingOutcome.FAILED_INSUFFICIENT_MARGIN;
            case TIMEOUT -> e.brokerOrderId() != null ? RoutingOutcome.ACK_PENDING : RoutingOutcome.FAILED_TIMEOUT;
            // The kill-switch (riskdesk.ibkr.native-read-only) and the TWS Read-Only API both surface as
            // a BROKER_REJECT carrying a read-only message — keep that distinct in the outcome so signal
            // history / UI show FAILED_READ_ONLY when the global kill switch blocks all orders.
            case BROKER_REJECT, CANCELLED -> looksReadOnly(e)
                ? RoutingOutcome.FAILED_READ_ONLY : RoutingOutcome.FAILED_BROKER_REJECT;
            case UNKNOWN -> RoutingOutcome.FAILED;
        };
    }

    private static boolean looksReadOnly(IbkrOrderRejectionException e) {
        return containsReadOnly(e.brokerMessage()) || containsReadOnly(e.getMessage());
    }

    private static boolean containsReadOnly(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.contains("read-only") || lower.contains("read only") || lower.contains("kill-switch");
    }

    private static Integer toIbkrOrderId(Long brokerOrderId) {
        return brokerOrderId == null ? null : brokerOrderId.intValue();
    }

    private static BigDecimal normalizeToTick(BigDecimal price, Instrument instrument) {
        BigDecimal tick = instrument.getTickSize();
        return price.divide(tick, 0, RoundingMode.HALF_UP)
            .multiply(tick)
            .setScale(tick.scale(), RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

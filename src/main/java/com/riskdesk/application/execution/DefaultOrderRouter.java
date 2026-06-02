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

    /** Non-null placeholder persisted when the intent leaves brokerAccountId null. The gateway's
     *  resolveAccountId() resolves any value that is not a real managed account to the default. */
    private static final String DEFAULT_BROKER_ACCOUNT = "__default__";

    /** Suffix appended to an exit leg's orderRef so it never collides with the entry order's ref in
     *  placeLimitOrder's orderRef idempotency lookup. The fill tracker strips it to map a close callback
     *  back to the row by its base executionKey (see {@code ExecutionFillTrackingService.locate}). */
    public static final String EXIT_ORDER_REF_SUFFIX = ":exit";

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
            Optional<TradeExecutionRecord> existing = findOpenRow(intent);
            if (existing.isPresent()) {
                TradeExecutionRecord row = existing.get();
                if (isInFlightEntry(row.getStatus())) {
                    // A genuine entry order is resting / partially filled at the broker while IBKR reads
                    // flat — opening another risks a double fill once both rest. Skip.
                    return RoutingResult.tracked(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT,
                        "entry still in flight (" + row.getStatus() + ") — open skipped to avoid double fill",
                        row.getId(), row.getEntryOrderId());
                }
                // ACTIVE / EXIT_SUBMITTED / VIRTUAL_EXIT_TRIGGERED — the position (or its close) completed
                // outside us: a manual close, a missed fill callback, or a restart after the exit filled.
                // IBKR is flat, so void the stale row (DB-only) so it can't block this entry or be flattened
                // naked, leaving the fresh open as the sole active row.
                voidRow(row, "OrderRouter " + intent.kind() + " — IBKR flat; stale " + row.getStatus()
                    + " row voided before entry");
            }
        }

        ReconcilePlan plan = reconciler.reconcile(intent, pos);
        return switch (plan) {
            case ReconcilePlan.Skip s -> handleEntrySkip(intent, pos, s);
            case ReconcilePlan.Open o -> submitEntry(intent, false);
            case ReconcilePlan.Reverse r -> executeReverse(intent, r.toSide(), pos);
            // reconcile never produces Close/Flatten for an OPEN/REVERSE intent.
            case ReconcilePlan.Close c -> throw new IllegalStateException(intent.kind() + " reconciled to Close");
            case ReconcilePlan.Flatten f -> throw new IllegalStateException(intent.kind() + " reconciled to Flatten");
        };
    }

    /**
     * Reconcile said the broker is already on the wanted side (SKIPPED_DUPLICATE). If we hold NO local
     * row tracking that position (drift / post-restart / manual trade), synthesise an ACTIVE tracking row
     * so a later CLOSE/FLATTEN can manage the live position instead of finding no row and leaving it
     * unmanaged (matches WtxExecutionBridge).
     */
    private RoutingResult handleEntrySkip(TradeIntent intent, BrokerPositionState pos, ReconcilePlan.Skip s) {
        if (s.outcome() == RoutingOutcome.SKIPPED_DUPLICATE && pos.available() && pos.net().signum() != 0) {
            Optional<TradeExecutionRecord> existing = findOpenRow(intent);
            if (existing.isEmpty()) {
                Side heldSide = pos.net().signum() > 0 ? Side.LONG : Side.SHORT;
                int qty = Math.max(1, pos.net().abs().intValue());
                TradeExecutionRecord tracking = synthesizeActiveRow(intent, heldSide, qty, ":tracking");
                log.info("OrderRouter {} SKIPPED_DUPLICATE — synthesised tracking row {} for the live IBKR position",
                    intent.kind(), tracking.getId());
                return RoutingResult.tracked(s.outcome(), s.message() + " (tracking row synthesised)",
                    tracking.getId(), null);
            }
        }
        return RoutingResult.of(s.outcome(), s.message());
    }

    /**
     * REVERSE — flatten the opposite position, THEN open on {@code toSide}. Two 1:1 legs: the close leg
     * fires FIRST (on the prior local row, or a phantom synthesised from the live IBKR position). If the
     * close ack-pends or is rejected the reverse is ABORTED — the open leg is never attempted (firing it
     * would stack on the un-flattened position). When the close succeeds but the open leg is then rejected,
     * the broker is FLAT — surfaced as {@code ROUTED_FLATTEN_ONLY} (protected, not an error).
     */
    private RoutingResult executeReverse(TradeIntent intent, Side toSide, BrokerPositionState pos) {
        // Can't confirm broker truth — a REVERSE would fire a blind close (naked if the broker is already
        // flat after a restart / manual close) and then stack the open leg. Skip until truth is readable
        // (mirrors executeExit).
        if (!pos.available()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE,
                "broker position truth unavailable — reverse skipped to avoid a blind close + stacked open");
        }

        Optional<TradeExecutionRecord> prior = findOpenRow(intent);
        if (prior.isEmpty() && !pos.confirmedFlat()) {
            // No local row but the broker is NOT flat — there is a live position we must flatten first.
            if (pos.net().signum() != 0) {
                // Directional position opened outside us / drift — synthesise a phantom so the close leg
                // flattens the broker side instead of the open stacking on top of it.
                Side heldSide = pos.isLong() ? Side.LONG : Side.SHORT;
                prior = Optional.of(synthesizePhantom(intent, heldSide, Math.max(1, pos.net().abs().intValue())));
            } else {
                // net==0 but NOT flat = offsetting live legs (rollover / calendar overlap) with no local
                // row. We can't synthesise a single close, and opening would stack on top of the live legs.
                return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                    "IBKR holds offsetting live legs (net 0, not flat) and no local row — reverse skipped to avoid stacking");
            }
        }

        boolean closeLegFired = false;
        if (prior.isPresent()) {
            TradeExecutionRecord priorRow = prior.get();
            ExecutionStatus priorStatus = priorRow.getStatus();
            if (isInFlightEntry(priorStatus)) {
                // The prior entry is still resting / only partially filled — there is NO confirmed full
                // position to flatten. Firing a close (then the open leg) would place naked / opposite /
                // over-sized orders. Skip the whole reverse; a later signal reverses once it is ACTIVE.
                return RoutingResult.tracked(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT,
                    "reverse skipped — prior entry still in flight (" + priorStatus + "), no confirmed position to flatten",
                    priorRow.getId(), priorRow.getEntryOrderId());
            }
            if (priorStatus != ExecutionStatus.EXIT_SUBMITTED) {
                if (pos.isLong() || pos.isShort()) {
                    // Flatten the broker's ACTUAL held side (authoritative — the local row may be stale after
                    // drift / a missed reverse): LONG → SELL, SHORT → BUY. Cap the qty to the live position so
                    // a stale-larger row can't over-close and FLIP it.
                    String closeAction = pos.isLong() ? "SHORT" : "LONG";
                    int priorQty = priorRow.getQuantity() != null && priorRow.getQuantity() > 0
                        ? priorRow.getQuantity() : intent.quantity();
                    int qty = Math.min(priorQty, pos.net().abs().intValue());
                    RoutingResult close = submitCloseLeg(priorRow, intent.instrument(), closeAction, qty,
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
                } else if (pos.confirmedFlat()) {
                    // Broker already flat (closed outside us / a missed reverse already flattened) — nothing to
                    // close. Void the stale prior row so it can't block, then just open the new leg.
                    voidRow(priorRow, "OrderRouter REVERSE — IBKR flat; stale " + priorStatus + " row voided before open");
                } else {
                    // net 0 but NOT flat = offsetting live legs — no single directional close, and opening
                    // would stack on the live legs. Skip.
                    return RoutingResult.tracked(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                        "IBKR holds offsetting live legs (net 0, not flat) — reverse skipped to avoid stacking",
                        priorRow.getId(), priorRow.getEntryOrderId());
                }
            }
        }

        // KNOWN LIMITATION — fill-ordering race (tracked follow-up): this fires the open leg once the close
        // leg is ACCEPTED, not once it is FILLED, matching the locked close-then-open design ported from
        // WtxExecutionBridge. For futures both legs are the same BUY/SELL, so an out-of-order fill (open
        // before close) can mark the new row ACTIVE while the broker is only flat. The resulting NAKED-exit
        // outcome is fully prevented downstream — a later CLOSE/FLATTEN voids the phantom when truth is
        // readable, and skips (SKIPPED_BRIDGE_UNAVAILABLE) when it is not — so no blind reducing order is
        // ever sent. Eliminating the transient ENTIRELY requires serialising the open behind the close FILL
        // (fill-driven deferred open). That is the dedicated fill-orchestration slice and an explicit
        // prerequisite before enabling riskdesk.execution.unified-router for any live strategy — see
        // docs/PLAN_ORDER_ROUTER_IMPL.md.
        return submitEntry(intent, closeLegFired);
    }

    /** Phantom ACTIVE row for a live IBKR position the close leg of a REVERSE will flatten. */
    private TradeExecutionRecord synthesizePhantom(TradeIntent intent, Side heldSide, int qty) {
        return synthesizeActiveRow(intent, heldSide, qty, ":reconcile-close");
    }

    /** Persist an ACTIVE row representing a live IBKR position with no local row, with a distinct
     *  executionKey (suffix) so it does not collide with the open leg. DB-only. */
    private TradeExecutionRecord synthesizeActiveRow(TradeIntent intent, Side heldSide, int qty, String keySuffix) {
        Instant now = Instant.now();
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setExecutionKey(intent.idempotencyKey() + keySuffix);
        r.setInstrument(intent.instrument().name());
        r.setTimeframe(intent.timeframe());
        r.setAction(heldSide == Side.LONG ? "LONG" : "SHORT");
        r.setQuantity(qty);
        r.setTriggerSource(intent.source());
        r.setBrokerAccountId(accountId(intent));
        r.setRequestedBy(intent.source().name());
        r.setStatus(ExecutionStatus.ACTIVE);
        // normalizedEntryPrice is NOT NULL in the entity. We don't know the live position's real fill
        // price (it drifted in / pre-dates restart), so use the intent's limit price as a proxy — this
        // row exists only to let a later CLOSE/FLATTEN manage the position; its close order prices off
        // the exit intent, not this stored value.
        r.setNormalizedEntryPrice(normalizeToTick(intent.limitPrice(), intent.instrument()));
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
            Instant submittedAt = submission.submittedAt() != null ? submission.submittedAt() : Instant.now();
            boolean filled = "Filled".equalsIgnoreCase(submission.brokerOrderStatus());
            persisted.setEntryOrderId(submission.brokerOrderId());
            persisted.setIbkrOrderId(toIbkrOrderId(submission.brokerOrderId()));
            // A marketable limit can come back Filled as its FIRST accepted status. Activate the row now: the
            // orderStatus callback may already have been dropped (ibkrOrderId not yet persisted) and
            // execDetails only updates fill fields, not lifecycle — leaving it ENTRY_SUBMITTED would make a
            // later CLOSE/FLATTEN skip a LIVE position as entry-in-flight.
            persisted.setStatus(filled ? ExecutionStatus.ACTIVE : ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("OrderRouter " + intent.kind() + (filled ? " filled: " : " submitted: ")
                + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submittedAt);
            if (filled) {
                persisted.setEntryFilledAt(submittedAt);
            }
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
        var active = findOpenRow(intent);
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
        if (!pos.available()) {
            // Broker position truth is unreadable. We cannot confirm a live position exists, so firing a
            // reducing order blind risks a NAKED order. This is the only path by which the REVERSE close/
            // open fill-ordering race could surface a naked exit: when truth IS readable an ACTIVE row over
            // a flat broker is voided below (no order); only an unreadable snapshot would fire blind. Skip
            // and let the next signal retry once truth is back.
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE,
                "broker position truth unavailable — " + (flatten ? "flatten" : "close")
                    + " skipped to avoid a blind/naked reducing order", row.getId(), row.getEntryOrderId());
        }
        if (pos.confirmedFlat()) {
            if (isInFlightEntry(row.getStatus())) {
                // Entry still resting unfilled while IBKR reads flat — don't fire a naked exit.
                return RoutingResult.tracked(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT,
                    "entry still in flight (" + row.getStatus() + ") — exit skipped", row.getId(), row.getEntryOrderId());
            }
            // ACTIVE / VIRTUAL_EXIT_TRIGGERED — the position is gone (closed outside us / missed fill). Void
            // the stale row (DB-only, no broker call) so a later open is never flattened naked. (EXIT_SUBMITTED
            // is already handled as a duplicate exit above.)
            voidRow(row, reasonPrefix + " — IBKR already flat; stale " + row.getStatus() + " row voided, no naked order");
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                "IBKR already flat — exit skipped", row.getId(), row.getEntryOrderId());
        }
        // Broker is NOT confirmed flat (a real position, or broker truth unavailable). If our entry is
        // still resting / only partially filled we have no confirmed full position to reduce — a close
        // would be naked or over-sized. Skip rather than fire (mirrors the reverse close-leg guard).
        if (isInFlightEntry(row.getStatus())) {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT,
                "entry still in flight (" + row.getStatus() + ") and position unconfirmed — "
                    + (flatten ? "flatten" : "close") + " skipped", row.getId(), row.getEntryOrderId());
        }
        // Derive the reducing side from BROKER TRUTH (authoritative), not the local row/intent: the row can
        // be stale (manual drift, a missed reverse) and reducing on a stale side would INCREASE the live
        // position. pos is available and NOT confirmedFlat here.
        Side brokerSide;
        if (pos.isLong()) {
            brokerSide = Side.LONG;
        } else if (pos.isShort()) {
            brokerSide = Side.SHORT;
        } else {
            // net 0 but not flat = offsetting live legs (rollover / calendar overlap) — no single reducing
            // action (per-leg flatten is out of scope). Skip rather than guess a side.
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                "IBKR holds offsetting live legs (net 0, not flat) — " + (flatten ? "flatten" : "close")
                    + " skipped", row.getId(), row.getEntryOrderId());
        }
        // CLOSE is directional: only reduce when the broker actually holds the side the intent names.
        if (!flatten && intent.side() != brokerSide) {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                "CLOSE " + intent.side() + " but IBKR holds " + brokerSide + " — no matching position to close",
                row.getId(), row.getEntryOrderId());
        }
        // Reduce the broker's actual held side: LONG → SELL (SHORT), SHORT → BUY (LONG).
        String closeAction = brokerSide == Side.LONG ? "SHORT" : "LONG";
        // Quantity is bounded BOTH ways: never more than the row owns (don't close another source's leg on
        // the same instrument) AND never more than the broker actually holds (a stale row recording more than
        // the live position would otherwise over-close and FLIP it — e.g. row 2 vs IBKR long 1 → SELL 1, not
        // SELL 2 which opens a short 1).
        int rowQty = row.getQuantity() != null && row.getQuantity() > 0 ? row.getQuantity() : intent.quantity();
        int qty = Math.min(rowQty, pos.net().abs().intValue());
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
                row.getId(), exitOrderRef(row), row.getBrokerAccountId(), row.getInstrument(),
                closeAction, qty, normalizeToTick(price, instrument)));
            Instant now = Instant.now();
            // A close can come back Filled as its FIRST accepted status (marketable). Mark the row CLOSED
            // now: the orderStatus callback may already have been dropped (close orderId not yet persisted)
            // and execDetails only updates fill fields, not lifecycle — leaving it EXIT_SUBMITTED would make
            // a later CLOSE/FLATTEN hit the duplicate-exit guard over an already-flat position.
            boolean filled = "Filled".equalsIgnoreCase(sub.brokerOrderStatus());
            row.setStatus(filled ? ExecutionStatus.CLOSED : ExecutionStatus.EXIT_SUBMITTED);
            row.setIbkrOrderId(toIbkrOrderId(sub.brokerOrderId()));
            row.setStatusReason(reasonPrefix + " — IBKR close " + (filled ? "filled" : "submitted") + ": "
                + sub.brokerOrderStatus());
            row.setExitSubmittedAt(now);
            row.setUpdatedAt(now);
            executionRepository.save(row);
            RoutingOutcome outcome = "PendingSubmit".equalsIgnoreCase(sub.brokerOrderStatus())
                ? RoutingOutcome.ACK_PENDING : RoutingOutcome.ROUTED;
            return RoutingResult.tracked(outcome, row.getId(), sub.brokerOrderId());
        } catch (IbkrOrderRejectionException e) {
            RoutingOutcome outcome = mapCloseRejection(e);
            Instant now = Instant.now();
            if (outcome == RoutingOutcome.ACK_PENDING) {
                // Genuinely live at the broker (late ack with an order id) — mark EXIT_SUBMITTED so the fill
                // tracker resolves it on the Filled/Cancelled callback.
                row.setStatus(ExecutionStatus.EXIT_SUBMITTED);
                row.setIbkrOrderId(toIbkrOrderId(e.brokerOrderId()));
                row.setExitSubmittedAt(now);
            }
            // else: REJECTED / CANCELLED / timed-out-without-id — even if IBKR allocated an order id it is
            // NOT working, and the position is still open. Leave the row in its current (non-terminal) state
            // so the next exit signal RETRIES; marking EXIT_SUBMITTED here would make the duplicate guard skip
            // the retry forever while the position stays open.
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

    /**
     * Locate THIS strategy's open row for the intent — scoped to (instrument, timeframe, source) AND the
     * resolved broker account. Account scoping is mandatory: every exit path closes on the row's own
     * {@code brokerAccountId}, so returning another account's row would flatten the wrong account. Uses
     * {@link #accountId} so it matches the (non-null) account these rows are persisted with.
     */
    private Optional<TradeExecutionRecord> findOpenRow(TradeIntent intent) {
        return executionRepository.findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccount(
            intent.instrument().name(), intent.timeframe(), intent.source(), accountId(intent));
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
        r.setBrokerAccountId(accountId(intent));
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
            // FLATTEN derives its close action from the held row, not the intent — brokerAction is never
            // called for it.
            case FLATTEN -> throw new IllegalStateException("FLATTEN close action is derived from the held row");
        };
    }

    /** Non-null broker account for persistence: the intent's account, or the default placeholder when
     *  the intent leaves it null (the gateway resolves the placeholder to the real default account). */
    private static String accountId(TradeIntent intent) {
        String acct = intent.brokerAccountId();
        return acct != null && !acct.isBlank() ? acct : DEFAULT_BROKER_ACCOUNT;
    }

    /**
     * True for entry statuses where no confirmed full position exists yet (order armed, resting, or only
     * partially filled). A reducing leg — a CLOSE, a FLATTEN, or the close leg of a REVERSE — against such
     * a row would be naked (entry never filled) or over-sized (partial fill), so the router skips it with
     * {@code SKIPPED_ENTRY_IN_FLIGHT} and lets a later signal act once the row is {@code ACTIVE}.
     */
    private static boolean isInFlightEntry(ExecutionStatus status) {
        return status == ExecutionStatus.PENDING_ENTRY_SUBMISSION
            || status == ExecutionStatus.ENTRY_SUBMITTED
            || status == ExecutionStatus.ENTRY_PARTIALLY_FILLED;
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

    /**
     * A distinct orderRef for an exit leg. {@code placeLimitOrder} runs an orderRef idempotency lookup
     * (live AND completed orders) before placing; reusing the entry's executionKey would let that lookup
     * find the already-completed ENTRY order and return it instead of submitting the reducing close — the
     * row would be marked EXIT_SUBMITTED with the entry's id while the position stays open. The fill tracker
     * keys exits by the close's brokerOrderId (persisted synchronously at submit), not the ref, so a
     * suffixed ref is safe.
     */
    private static String exitOrderRef(TradeExecutionRecord row) {
        return row.getExecutionKey() + EXIT_ORDER_REF_SUFFIX;
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

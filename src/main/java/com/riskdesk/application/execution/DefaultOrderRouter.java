package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.InstrumentTickProvider;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort.CreateOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

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
     *  resolveAccountId() resolves any value that is not a real managed account to the default. Public so
     *  a migrating strategy can normalize its own legacy default-account rows to this value at cutover. */
    public static final String DEFAULT_BROKER_ACCOUNT = "__default__";

    /** Suffix appended to an exit leg's orderRef so it never collides with the entry order's ref in
     *  placeLimitOrder's orderRef idempotency lookup. The fill tracker strips it to map a close callback
     *  back to the row by its base executionKey (see {@code ExecutionFillTrackingService.locate}). */
    public static final String EXIT_ORDER_REF_SUFFIX = ":exit";

    /** Price sources accepted as an EXECUTABLE LIVE reference for marketable exit crossing — a streaming
     *  push or a fresh instant provider fetch. A {@code FALLBACK_DB} candle close or an ambiguous {@code
     *  CACHE} value is NOT executable: crossing it yields a falsely-"marketable" limit that can rest unfilled
     *  (the very bug this fixes), so such a price falls back to the passive intent limit. */
    private static final Set<String> LIVE_PRICE_SOURCES = Set.of("LIVE_PUSH", "LIVE_PROVIDER");

    /** Max age of a live price still treated as the current market for crossing; older → passive fallback.
     *  Tracks {@code MarketDataService}'s fresh-cache horizon (15s) with a small margin, so an outage that
     *  leaves only a stale price degrades to the passive limit (the pre-marketable baseline). */
    private static final long MARKETABLE_MAX_PRICE_AGE_SECONDS = 20L;

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrProperties ibkrProperties;
    private final ExecutionReadinessGate readinessGate;
    private final ExecutionReconciler reconciler;
    private final InstrumentTickProvider tickProvider;
    /** Optional margin pre-flight (D4). Null when no bean is present → the router fails open (no check),
     *  matching the legacy bridge's {@code marginPreflight != null} guard. */
    private final OrderAffordabilityPort affordability;
    /** P4 daily loss cap. Nullable in test constructors; when present and tripped, new entries are refused. */
    private final DailyLossCapGuard lossCapGuard;
    /** Internal live-price source for marketable EXIT pricing — the AGENTS.md-compliant {@code IBKR Gateway
     *  -> PostgreSQL -> services} feed (with live-vs-DB provenance), NOT a direct broker read. A reducing leg
     *  crosses this price so it fills immediately like a market order, the limit capping slippage. Only exits
     *  use it; entries stay passive. Same source the Quant force-close uses ({@code QuantSimFlattenReconciler}). */
    private final LivePricePort livePricePort;
    /** Ticks crossed through the touch on an exit leg (SELL: bid − N·tick, BUY: ask + N·tick). This is the
     *  worst-case slippage CAP, not the expected fill — IBKR fills at the best price, usually the touch.
     *  Mirrors the proven Quant force-close convention ({@code riskdesk.quant.sim-exec.flatten-cross-ticks},
     *  default 10) — see {@code IbkrQuant7GatesExecutionBridge#marketableLimit}. */
    private final int marketableCrossTicks;
    /** Kill-switch: when false, exit legs revert to the passive intent limit price (legacy behaviour). */
    private final boolean marketableCloseEnabled;
    /** When true, the OPEN leg of a REVERSE that ACTUALLY flattened is also priced marketable (crossed) so the
     *  flip completes instead of leaving the user flat when price moved past the passive entry. Plain OPENs
     *  (fresh entries, nothing flattened) always stay passive. Independent toggle. */
    private final boolean marketableReverseOpenEnabled;

    public DefaultOrderRouter(IbkrOrderService ibkrOrderService,
                              TradeExecutionRepositoryPort executionRepository,
                              IbkrProperties ibkrProperties,
                              ExecutionReadinessGate readinessGate,
                              ExecutionReconciler reconciler,
                              InstrumentTickProvider tickProvider,
                              Optional<OrderAffordabilityPort> affordability,
                              DailyLossCapGuard lossCapGuard,
                              LivePricePort livePricePort,
                              @Value("${riskdesk.execution.marketable-close.cross-ticks:10}") int marketableCrossTicks,
                              @Value("${riskdesk.execution.marketable-close.enabled:true}") boolean marketableCloseEnabled,
                              @Value("${riskdesk.execution.marketable-reverse-open.enabled:true}") boolean marketableReverseOpenEnabled) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.readinessGate = readinessGate;
        this.reconciler = reconciler;
        this.tickProvider = tickProvider;
        this.affordability = affordability == null ? null : affordability.orElse(null);
        this.lossCapGuard = lossCapGuard;
        this.livePricePort = livePricePort;
        this.marketableCrossTicks = Math.max(0, marketableCrossTicks);
        this.marketableCloseEnabled = marketableCloseEnabled;
        this.marketableReverseOpenEnabled = marketableReverseOpenEnabled;
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
        // P4 daily loss cap — halt NEW entries (OPEN and the opening of a REVERSE) once the broker-truth
        // daily realized loss breached the threshold. Closes/flattens are never gated here, so a live
        // position stays exitable; a strategy's own CLOSE signal still flattens. Reversals simply don't flip
        // until the cap re-arms at the next trading day.
        if (lossCapGuard != null && lossCapGuard.blocksNewEntries()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_AUTO_OFF,
                "daily loss cap tripped — new entries halted until re-arm");
        }
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
            case ReconcilePlan.Open o -> submitOpen(intent);
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

        // D4 — affordability of the open leg, sized by the NET margin delta this reverse creates:
        // (new size − the live position it flattens). A same-size / smaller reverse frees margin
        // (delta <= 0 → skipped); a size-increasing reverse pre-checks only the extra contracts. Computed
        // here against broker truth, but ENFORCED only after the close leg fires below — a margin denial
        // must NEVER abort the close (flattening protects the user); it only skips the open.
        int reverseDeltaQty = Math.max(0, intent.quantity() - pos.net().abs().intValue());
        OrderAffordabilityPort.Affordability aff = checkAffordability(intent, brokerAction(intent), reverseDeltaQty);

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
        TradeExecutionRecord restingCloseRow = null; // D2 — set when the close RESTS: defer the open behind its fill
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
                        intent.limitPrice(), "OrderRouter REVERSE close", intent.idempotencyKey());
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
                    // D2 — submitCloseLeg set priorRow to CLOSED (marketable close filled → broker flat now →
                    // open inline below) or EXIT_SUBMITTED (close RESTING → defer the open behind its fill).
                    if (priorRow.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
                        restingCloseRow = priorRow;
                    }
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

        // D4 — open-leg affordability gate. A denial NEVER undoes the close that already fired: the user
        // ends up FLAT (protected) → ROUTED_FLATTEN_ONLY (caller corrects its virtual state to flat). A
        // pure reverse-to-open with nothing flattened (broker was flat, prior voided) is declined outright.
        if (!aff.allowed()) {
            if (closeLegFired) {
                log.warn("OrderRouter REVERSE flattened, open leg skipped — insufficient margin ({}): {}",
                    intent.idempotencyKey(), aff.denyReason());
                return RoutingResult.of(RoutingOutcome.ROUTED_FLATTEN_ONLY,
                    truncate("reversed to flat — open leg skipped (insufficient margin): " + aff.denyReason(), 200));
            }
            log.info("OrderRouter REVERSE→open declined by margin pre-flight ({}): {}",
                intent.idempotencyKey(), aff.denyReason());
            return RoutingResult.of(RoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, truncate(aff.denyReason(), 200));
        }

        // D2 — serialise the open behind the close FILL. When a close leg is RESTING (EXIT_SUBMITTED), defer
        // the open: persist it linked to the close ROW so ReverseDeferredOpenScheduler submits it only once
        // the close FILLS (broker confirmed flat). This eliminates the open-before-close fill-ordering
        // transient that, for same-side futures legs, could otherwise mark the new row ACTIVE while the broker
        // is only momentarily flat. A close that came back already FILLED (marketable), or no close fired
        // (broker was flat / the prior row was voided), means the broker is flat NOW → open inline.
        if (restingCloseRow != null) {
            return deferReverseOpen(intent, restingCloseRow);
        }
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
     * Plain OPEN — runs the margin pre-flight (D4) on the FULL position quantity BEFORE any broker side
     * effect, then submits. A denial declines outright with {@code SKIPPED_INSUFFICIENT_MARGIN} (there is
     * no position to flatten, unlike a REVERSE). The pre-flight runs here — AFTER reconcile — not before
     * route(): pre-declining ahead of the reconcile would skip the broker-truth pass that synthesises a
     * tracking row for a duplicate / upgrades to a REVERSE, leaving a live position unmanaged.
     */
    private RoutingResult submitOpen(TradeIntent intent) {
        OrderAffordabilityPort.Affordability aff = checkAffordability(intent, brokerAction(intent), intent.quantity());
        if (!aff.allowed()) {
            log.info("OrderRouter OPEN declined by margin pre-flight ({}): {}",
                intent.idempotencyKey(), aff.denyReason());
            return RoutingResult.of(RoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, truncate(aff.denyReason(), 200));
        }
        return submitEntry(intent, false);
    }

    /**
     * Margin pre-flight (D4), fail-open. Returns {@code allow()} when no pre-flight bean is wired or
     * {@code qty <= 0} (a same-size / size-decreasing REVERSE frees margin — nothing to check).
     */
    private OrderAffordabilityPort.Affordability checkAffordability(TradeIntent intent, String action, int qty) {
        if (affordability == null || qty <= 0) {
            return OrderAffordabilityPort.Affordability.allow();
        }
        // Assess against the intent's ACCOUNT (the same account readPositionState reconciles), not the
        // gateway default — a multi-account gateway must not judge a DU2 order against DU1's funds.
        return affordability.check(intent.instrument(), action, qty, intent.limitPrice(), intent.brokerAccountId());
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
        return submitPersistedEntry(persisted, intent.kind().name(), reverseFlattened);
    }

    /**
     * Broker-submit an ALREADY-PERSISTED pending entry row and apply the lifecycle transitions — shared by a
     * fresh {@link #submitEntry} and the fill-deferred reverse open ({@link #submitDeferredReverseOpen}), so
     * both follow identical idempotence, id-persistence and error mapping. The broker action is read from the
     * row ({@code getAction()}). {@code kindLabel} ("OPEN"/"REVERSE") flavours status/logs; {@code
     * reverseFlattened} marks that a reverse close leg already flattened, so a NON-timeout open reject means
     * the broker is FLAT (protected) → {@code ROUTED_FLATTEN_ONLY}.
     */
    private RoutingResult submitPersistedEntry(TradeExecutionRecord persisted, String kindLabel, boolean reverseFlattened) {
        // The OPEN leg of a REVERSE that already flattened is priced MARKETABLE (crossed) when enabled, so the
        // flip completes instead of resting at the passive entry while price moves away (re-priced off the live
        // price at submit time — inline or fill-deferred). Plain OPENs (reverseFlattened=false) stay passive.
        BigDecimal entryPrice = persisted.getNormalizedEntryPrice();
        if (reverseFlattened && marketableReverseOpenEnabled) {
            Instrument instrument = parseInstrument(persisted.getInstrument());
            if (instrument != null) {
                entryPrice = normalizeToTick(
                    marketableLimit(instrument, persisted.getAction(), persisted.getNormalizedEntryPrice()), instrument);
                // Track the row at the price we actually SUBMIT (the crossed price), not the passive signal
                // limit — ActivePositionView derives live P&L from normalizedEntryPrice, so a divergence would
                // skew points/$ on every marketable reverse-open position until a separate correction.
                persisted.setNormalizedEntryPrice(entryPrice);
            }
        }
        try {
            BrokerEntryOrderSubmission submission = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                persisted.getId(),
                persisted.getExecutionKey(),
                persisted.getBrokerAccountId(),
                persisted.getInstrument(),
                persisted.getAction(),
                persisted.getQuantity(),
                entryPrice));

            Instant submittedAt = submission.submittedAt() != null ? submission.submittedAt() : Instant.now();
            boolean filled = "Filled".equalsIgnoreCase(submission.brokerOrderStatus());
            persisted.setEntryOrderId(submission.brokerOrderId());
            persisted.setIbkrOrderId(toIbkrOrderId(submission.brokerOrderId()));
            persisted.setStatus(filled ? ExecutionStatus.ACTIVE : ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("OrderRouter " + kindLabel + (filled ? " filled: " : " submitted: ")
                + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submittedAt);
            if (filled) {
                persisted.setEntryFilledAt(submittedAt);
            }
            persisted.setDeferredReverseCloseRowId(null); // submitted now — never re-pick this row as deferred
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);

            RoutingOutcome outcome = "PendingSubmit".equalsIgnoreCase(submission.brokerOrderStatus())
                ? RoutingOutcome.ACK_PENDING
                : RoutingOutcome.ROUTED;
            return RoutingResult.tracked(outcome, persisted.getId(), submission.brokerOrderId());

        } catch (IbkrOrderRejectionException e) {
            if (reverseFlattened && e.kind() != IbkrOrderRejectionException.Kind.TIMEOUT) {
                persisted.setStatus(ExecutionStatus.FAILED);
                persisted.setStatusReason(truncate("OrderRouter REVERSE flattened — open leg not opened ("
                    + e.kind() + "): " + e.getMessage(), 256));
                persisted.setDeferredReverseCloseRowId(null);
                persisted.setUpdatedAt(Instant.now());
                executionRepository.save(persisted);
                log.warn("OrderRouter REVERSE flattened, open leg rejected ({}) — broker FLAT, protected: {}",
                    e.kind(), persisted.getExecutionKey());
                return RoutingResult.tracked(RoutingOutcome.ROUTED_FLATTEN_ONLY,
                    "reversed to flat — open leg not opened (" + e.kind() + ")", persisted.getId(), null);
            }

            RoutingOutcome outcome = mapRejection(e);
            persisted.setStatus(outcome.mustTrackExecutionRow()
                ? ExecutionStatus.ENTRY_SUBMITTED : ExecutionStatus.FAILED);
            persisted.setStatusReason(truncate("OrderRouter " + kindLabel + " " + e.kind() + ": " + e.getMessage(), 256));
            if (e.brokerOrderId() != null) {
                persisted.setEntryOrderId(e.brokerOrderId());
                persisted.setIbkrOrderId(toIbkrOrderId(e.brokerOrderId()));
                persisted.setEntrySubmittedAt(Instant.now());
            }
            persisted.setDeferredReverseCloseRowId(null); // submission attempted — no longer a deferred row
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("OrderRouter {} {} ({}) — {}", kindLabel, outcome, e.kind(), persisted.getExecutionKey());
            return RoutingResult.tracked(outcome, persisted.getId(), e.brokerOrderId());

        } catch (RuntimeException e) {
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason(truncate("OrderRouter " + kindLabel + " failed: " + e.getMessage(), 256));
            persisted.setDeferredReverseCloseRowId(null);
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.warn("OrderRouter {} failed for {}: {}", kindLabel, persisted.getExecutionKey(), e.getMessage());
            return RoutingResult.tracked(RoutingOutcome.FAILED, e.getMessage(), persisted.getId(), null);
        }
    }

    /**
     * D2 — hold the REVERSE open leg behind the close FILL. Persist the open as a deferred
     * {@code PENDING_ENTRY_SUBMISSION} row linked to the resting close ROW (by PK); {@code
     * ReverseDeferredOpenScheduler} submits it once that close row is confirmed flat. Returns {@code ROUTED}
     * — the reverse is in progress (close routed, open follows on its fill). De-dups on the intent key.
     */
    private RoutingResult deferReverseOpen(TradeIntent intent, TradeExecutionRecord closeRow) {
        TradeExecutionRecord pending = toPendingRecord(intent);
        pending.setDeferredReverseCloseRowId(closeRow.getId());
        pending.setStatusReason(truncate("OrderRouter REVERSE — open deferred behind close row "
            + closeRow.getId() + " fill", 256));
        CreateOutcome created = executionRepository.createIfAbsentTracked(pending);
        TradeExecutionRecord row = created.record();
        if (!created.created()) {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_DUPLICATE,
                "reverse open already exists for this signal", row.getId(), row.getEntryOrderId());
        }
        log.info("OrderRouter REVERSE — open leg deferred behind close row {} (open row {}, key {})",
            closeRow.getId(), row.getId(), intent.idempotencyKey());
        return RoutingResult.tracked(RoutingOutcome.ROUTED,
            "reverse close resting — open deferred behind its fill", row.getId(), null);
    }

    /**
     * D2 — submit the deferred REVERSE open leg now that its close has FILLED (broker flat). Called by
     * {@code ReverseDeferredOpenScheduler} on its scheduler thread (NEVER the broker callback thread).
     * {@code reverseFlattened=true}: the close already flattened, so a non-timeout reject means the broker
     * stays flat (protected).
     */
    public RoutingResult submitDeferredReverseOpen(TradeExecutionRecord deferredOpenRow) {
        return submitPersistedEntry(deferredOpenRow, "REVERSE", true);
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
        return submitCloseLeg(row, intent.instrument(), closeAction, qty, intent.limitPrice(), reasonPrefix,
            intent.idempotencyKey());
    }

    /**
     * Submit a close/exit leg on an existing row, mutating it to {@code EXIT_SUBMITTED}. Reuses
     * {@code ibkrOrderService.submitEntryOrder} with the opposite action. Shared by REVERSE-close, CLOSE and
     * FLATTEN — so all three price the exit as a MARKETABLE LIMIT ({@link #marketableLimit}) instead of
     * the passive intent limit: a reducing leg is risk-reduction and must fill, so it crosses the market
     * (capped by a slippage buffer) rather than rest unfilled and leave the user holding an unwanted
     * position. A close is NEVER terminal-failed on a reject: the position is still open, so the row stays
     * as-is for the next bar to retry.
     */
    private RoutingResult submitCloseLeg(TradeExecutionRecord row, Instrument instrument, String closeAction,
                                         int qty, BigDecimal price, String reasonPrefix, String exitDiscriminator) {
        BigDecimal closePrice = marketableCloseEnabled ? marketableLimit(instrument, closeAction, price) : price;
        try {
            BrokerEntryOrderSubmission sub = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                row.getId(), exitOrderRef(row.getExecutionKey(), exitDiscriminator), row.getBrokerAccountId(),
                row.getInstrument(), closeAction, qty, normalizeToTick(closePrice, instrument)));
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
     * Marketable-limit price for a leg that must fill NOW — a reducing exit (close / flatten / reverse-close)
     * or the open leg of a reverse that already flattened. A LIMIT priced THROUGH the market so it fills like a
     * market order, while the limit caps worst-case slippage (safer than a raw MKT on thin micro-futures books,
     * and keeps the deliberately limit-only broker path). Crosses the internal live price ({@link LivePricePort}
     * — the compliant {@code IBKR Gateway -> PostgreSQL -> services} feed, NOT a direct broker read) by {@code
     * marketableCrossTicks · minTick}: {@code action} "SHORT" = SELL → {@code price − cross}; "LONG" = BUY →
     * {@code price + cross}. Mirrors the proven Quant force-close ({@code
     * IbkrQuant7GatesExecutionBridge#marketableLimit}). Enable-gating is the CALLER's job
     * ({@code marketableCloseEnabled} for exits, {@code marketableReverseOpenEnabled} for the reverse open).
     *
     * <p>The compliant path exposes a single reconciled live price (not a separate bid/ask), so we cross it by
     * {@code cross-ticks} (default 10, sized to clear a normal spread) rather than sitting on a touch. Falls
     * back to {@code fallback} (the caller's intent limit = legacy passive behaviour) when no live-price port is
     * wired, no executable-live price is available, or the lookup throws — strictly no worse than before. A leg
     * that still doesn't fill stays a retryable LIMIT (re-priced next bar), never worse than the passive limit.</p>
     */
    private BigDecimal marketableLimit(Instrument instrument, String action, BigDecimal fallback) {
        if (livePricePort == null) {
            return fallback;
        }
        Optional<LivePriceSnapshot> snapshot;
        try {
            snapshot = livePricePort.current(instrument);
        } catch (RuntimeException e) {
            // A price hiccup must NEVER break the leg — degrade to the passive limit (today's behaviour).
            log.warn("OrderRouter marketable leg: live-price lookup for {} failed ({}) — passive limit {}",
                instrument, e.toString(), fallback);
            return fallback;
        }
        // Only a GENUINELY-LIVE, FRESH price is an executable reference. A DB-fallback candle close (or any
        // stale/cached value, e.g. during a live-feed outage) must NOT be crossed — a stale price yields a
        // falsely-"marketable" limit that can rest unfilled. Treat it exactly like no price → passive limit
        // (the pre-marketable baseline, never worse than before).
        if (snapshot.isEmpty() || !isExecutableLive(snapshot.get())) {
            log.warn("OrderRouter marketable leg: no executable-live price for {} ({}) — passive limit {} (may rest)",
                instrument, snapshot.map(s -> s.source() + "@" + s.timestamp()).orElse("none"), fallback);
            return fallback;
        }
        BigDecimal reference = BigDecimal.valueOf(snapshot.get().price());
        if (reference.signum() <= 0) {
            return fallback;
        }
        BigDecimal cross = tickProvider.minTick(instrument).multiply(BigDecimal.valueOf(marketableCrossTicks));
        boolean sell = "SHORT".equals(action); // SELL crosses down (reduce a long / open a short); BUY crosses up
        BigDecimal marketable = sell ? reference.subtract(cross) : reference.add(cross);
        // placeLimitOrder rejects a non-positive limit; guard against a degenerate price.
        return marketable.signum() > 0 ? marketable : fallback;
    }

    /**
     * A price is an executable reference for crossing only when it is GENUINELY LIVE (a streaming push or a
     * fresh instant provider fetch — not a {@code FALLBACK_DB} candle close or an ambiguous {@code CACHE}
     * value) AND recent ({@link #MARKETABLE_MAX_PRICE_AGE_SECONDS}). Mirrors the live-source notion the Quant
     * G6 gate uses; stale / fallback prices are treated as no price (→ passive limit).
     */
    private static boolean isExecutableLive(LivePriceSnapshot snap) {
        if (snap.source() == null || !LIVE_PRICE_SOURCES.contains(snap.source())) {
            return false;
        }
        return snap.timestamp() != null
            && snap.timestamp().isAfter(Instant.now().minusSeconds(MARKETABLE_MAX_PRICE_AGE_SECONDS));
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
     * A distinct, retry-safe orderRef for an exit leg: {@code <executionKey>:exit:<discriminator>}.
     * placeLimitOrder runs an orderRef idempotency lookup (live AND completed orders) before placing — the
     * ":exit" suffix stops it returning the already-completed ENTRY order (which would mark the row
     * EXIT_SUBMITTED with the entry id while the position stays open), and the per-attempt {@code
     * discriminator} (the close intent's idempotency key, fresh per close signal) stops a close retried
     * after a terminal-non-filled one (e.g. an EOD-cancelled limit) from matching the dead order — while
     * staying idempotent within the same intent. The fill tracker keys exits by the close brokerOrderId
     * (persisted at submit) and strips from ":exit" to recover the base key, so the discriminator is safe.
     */
    private static String exitOrderRef(String executionKey, String discriminator) {
        return executionKey + EXIT_ORDER_REF_SUFFIX + ":" + discriminator;
    }

    private static Integer toIbkrOrderId(Long brokerOrderId) {
        return brokerOrderId == null ? null : brokerOrderId.intValue();
    }

    /** Resolve the persisted instrument name to the enum, or null when unparseable (→ skip marketable pricing). */
    private static Instrument parseInstrument(String name) {
        try {
            return name == null ? null : Instrument.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Round an order price to the instrument's tick. Prefers the broker's runtime minTick
     *  (ContractDetails.minTick via the provider) over the hardcoded Instrument tick. */
    private BigDecimal normalizeToTick(BigDecimal price, Instrument instrument) {
        BigDecimal tick = tickProvider.minTick(instrument);
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

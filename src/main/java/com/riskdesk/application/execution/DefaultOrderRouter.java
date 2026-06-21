package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.InstrumentTickProvider;
import com.riskdesk.domain.execution.port.MarketableSettingsProvider;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.infrastructure.config.ExecutionProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort.CreateOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
    private static final Set<String> LIVE_PRICE_SOURCES = com.riskdesk.application.marketdata.LivePriceSource.SOURCES;

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
    /** Live, operator-controllable marketable-execution policy — the close / reverse-open toggles and
     *  cross-ticks, read at order time so a UI change (UI → REST → persisted state) takes effect without a
     *  restart. Seeded from {@code riskdesk.execution.marketable-*}; cross-ticks mirrors the proven Quant
     *  force-close convention ({@code riskdesk.quant.sim-exec.flatten-cross-ticks}). */
    private final MarketableSettingsProvider marketableSettings;
    /** Execution-core config — read for the unified-router stuck-close retry grace ({@code
     *  riskdesk.execution.unified-router.stale-close-retry-seconds}), the mirror of the legacy WTX knob. */
    private final ExecutionProperties executionProperties;

    public DefaultOrderRouter(IbkrOrderService ibkrOrderService,
                              TradeExecutionRepositoryPort executionRepository,
                              IbkrProperties ibkrProperties,
                              ExecutionReadinessGate readinessGate,
                              ExecutionReconciler reconciler,
                              InstrumentTickProvider tickProvider,
                              Optional<OrderAffordabilityPort> affordability,
                              DailyLossCapGuard lossCapGuard,
                              LivePricePort livePricePort,
                              MarketableSettingsProvider marketableSettings,
                              ExecutionProperties executionProperties) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.readinessGate = readinessGate;
        this.reconciler = reconciler;
        this.tickProvider = tickProvider;
        this.affordability = affordability == null ? null : affordability.orElse(null);
        this.lossCapGuard = lossCapGuard;
        this.livePricePort = livePricePort;
        this.marketableSettings = marketableSettings;
        this.executionProperties = executionProperties;
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
            case REDUCE -> executeReduce(intent);
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

        // D4 — affordability of the open leg, sized by the NET margin delta this reverse creates (new size −
        // the live position it flattened; delta <= 0 frees margin → skipped). Priced at what the open will
        // ACTUALLY submit: the crossed marketable price ONLY when a close fired (closeLegFired → the open is
        // marketable); otherwise the passive intent limit (broker was flat / prior voided → submitEntry(false)
        // submits passive). Gating the crossed price on closeLegFired keeps the estimate consistent with the
        // submit — using it unconditionally would falsely DENY a flat-reversal open the passive submit could
        // afford. Computed against the same broker-truth pos.
        //
        // Open-leg affordability gate. A denial NEVER undoes the close that already fired: the user ends up
        // FLAT (protected) → ROUTED_FLATTEN_ONLY (caller corrects its virtual state to flat). A pure
        // reverse-to-open with nothing flattened (broker was flat, prior voided) is declined outright.
        int reverseDeltaQty = Math.max(0, intent.quantity() - pos.net().abs().intValue());
        // Compute the marketable open price ONCE (normalized): the preflight AND the inline submit both use this
        // exact value (carried via submitEntry), so the order sent to IBKR is the one that passed margin — no
        // second live read that could tick higher and fail margin. null when the open will be passive (no close
        // fired / toggle off); the deferred path (close resting) re-prices at its own submit time instead.
        BigDecimal marketableOpenPrice = (marketableSettings.current().reverseOpenEnabled() && closeLegFired)
            ? normalizeToTick(marketableLimit(intent.instrument(), brokerAction(intent), intent.limitPrice()),
                intent.instrument())
            : null;
        BigDecimal openRefPrice = marketableOpenPrice != null ? marketableOpenPrice : intent.limitPrice();
        OrderAffordabilityPort.Affordability aff =
            checkAffordability(intent, brokerAction(intent), reverseDeltaQty, openRefPrice);
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
        return submitEntry(intent, closeLegFired, marketableOpenPrice); // inline: reuse the preflighted price
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
        OrderAffordabilityPort.Affordability aff =
            checkAffordability(intent, brokerAction(intent), intent.quantity(), intent.limitPrice());
        if (!aff.allowed()) {
            log.info("OrderRouter OPEN declined by margin pre-flight ({}): {}",
                intent.idempotencyKey(), aff.denyReason());
            return RoutingResult.of(RoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN, truncate(aff.denyReason(), 200));
        }
        return submitEntry(intent, false, null); // plain OPEN — passive, no carried marketable price
    }

    /**
     * Margin pre-flight (D4), fail-open. Returns {@code allow()} when no pre-flight bean is wired or
     * {@code qty <= 0} (a same-size / size-decreasing REVERSE frees margin — nothing to check).
     */
    private OrderAffordabilityPort.Affordability checkAffordability(TradeIntent intent, String action, int qty,
                                                                    BigDecimal price) {
        if (affordability == null || qty <= 0) {
            return OrderAffordabilityPort.Affordability.allow();
        }
        // Assess against the intent's ACCOUNT (the same account readPositionState reconciles), not the
        // gateway default — a multi-account gateway must not judge a DU2 order against DU1's funds. The
        // reference price is the one the leg will actually submit (crossed for a marketable reverse open).
        return affordability.check(intent.instrument(), action, qty, price, intent.brokerAccountId());
    }

    /**
     * Submit a fresh entry leg (OPEN, or the open leg of a REVERSE). De-dups via createIfAbsentTracked (no
     * lock, no transaction held across the broker submit). {@code reverseFlattened} = the reverse close leg
     * already flattened the broker, so a subsequent NON-timeout open rejection means the broker is FLAT
     * (protected) → {@code ROUTED_FLATTEN_ONLY}, not a plain failure.
     */
    private RoutingResult submitEntry(TradeIntent intent, boolean reverseFlattened, BigDecimal marketableOverride) {
        CreateOutcome createResult = executionRepository.createIfAbsentTracked(toPendingRecord(intent));
        TradeExecutionRecord persisted = createResult.record();
        if (!createResult.created()) {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_DUPLICATE, "execution already exists",
                persisted.getId(), persisted.getEntryOrderId());
        }
        return submitPersistedEntry(persisted, intent.kind().name(), reverseFlattened, marketableOverride);
    }

    /**
     * Broker-submit an ALREADY-PERSISTED pending entry row and apply the lifecycle transitions — shared by a
     * fresh {@link #submitEntry} and the fill-deferred reverse open ({@link #submitDeferredReverseOpen}), so
     * both follow identical idempotence, id-persistence and error mapping. The broker action is read from the
     * row ({@code getAction()}). {@code kindLabel} ("OPEN"/"REVERSE") flavours status/logs; {@code
     * reverseFlattened} marks that a reverse close leg already flattened, so a NON-timeout open reject means
     * the broker is FLAT (protected) → {@code ROUTED_FLATTEN_ONLY}. {@code marketableOverride} (non-null only
     * for an INLINE reverse open) is the exact, already-normalized price the margin preflight checked — reused
     * verbatim so the submitted order matches what passed margin.
     */
    private RoutingResult submitPersistedEntry(TradeExecutionRecord persisted, String kindLabel,
                                               boolean reverseFlattened, BigDecimal marketableOverride) {
        // The OPEN leg of a REVERSE that already flattened is priced MARKETABLE (crossed) when enabled, so the
        // flip completes instead of resting at the passive entry while price moves away. INLINE: reuse the EXACT
        // price the margin preflight checked (marketableOverride, already normalized) — a single live read,
        // carried, so the submitted order can't drift above what passed margin between two reads. DEFERRED
        // (override null, submitted later by the scheduler): re-price off the CURRENT live price (the price
        // legitimately moved while the close filled). Plain OPENs stay passive. The row is tracked at the
        // submitted price (ActivePositionView derives live P&L from normalizedEntryPrice).
        BigDecimal entryPrice = persisted.getNormalizedEntryPrice();
        if (marketableOverride != null) {
            entryPrice = marketableOverride;
            persisted.setNormalizedEntryPrice(entryPrice);
        } else if (reverseFlattened && marketableSettings.current().reverseOpenEnabled()) {
            Instrument instrument = parseInstrument(persisted.getInstrument());
            if (instrument != null) {
                entryPrice = normalizeToTick(
                    marketableLimit(instrument, persisted.getAction(), persisted.getNormalizedEntryPrice()), instrument);
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
        // Deferred: submitted later than the preflight, so re-price off the CURRENT live price (override null).
        return submitPersistedEntry(deferredOpenRow, "REVERSE", true, null);
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
        // Read broker truth ONCE up front — needed both for the stuck-close decision below and for the
        // available / confirmed-flat / held-side branches that follow.
        BrokerPositionState pos = reconciler.readPositionState(intent.brokerAccountId(), intent.instrument());
        if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            // A close is already resting at the broker (awaiting fill). A second reducing order could
            // over-close and flip the position once both fill — skip as a duplicate (matches
            // WtxExecutionBridge.handleClose).
            //
            // BUT a marketable close whose ack / fill callback was dropped (a known race) can leave the row
            // stuck in EXIT_SUBMITTED while IBKR STILL holds the position. The old unconditional skip then
            // DEAD-LOCKED the instrument: every later CLOSE returned SKIPPED_DUPLICATE here and every same-side
            // OPEN returned SKIPPED_DUPLICATE from the reconcile, so the position could be neither exited nor
            // reversed and bled — recovered only by the background StaleCloseReconciler 60-90s later, leaving
            // the live position unmanaged in between. {@link #stuckCloseNeedsRetry} returns true ONLY once the
            // close has been EXIT_SUBMITTED past the retry grace AND broker truth confirms the position is
            // still open on this row's side — then we fall through and re-fire a FRESH close (a per-signal exit
            // ref, exactly what submitCloseLeg was built to retry). Within the grace, when broker truth is
            // unavailable, or when IBKR is confirmed flat (StaleCloseReconciler owns that case), we keep the
            // duplicate-skip so a genuinely in-flight close is never double-submitted. Ports PR #409's
            // WtxExecutionBridge fix into the unified router.
            if (!stuckCloseNeedsRetry(row, pos)) {
                return RoutingResult.tracked(RoutingOutcome.SKIPPED_DUPLICATE,
                    "close already in flight (EXIT_SUBMITTED) — duplicate exit skipped", row.getId(), row.getEntryOrderId());
            }
            log.warn("OrderRouter {} — prior close stuck (execution {} EXIT_SUBMITTED past grace, IBKR still "
                + "holds the position) — re-firing a fresh close to break the dead-lock", reasonPrefix, row.getId());
            // fall through to submit a fresh close leg on this same row
        }
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
     * REDUCE — partial close (scale-out): reduce the held side by {@code intent.quantity()} contracts and
     * KEEP the remainder live. Mirrors {@link #executeExit}'s broker-truth guards (so a reduce is never naked,
     * over-sized, or fired on the wrong side) but caps the close to the requested quantity and, when that is
     * less than the position, leaves the row ACTIVE with a decremented quantity (via {@code submitCloseLeg}'s
     * {@code remainAfterReduce}). A request for the whole position (or more) collapses to a full close.
     * {@link #executeExit} is deliberately left untouched — full CLOSE / FLATTEN behaviour is unchanged.
     *
     * <p>A prior exit stuck in {@code EXIT_SUBMITTED} (dropped ack/fill callback) is reconciled against broker
     * truth before deciding — re-fired only when the broker still holds the full pre-exit size, otherwise the
     * row is finalized to the reduced broker size WITHOUT a re-fire (a blind re-fire would over-reduce). This
     * removes the asymmetry with {@link #executeExit}, which {@link #stuckCloseNeedsRetry}-recovers its stuck
     * close, while keeping the reduce-specific over-reduction guard.</p>
     */
    private RoutingResult executeReduce(TradeIntent intent) {
        var active = findOpenRow(intent);
        if (active.isEmpty()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_NO_OPEN_ROW, "no open execution row to reduce");
        }
        TradeExecutionRecord row = active.get();
        // Read broker truth ONCE up front — needed both for the stuck-exit reconciliation below and for the
        // available / confirmed-flat / held-side branches that follow (mirrors executeExit).
        BrokerPositionState pos = reconciler.readPositionState(intent.brokerAccountId(), intent.instrument());
        if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            // A close/reduce is already resting at the broker — a second reducing order could over-close, so
            // the default is to skip as a duplicate. BUT a reduce/close whose ack/fill callback was DROPPED
            // leaves the row stuck EXIT_SUBMITTED forever, and the old unconditional skip then DEAD-LOCKED
            // partial management (every later REDUCE skipped here) until a full close or the background
            // reconciler intervened. So, like executeExit, once the exit is stuck past the grace AND IBKR still
            // holds the row's side ({@link #stuckCloseNeedsRetry}), recover it — but a reduce needs an EXTRA
            // safety step a full close does not: re-firing BLINDLY can OVER-REDUCE, because broker-still-open
            // alone cannot tell "the reduce never filled" from "the reduce filled but its callback was lost"
            // (the position is partly open on the same side either way). So reconcile the EXPECTED post-exit
            // size against broker truth FIRST:
            //   • broker still holds the FULL pre-exit size (brokerQty == rowQty) → the exit never reduced →
            //     re-fire a fresh reduce;
            //   • broker holds LESS (brokerQty < rowQty) → the exit ALREADY reduced (lost callback / partial
            //     fill) → finalize the row to broker truth and DON'T re-fire (a later signal acts on it).
            if (!stuckCloseNeedsRetry(row, pos)) {
                // within grace / truth unavailable / broker flat / opposite side — keep the conservative skip
                return RoutingResult.tracked(RoutingOutcome.SKIPPED_DUPLICATE,
                    "an exit is already in flight (EXIT_SUBMITTED) — reduce skipped", row.getId(), row.getEntryOrderId());
            }
            int brokerQty = pos.net().abs().intValue();
            int rowQty = row.getQuantity() != null && row.getQuantity() > 0 ? row.getQuantity() : 0;
            if (brokerQty < rowQty) {
                // The stuck exit already reduced the position (a lost fill callback, or a partial fill): the
                // broker holds fewer than the row's pre-exit size. Re-firing would OVER-reduce. Finalize the
                // row to broker truth (decremented, ACTIVE) and skip — a later signal acts on the reconciled
                // row. Detach the dead close ids so a replayed callback can't re-target it (mirrors the fill
                // tracker's revive). Clearing closingQuantity drops any stale in-flight reduce marker.
                row.setQuantity(brokerQty);
                row.setClosingQuantity(null);
                row.setStatus(ExecutionStatus.ACTIVE);
                row.setIbkrOrderId(null);
                row.setPermId(null);
                row.setStatusReason(truncate("OrderRouter REDUCE — prior exit already reduced to broker truth "
                    + brokerQty + "; reconciled to ACTIVE, duplicate reduce skipped", 256));
                row.setUpdatedAt(Instant.now());
                executionRepository.save(row);
                log.warn("OrderRouter REDUCE — prior exit stuck (execution {} EXIT_SUBMITTED past grace) but IBKR "
                    + "holds {} < row {} — already reduced (lost callback); reconciled to ACTIVE, no duplicate",
                    row.getId(), brokerQty, rowQty);
                return RoutingResult.tracked(RoutingOutcome.SKIPPED_DUPLICATE,
                    "prior exit already reduced to " + brokerQty + " — reconciled, duplicate reduce skipped",
                    row.getId(), row.getEntryOrderId());
            }
            // brokerQty >= rowQty: the stuck exit never reduced anything (broker still holds the full size, or
            // more after external drift). Safe to re-fire a fresh reduce. Clear the stale in-flight marker and
            // sync the row size up to broker truth on drift, then fall through to submit a fresh reduce leg.
            row.setClosingQuantity(null);
            if (brokerQty > rowQty) {
                row.setQuantity(brokerQty);
            }
            log.warn("OrderRouter REDUCE — prior exit stuck (execution {} EXIT_SUBMITTED past grace, IBKR still "
                + "holds {}) — re-firing a fresh reduce to break the dead-lock", row.getId(), brokerQty);
            // fall through to submit a fresh reduce leg on this same (reconciled) row
        }
        if (!pos.available()) {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE,
                "broker position truth unavailable — reduce skipped to avoid a blind reducing order",
                row.getId(), row.getEntryOrderId());
        }
        if (pos.confirmedFlat()) {
            if (!isInFlightEntry(row.getStatus())) {
                voidRow(row, "OrderRouter REDUCE — IBKR already flat; stale " + row.getStatus() + " row voided");
            }
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                "IBKR already flat — reduce skipped", row.getId(), row.getEntryOrderId());
        }
        if (isInFlightEntry(row.getStatus())) {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT,
                "entry still in flight (" + row.getStatus() + ") — reduce skipped", row.getId(), row.getEntryOrderId());
        }
        Side brokerSide;
        if (pos.isLong()) {
            brokerSide = Side.LONG;
        } else if (pos.isShort()) {
            brokerSide = Side.SHORT;
        } else {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                "IBKR holds offsetting live legs (net 0, not flat) — reduce skipped", row.getId(), row.getEntryOrderId());
        }
        if (intent.side() != brokerSide) {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                "REDUCE " + intent.side() + " but IBKR holds " + brokerSide + " — no matching position",
                row.getId(), row.getEntryOrderId());
        }
        String closeAction = brokerSide == Side.LONG ? "SHORT" : "LONG";
        int rowQty = row.getQuantity() != null && row.getQuantity() > 0 ? row.getQuantity() : intent.quantity();
        // The live size this row represents is bounded by broker truth: a stale-larger row (drift / an
        // external partial close) must not drive the remainder, or the reduce would leave a phantom
        // remainder ACTIVE over a flat / smaller broker position.
        int effectiveQty = Math.min(rowQty, pos.net().abs().intValue());
        int reduceQty = Math.min(intent.quantity(), effectiveQty);
        if (reduceQty <= 0) {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_NO_QTY,
                "computed reduce quantity is non-positive", row.getId(), row.getEntryOrderId());
        }
        // Reducing the whole (broker-truth) position or more is a full close, not a partial — remainder null.
        Integer remainAfterReduce = reduceQty < effectiveQty ? (effectiveQty - reduceQty) : null;
        return submitCloseLeg(row, intent.instrument(), closeAction, reduceQty, intent.limitPrice(),
            "OrderRouter REDUCE", intent.idempotencyKey(), remainAfterReduce);
    }

    /**
     * True when an {@code EXIT_SUBMITTED} close is STUCK and must be re-fired rather than skipped as a
     * duplicate exit: it has been non-terminal for longer than the retry grace AND IBKR still holds a live
     * position on this row's side, so the close clearly never completed (a marketable close that gapped out
     * and died, or a lost ack / fill callback). Re-firing a fresh close is the only way to flatten —
     * otherwise the instrument dead-locks (every later CLOSE skips here as a duplicate, every same-side OPEN
     * skips in the reconcile as a duplicate) and the live position bleeds unmanaged until the background
     * {@code StaleCloseReconciler} recovers it. Port of {@code WtxExecutionBridge.stuckCloseNeedsRetry} (PR #409).
     *
     * <p>Deliberately conservative — returns {@code false} (keep the duplicate-skip) when:
     * <ul>
     *   <li>broker truth is unavailable ({@code net == null}) — never re-fire on a guess;</li>
     *   <li>IBKR is flat (net 0), or holds the OPPOSITE side — the close completed (the flat-but-stuck row is
     *       finalized to CLOSED by {@code StaleCloseReconciler}), or this is a deeper divergence the
     *       entry-path reconcile owns; flattening here could open an unintended position;</li>
     *   <li>still within the grace window — a genuinely in-flight marketable close fills in seconds, so a
     *       fresh close must NOT be double-submitted on top of it.</li>
     * </ul>
     * Grace is {@code riskdesk.execution.unified-router.stale-close-retry-seconds} (0 disables the retry →
     * the legacy unconditional skip).
     */
    private boolean stuckCloseNeedsRetry(TradeExecutionRecord row, BrokerPositionState pos) {
        if (row.getStatus() != ExecutionStatus.EXIT_SUBMITTED) {
            return false;
        }
        if (!pos.available() || pos.isNetZero()) {
            return false; // unknown or flat → not a re-fire case (StaleCloseReconciler owns the flat-but-stuck row)
        }
        // Only re-fire when IBKR still holds the SAME side this row tracks: flattening a short is a BUY, so it
        // must never run while IBKR is (somehow) long, which would stack rather than flatten.
        boolean rowIsLong = "LONG".equalsIgnoreCase(row.getAction());
        boolean stillHoldsSameSide = rowIsLong ? pos.isLong() : pos.isShort();
        if (!stillHoldsSameSide) {
            return false;
        }
        int graceSeconds = executionProperties.getUnifiedRouter().getStaleCloseRetrySeconds();
        if (graceSeconds <= 0) {
            return false; // retry disabled → legacy unconditional skip
        }
        Instant since = row.getExitSubmittedAt() != null ? row.getExitSubmittedAt() : row.getUpdatedAt();
        if (since == null) {
            return false;
        }
        return Duration.between(since, Instant.now()).getSeconds() >= graceSeconds;
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
        return submitCloseLeg(row, instrument, closeAction, qty, price, reasonPrefix, exitDiscriminator, null);
    }

    /**
     * @param remainAfterReduce when non-null and {@code > 0}, this leg is a PARTIAL close (REDUCE): on a
     *        synchronous fill the row's {@code quantity} is set to this remainder and the row stays ACTIVE;
     *        while it rests, {@code closingQuantity} is stamped so the fill tracker decrements on the async
     *        fill. Null = a full close (the row goes CLOSED on fill) — the behaviour for every existing caller.
     */
    private RoutingResult submitCloseLeg(TradeExecutionRecord row, Instrument instrument, String closeAction,
                                         int qty, BigDecimal price, String reasonPrefix, String exitDiscriminator,
                                         Integer remainAfterReduce) {
        BigDecimal closePrice = marketableSettings.current().closeEnabled()
            ? marketableLimit(instrument, closeAction, price) : price;
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
            boolean partial = remainAfterReduce != null && remainAfterReduce > 0;
            if (filled && partial) {
                // Partial close filled synchronously — decrement and keep the remainder live (ACTIVE).
                row.setQuantity(remainAfterReduce);
                row.setClosingQuantity(null);
                row.setStatus(ExecutionStatus.ACTIVE);
            } else {
                row.setStatus(filled ? ExecutionStatus.CLOSED : ExecutionStatus.EXIT_SUBMITTED);
                if (partial) {
                    // Resting reduce — remember the close size so the fill tracker decrements on its fill.
                    row.setClosingQuantity(qty);
                } else {
                    // FULL close / flatten / reverse-close — the entire remaining position is leaving. Clear any
                    // STALE reduce marker left by a prior unfilled REDUCE on this row: otherwise, when this full
                    // close fills, the fill tracker sees closingQuantity < quantity, mis-reads the full close as a
                    // partial, decrements and revives the row to ACTIVE — a phantom position over a flat broker.
                    row.setClosingQuantity(null);
                }
                if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
                    resetFillCounterForExitLeg(row);
                }
            }
            row.setIbkrOrderId(toIbkrOrderId(sub.brokerOrderId()));
            row.setStatusReason(reasonPrefix + " — IBKR " + (partial ? "partial close " : "close ")
                + (filled ? "filled" : "submitted") + ": " + sub.brokerOrderStatus());
            row.setExitSubmittedAt(now);
            // Stamp closedAt on a synchronous FULL-close fill: we mark CLOSED here, so the later
            // orderStatus(Filled) callback (if it arrives) skips its closedAt transition. A partial close that
            // filled stays ACTIVE (no closedAt). Mirrors the WTX close path and Quant doFlatten.
            if (filled && !partial && row.getClosedAt() == null) {
                row.setClosedAt(now);
            }
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
                resetFillCounterForExitLeg(row);
                if (remainAfterReduce != null && remainAfterReduce > 0) {
                    // A resting partial reduce — the fill tracker decrements on its fill.
                    row.setClosingQuantity(qty);
                } else {
                    // Full close re-fired on a late ack — clear any stale reduce marker (see the sync branch).
                    row.setClosingQuantity(null);
                }
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
        BigDecimal cross = tickProvider.minTick(instrument)
            .multiply(BigDecimal.valueOf(marketableSettings.current().crossTicks()));
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

    /**
     * A row going EXIT_SUBMITTED now tracks the RESTING exit leg, not the entry. {@code filledQuantity} is a
     * single broker-fill counter reused across the entry and every exit leg, so reset it to zero (and drop the
     * entry's last execId) when an exit leg starts resting: the fill tracker then measures THIS leg's fills from
     * zero. Without this, a cancel callback that carries no (or a null) {@code filled} leaves the entry's
     * cumulative fill in place, and the partial-fill-then-cancel reconciliation reads it as "this close already
     * filled" — wrongly marking a still-live position CLOSED (or computing a wrong remainder).
     */
    private static void resetFillCounterForExitLeg(TradeExecutionRecord row) {
        row.setFilledQuantity(BigDecimal.ZERO);
        row.setLastExecId(null);
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
            // FLATTEN / REDUCE derive their close action from the held position, not the intent —
            // brokerAction is never called for them.
            case FLATTEN -> throw new IllegalStateException("FLATTEN close action is derived from the held row");
            case REDUCE -> throw new IllegalStateException("REDUCE close action is derived from the held position");
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

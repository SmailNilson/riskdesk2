package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.service.IbkrMarginPreflightService;
import com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.engine.strategy.wtx.WtxAction;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
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
 * Routes WTX strategy actions to IBKR via the existing {@link IbkrOrderService}.
 *
 * Only invoked when {@link WtxStrategyState#autoExecutionEnabled()} is true for the instrument.
 * The user opts in per instrument via the WTX panel; the default is OFF for safety.
 *
 * Lifecycle contract — every {@link TradeExecutionRecord} the bridge writes mirrors the
 * convention of the rest of the execution stack, and every broker order is a 1:1
 * {@code order ↔ row} pair the standard fill tracker can reconcile:
 * <ul>
 *   <li>{@code action} carries the broker-side direction token {@code LONG}/{@code SHORT},
 *       NOT the WTX enum name — this is what {@code IbGatewayBrokerGateway} interprets
 *       correctly and what {@code ActivePositionView} resolves to direction + PnL sign.</li>
 *   <li>OPEN creates one entry row ({@code ENTRY_SUBMITTED}) for one broker order.</li>
 *   <li>CLOSE never creates a new row — it locates the bridge's own open WTX execution and
 *       transitions it to {@code EXIT_SUBMITTED}; the fill tracker reconciles it to
 *       {@code CLOSED} on the Filled callback.</li>
 *   <li>REVERSE is decomposed into TWO independent orders: a close leg against the prior
 *       row (→ {@code EXIT_SUBMITTED}) and an open leg for the new row (→ {@code ENTRY_SUBMITTED}).
 *       Each leg carries its own {@code ibkrOrderId} so both rows are reconciled by the
 *       standard fill tracker — no "one order, two rows" impedance mismatch.</li>
 *   <li>The broker order id is persisted on {@code ibkrOrderId} at submission time for every
 *       leg, because {@code ExecutionFillTrackingService.onOrderStatus} locates rows only by
 *       {@code orderId}.</li>
 * </ul>
 *
 * <p><b>Failure typing (slice 1):</b> the bridge returns {@link WtxRoutingResult} — an outcome
 * plus a human-readable error message — so the UI can distinguish three failure modes:</p>
 * <ul>
 *   <li>{@link WtxRoutingOutcome#SKIPPED_INSUFFICIENT_MARGIN} — broker reported margin /
 *       equity insufficient (e.g. IBKR code 201). No position change occurred. The bridge
 *       does NOT mark the execution row FAILED — it stays {@code ACTIVE} so a subsequent
 *       bar can retry once funds are available.</li>
 *   <li>{@link WtxRoutingOutcome#ACK_PENDING} — the order was sent to IBKR and has an
 *       order id, but the initial ack was late. The row keeps that order id so later
 *       callbacks can reconcile it.</li>
 *   <li>{@link WtxRoutingOutcome#FAILED_TIMEOUT} — IBKR did not ack the order and no
 *       broker order id was available. The broker state is unknown; the row is left
 *       non-terminal with statusReason hinting at manual reconciliation.</li>
 *   <li>{@link WtxRoutingOutcome#FAILED_BROKER_REJECT} — IBKR explicitly rejected the
 *       order (non-margin reject). For an open leg the row is marked {@code FAILED};
 *       for a close leg the row stays non-terminal to retry on the next bar.</li>
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
    /**
     * Pre-flight margin check. Nullable: when the {@code IbkrMarginPreflightService}
     * bean is not wired (legacy deploy, or {@code riskdesk.wtx.enabled=false} on the
     * service but the bridge still constructible for tests) the bridge runs without
     * a pre-flight — same behavior as {@link WtxStrategyProperties.PreflightMode#OFF}.
     */
    private final IbkrMarginPreflightService marginPreflight;
    /**
     * Live IBKR portfolio reader. Nullable: when absent the bridge skips the reconcile
     * step (legacy behaviour). When present, the bridge consults IBKR's <i>actual</i>
     * position before opening — if IBKR already holds the opposite side, the WTX
     * {@code OPEN_*} is upgraded in-place to {@code REVERSE_TO_*}; if IBKR already holds
     * the same side, the order is suppressed as a duplicate. This stops the bridge from
     * stacking a fresh contract on top of a position it lost track of after a restart or
     * a manual broker-side trade.
     */
    private final IbkrPortfolioService ibkrPortfolioService;

    /** Test-only legacy constructor — production code uses the 6-arg variant via Spring autowiring. */
    public WtxExecutionBridge(IbkrOrderService ibkrOrderService,
                              TradeExecutionRepositoryPort executionRepository,
                              IbkrProperties ibkrProperties,
                              WtxStrategyProperties wtxProperties) {
        this(ibkrOrderService, executionRepository, ibkrProperties, wtxProperties, null, null);
    }

    /** Test-only — preflight wired, IBKR reconcile disabled. */
    public WtxExecutionBridge(IbkrOrderService ibkrOrderService,
                              TradeExecutionRepositoryPort executionRepository,
                              IbkrProperties ibkrProperties,
                              WtxStrategyProperties wtxProperties,
                              IbkrMarginPreflightService marginPreflight) {
        this(ibkrOrderService, executionRepository, ibkrProperties, wtxProperties, marginPreflight, null);
    }

    @Autowired
    public WtxExecutionBridge(IbkrOrderService ibkrOrderService,
                              TradeExecutionRepositoryPort executionRepository,
                              IbkrProperties ibkrProperties,
                              WtxStrategyProperties wtxProperties,
                              IbkrMarginPreflightService marginPreflight,
                              IbkrPortfolioService ibkrPortfolioService) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.wtxProperties = wtxProperties;
        this.marginPreflight = marginPreflight;
        this.ibkrPortfolioService = ibkrPortfolioService;
    }

    public WtxRoutingResult submit(WtxSignal signal, WtxStrategyState state) {
        return submit(signal, state, null);
    }

    /**
     * Routes a WTX signal to IBKR and reports the outcome with an optional error message.
     * Every early-return logs the exact gate it stopped at at INFO level so an
     * "Auto-IBKR : ON but no order" case is always diagnosable from the backend log and
     * the returned {@link WtxRoutingResult}.
     * Returns a result with a {@code null} outcome's {@code errorMessage} when routing
     * was never meaningfully attempted (no signal/state, or a non-actionable action).
     */
    public WtxRoutingResult submit(WtxSignal signal, WtxStrategyState state, BigDecimal referencePrice) {
        if (signal == null || state == null) return null;
        if (!state.autoExecutionEnabled()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_AUTO_OFF);
        }
        if (!ibkrProperties.isEnabled()) {
            log.info("WTX [{} {}] routing skipped — IBKR disabled in backend (ibkrProperties.enabled=false)",
                    state.instrument(), state.timeframe());
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_IBKR_DISABLED);
        }

        WtxAction action = signal.suggestedAction();
        if (action == null || action == WtxAction.NONE) return null;

        Instrument instrument;
        try {
            instrument = Instrument.valueOf(state.instrument());
        } catch (IllegalArgumentException e) {
            log.warn("WTX execution bridge: unknown instrument {}", state.instrument());
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED, "Unknown instrument: " + state.instrument());
        }

        return switch (action) {
            case CLOSE_LONG, CLOSE_SHORT, CLOSE_ALL -> handleClose(signal, state, instrument, action, referencePrice);
            case OPEN_LONG, OPEN_SHORT, REVERSE_TO_LONG, REVERSE_TO_SHORT ->
                    handleEntry(signal, state, instrument, action, referencePrice);
            default -> null;
        };
    }

    /**
     * OPEN / REVERSE — open a new WTX position row + broker order.
     *
     * A REVERSE is decomposed into TWO independent 1:1 broker-order ↔ execution-row pairs:
     * first a close leg against the prior row (see {@link #submitCloseLeg}), then this open
     * leg for the new position. Modelling it as two real orders — instead of one doubled
     * order — means each row is reconciled by the standard fill tracker via its own
     * {@code ibkrOrderId}; the prior row is never stranded as a terminal-before-fill or an
     * orphaned non-terminal row. All open-leg validation runs before the close leg, so a
     * duplicate / missing-price reverse never fires a close that can't be followed by an open.
     */
    private WtxRoutingResult handleEntry(WtxSignal signal, WtxStrategyState state, Instrument instrument,
                                         WtxAction action, BigDecimal referencePrice) {
        boolean isReverse = action == WtxAction.REVERSE_TO_LONG || action == WtxAction.REVERSE_TO_SHORT;
        String tf = signal.timeframe();

        String executionKey = "wtx:" + state.instrument() + ":" + tf + ":"
                + signal.signalTs().getEpochSecond() + ":" + action.name();
        if (executionRepository.findByExecutionKey(executionKey).isPresent()) {
            log.info("WTX [{} {}] routing skipped — duplicate execution for {}",
                    state.instrument(), tf, executionKey);
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_DUPLICATE);
        }

        BigDecimal price = referencePrice != null ? referencePrice : state.entryPrice();
        if (price == null) {
            log.info("WTX [{} {}] routing skipped — missing reference price for {}",
                    state.instrument(), tf, action);
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_PRICE);
        }

        int positionQty = positionQuantity(state);
        if (positionQty <= 0) {
            log.info("WTX [{} {}] routing skipped — non-positive quantity {}",
                    state.instrument(), tf, positionQty);
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_QTY);
        }

        // IBKR truth reconcile — if the broker already holds a position in this instrument,
        // the WTX-state view of the world may be stale (post-restart, manual broker trade,
        // failed reconcile). Trust IBKR: a same-side existing position skips the duplicate;
        // an opposite-side position upgrades the OPEN into a REVERSE so we never stack a fresh
        // contract on top of an unknown one. Returns the (possibly rewritten) action.
        BigDecimal liveIbkrPosition = readLiveIbkrPosition(instrument);
        ReconcileOutcome reconcile = reconcileWithIbkr(action, liveIbkrPosition, state.instrument(), tf);
        if (reconcile.skipResult() != null) {
            // Same-side IBKR position with no WTX tracking row: synthesize an ACTIVE row so the
            // next CLOSE / MAX_LOSS / trailing-exit signal can locate the broker-side position and
            // flatten it. Without this, a duplicate-skip leaves the live position invisible to
            // {@link #handleClose} (returns SKIPPED_NO_OPEN_ROW) and the broker position can only
            // be exited manually.
            if (reconcile.skipResult().outcome() == WtxRoutingOutcome.SKIPPED_DUPLICATE
                    && liveIbkrPosition != null && liveIbkrPosition.signum() != 0) {
                ensureTrackedRowForLivePosition(state, tf, liveIbkrPosition, price);
            }
            return reconcile.skipResult();
        }
        action = reconcile.action();
        isReverse = action == WtxAction.REVERSE_TO_LONG || action == WtxAction.REVERSE_TO_SHORT;
        String orderAction = mapToOrderAction(action);

        // IBKR confirmed flat — resolve our own non-terminal WTX row before opening fresh.
        if (liveIbkrPosition != null && liveIbkrPosition.signum() == 0) {
            Optional<TradeExecutionRecord> existingRow = findOpenWtxExecution(state.instrument(), tf);
            if (existingRow.isPresent()) {
                TradeExecutionRecord existing = existingRow.get();
                if (existing.getStatus() == ExecutionStatus.ACTIVE) {
                    // Genuine drift: a FILLED position the broker no longer holds (manual close, or a
                    // side opened while auto-exec was OFF). Void the phantom so the new OPEN is the
                    // sole active row and a later CLOSE can't flatten it naked. DB-only, no broker call.
                    voidRow(existing, "WTX reconcile: IBKR flat — stale row voided before " + action.name());
                    log.warn("WTX [{} {}] reconcile: voided stale ACTIVE row {} — IBKR flat, position closed outside WTX",
                            state.instrument(), tf, existing.getId());
                } else {
                    // An entry order is resting UNFILLED at the broker — IBKR reads flat only because it
                    // hasn't filled yet. Opening another now risks a DOUBLE FILL once both rest, leaving
                    // two non-terminal rows on one panel. Skip and let the in-flight order resolve
                    // (fill → ACTIVE, or terminal) before any new entry on a later bar.
                    log.info("WTX [{} {}] open/reverse skipped — entry row {} still in flight ({}) while IBKR flat; "
                            + "not stacking a second order", state.instrument(), tf, existing.getId(), existing.getStatus());
                    return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT,
                            "entry still in flight (" + existing.getStatus() + ") — open skipped to avoid double fill");
                }
            }
        }

        // Pre-flight margin — sized by the NET margin delta the order will create.
        //   • Pure OPEN              → delta = positionQty (full new position).
        //   • Same-size REVERSE      → delta ≈ 0; close leg releases exactly what the open leg
        //                              consumes, so preflight is skipped (this is the prod
        //                              false-denial fix — running the 15 % gross-estimate gate
        //                              tripped on any account already holding the position).
        //   • Size-increasing REVERSE → delta = positionQty − priorQty; only the extra contracts
        //                              consume fresh margin, so preflight checks that delta.
        //                              Prevents the trap where the close leg succeeds but the
        //                              larger open leg gets rejected at IBKR, leaving the user
        //                              unintentionally flat.
        //   • Size-decreasing REVERSE → delta ≤ 0; margin is freed, no preflight needed.
        // priorQty for a REVERSE comes from the existing WTX row, or from the IBKR live position
        // when reconcile is synthesizing the close leg.
        int preflightQty;
        if (isReverse) {
            int priorQty = priorReverseQty(state, tf, liveIbkrPosition);
            preflightQty = Math.max(0, positionQty - priorQty);
        } else {
            preflightQty = positionQty;
        }
        // A margin denial never aborts a REVERSE: the close leg still fires (flatten =
        // protect the user), only the open leg is skipped. A pure OPEN with no affordable
        // margin is declined outright — there is no position to flatten.
        boolean openLegAffordable = true;
        String marginDenyReason = null;
        if (marginPreflight != null && preflightQty > 0) {
            PreflightDecision decision = marginPreflight.canAffordOrder(instrument, orderAction, preflightQty, price);
            if (!decision.allowed()) {
                openLegAffordable = false;
                marginDenyReason = decision.denyReason();
                log.warn("WTX [{} {}] open leg denied by pre-flight (delta qty={}) — {}",
                        state.instrument(), tf, preflightQty, marginDenyReason);
                if (!isReverse) {
                    // Pure OPEN — nothing to flatten; decline before any broker side effect.
                    return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN,
                            truncate(marginDenyReason, 200));
                }
            }
        }

        // REVERSE: flatten the prior position with its own close-leg order BEFORE opening the
        // new one. Every open-leg precondition is already validated above, so the close leg
        // only fires once we know the open will proceed. If the close leg is rejected we abort
        // — nothing is opened and the live position keeps its active row for the next bar.
        boolean closeLegFired = false;
        if (isReverse) {
            Optional<TradeExecutionRecord> prior = findOpenWtxExecution(state.instrument(), tf);
            // Reconcile fallback: WTX has no open row but IBKR does. Synthesize a phantom row
            // so submitCloseLeg can flatten the broker-side position without us needing its
            // original entry price.
            if (prior.isEmpty() && liveIbkrPosition != null && liveIbkrPosition.signum() != 0
                    && ibkrPositionOpposes(action, liveIbkrPosition)) {
                int qty = Math.max(1, liveIbkrPosition.abs().intValue());
                prior = Optional.of(synthesizeReconcileRow(state, tf, qty, liveIbkrPosition, price));
            }
            if (prior.isPresent() && prior.get().getStatus() != ExecutionStatus.EXIT_SUBMITTED) {
                TradeExecutionRecord priorRow = prior.get();
                int priorQty = priorRow.getQuantity() != null && priorRow.getQuantity() > 0
                        ? priorRow.getQuantity() : positionQty;
                BigDecimal priorPrice = referencePrice != null ? referencePrice : priorRow.getNormalizedEntryPrice();
                if (priorPrice == null) {
                    log.info("WTX [{} {}] routing skipped — missing price for {} close leg, aborting reverse",
                            state.instrument(), tf, action);
                    return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_PRICE);
                }
                CloseLegResult closeResult = submitCloseLeg(priorRow, instrument, orderAction, priorQty, priorPrice,
                        "WTX reversed by " + action.name());
                if (!closeResult.accepted()) {
                    if (closeResult.outcomeOnFailure() == WtxRoutingOutcome.ACK_PENDING) {
                        // The reversal is effectively lost — the close ack-pends and there is
                        // no fill-driven retry that fires the open leg once it confirms. Log
                        // at ERROR so the missed reverse is greppable; embed the hint in the
                        // routing result so the UI tooltip surfaces it too.
                        log.error("WTX [{} {}] reverse close leg ack pending — open leg NOT attempted, "
                                + "reversal signal LOST until manual reconcile",
                                state.instrument(), tf);
                        String hint = "reversal lost — close ack pending, open leg not attempted";
                        String msg = closeResult.errorMessage() == null
                                ? hint
                                : hint + "; " + closeResult.errorMessage();
                        return WtxRoutingResult.of(closeResult.outcomeOnFailure(), msg);
                    }
                    log.warn("WTX [{} {}] reverse close leg rejected — new position not opened (outcome={})",
                            state.instrument(), tf, closeResult.outcomeOnFailure());
                    return WtxRoutingResult.of(closeResult.outcomeOnFailure(), closeResult.errorMessage());
                }
                closeLegFired = true;
            }
        }

        // REVERSE flatten-first: the prior position has now been flattened. If the open leg
        // can't be afforded, stop here — the user ends up FLAT (protected) rather than stuck
        // in the position the strategy told them to exit. Pure OPEN already returned above.
        if (!openLegAffordable) {
            if (closeLegFired) {
                String msg = "reversed to flat — open leg skipped (insufficient margin): "
                        + truncate(marginDenyReason, 150);
                log.warn("WTX [{} {}] reverse flattened, open leg skipped — insufficient margin for new "
                        + "position (delta qty={})", state.instrument(), tf, preflightQty);
                // ROUTED_FLATTEN_ONLY (not ROUTED): the caller optimistically moved the virtual
                // state to the new side before routing — it must correct it back to FLAT so the
                // virtual position/PnL matches the broker, which is now flat.
                return WtxRoutingResult.of(WtxRoutingOutcome.ROUTED_FLATTEN_ONLY, msg);
            }
            // Nothing flattened this call (prior already EXIT_SUBMITTED / no opposite position)
            // and the open can't be afforded → decline. Any in-flight close still brings the
            // account flat on its own fill.
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN,
                    truncate(marginDenyReason, 200));
        }

        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey(executionKey);
        candidate.setMentorSignalReviewId(null);
        candidate.setReviewAlertKey(null);
        candidate.setReviewRevision(null);
        candidate.setBrokerAccountId(firstNonBlank(wtxProperties.getBrokerAccountId(), "wtx-default"));
        candidate.setInstrument(state.instrument());
        candidate.setTimeframe(signal.timeframe());
        // Broker-side direction token: "LONG"/"SHORT" — the value IbGatewayBrokerGateway
        // understands (only "SHORT" maps to Action.SELL; anything else is a BUY) and which
        // ActivePositionView also resolves to the correct direction + PnL sign.
        candidate.setAction(orderAction);
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
                    orderAction,
                    positionQty,
                    persisted.getNormalizedEntryPrice()
            ));
            persisted.setEntryOrderId(submission.brokerOrderId());
            // Persist the broker order id on ibkrOrderId too: ExecutionFillTrackingService.onOrderStatus
            // locates rows ONLY by orderId (it receives no orderRef), so without this an early
            // orderStatus(Filled) before execDetails would be dropped and the row would never
            // leave ENTRY_SUBMITTED.
            persisted.setIbkrOrderId(toIbkrOrderId(submission.brokerOrderId()));
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("WTX " + action.name() + " submitted: " + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submission.submittedAt() != null ? submission.submittedAt() : Instant.now());
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("WTX [{} {}] IBKR open leg submitted — action={} positionQty={} orderAction={} brokerOrderId={}",
                    state.instrument(), tf, action, positionQty, orderAction, submission.brokerOrderId());
            return WtxRoutingResult.of(WtxRoutingOutcome.ROUTED);
        } catch (IbkrOrderRejectionException e) {
            // Typed broker exception: map kind → outcome and persist a clean reason. closeLegFired
            // tells the rejection handler whether a REVERSE already flattened the prior position —
            // in that case an open-leg reject means the broker is FLAT, not "no order happened".
            return handleEntryRejection(persisted, action, e, closeLegFired);
        } catch (RuntimeException e) {
            // Unknown error path — preserve legacy FAILED outcome.
            String msg = truncate(e.getMessage(), 200);
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason("WTX " + action.name() + " failed: " + msg);
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.error("WTX [{} {}] IBKR submission failed for {} — {}",
                    state.instrument(), tf, action, e.getMessage(), e);
            return WtxRoutingResult.of(WtxRoutingOutcome.FAILED, msg);
        }
    }

    /**
     * Maps a typed {@link IbkrOrderRejectionException} from the open leg into a
     * {@link WtxRoutingResult}. Tied to the bridge's persistence contract:
     * <ul>
     *   <li>{@code INSUFFICIENT_MARGIN} — leave the row {@code ACTIVE} (do NOT terminal-fail
     *       it). No position change occurred at the broker; the next bar can retry once funds
     *       come back.</li>
     *   <li>{@code TIMEOUT} — broker state unknown. If the native client can provide the
     *       IBKR order id, persist it and mark the row submitted so late callbacks can
     *       reconcile. Otherwise leave the row non-terminal with an explicit
     *       "manual reconcile required" status reason.</li>
     *   <li>{@code BROKER_REJECT} / {@code CANCELLED} / {@code UNKNOWN} — mark {@code FAILED}.</li>
     * </ul>
     *
     * <p>{@code reverseFlattened} is {@code true} when this open leg belongs to a REVERSE whose
     * close leg already flattened the prior position at the broker. In that case a definitive
     * open-leg rejection (margin / broker reject) leaves the account <b>FLAT</b>, not unchanged —
     * so it must return {@link WtxRoutingOutcome#ROUTED_FLATTEN_ONLY} (never {@code NO MARGIN} /
     * {@code REJECT}). That tells the caller the user is protected-flat and the virtual strategy
     * state must be corrected to FLAT instead of tracking the never-opened new side.</p>
     */
    private WtxRoutingResult handleEntryRejection(TradeExecutionRecord row, WtxAction action,
                                                  IbkrOrderRejectionException e, boolean reverseFlattened) {
        String brokerText = e.brokerMessage() != null ? e.brokerMessage() : e.getMessage();
        String shortMsg = truncate(brokerText, 200);

        // TIMEOUT first: broker state is UNKNOWN — the open leg may or may not have executed —
        // so we must NOT force the caller back to FLAT, even on a flattened reverse. Same handling
        // for pure OPEN and REVERSE.
        if (e.kind() == IbkrOrderRejectionException.Kind.TIMEOUT) {
            WtxRoutingOutcome outcome;
            Long brokerOrderId = e.brokerOrderId();
            if (brokerOrderId != null) {
                outcome = WtxRoutingOutcome.ACK_PENDING;
                persistAckPending(row, brokerOrderId, ExecutionStatus.ENTRY_SUBMITTED);
                row.setStatusReason("WTX " + action.name()
                        + " sent to IBKR; acknowledgement pending (broker order " + brokerOrderId + ")");
                log.warn("WTX [{} {}] open leg ack pending — brokerOrderId={} saved for reconciliation",
                        row.getInstrument(), row.getTimeframe(), brokerOrderId);
            } else {
                outcome = WtxRoutingOutcome.FAILED_TIMEOUT;
                // Keep non-terminal — broker state is unknown, do not terminal-fail.
                row.setStatusReason("WTX " + action.name()
                        + " timeout — ack lost, manual reconcile required");
                log.error("WTX [{} {}] open leg timeout — broker state unknown, row left non-terminal",
                        row.getInstrument(), row.getTimeframe());
            }
            row.setUpdatedAt(Instant.now());
            executionRepository.save(row);
            return WtxRoutingResult.of(outcome, shortMsg);
        }

        // Every non-timeout rejection means the open leg definitely did NOT open at the broker.
        // For a REVERSE whose close leg already flattened, the account is now FLAT — surface
        // ROUTED_FLATTEN_ONLY so the user sees "protected flat" instead of NO MARGIN / REJECT, and
        // the caller re-derives the virtual state to FLAT. We deliberately do NOT retry the open
        // (flatten au minimum) — terminal-fail this never-opened leg.
        if (reverseFlattened) {
            row.setStatus(ExecutionStatus.FAILED);
            row.setStatusReason("WTX " + action.name() + " flattened to flat — open leg not opened ("
                    + e.kind() + "): " + shortMsg);
            row.setUpdatedAt(Instant.now());
            executionRepository.save(row);
            log.warn("WTX [{} {}] reverse flattened, open leg rejected by IBKR ({}) — broker FLAT, "
                    + "virtual state corrected; {}", row.getInstrument(), row.getTimeframe(), e.kind(), shortMsg);
            return WtxRoutingResult.of(WtxRoutingOutcome.ROUTED_FLATTEN_ONLY,
                    "reversed to flat — open leg not opened (" + e.kind() + "): " + truncate(shortMsg, 150));
        }

        // Pure OPEN (or a reverse where no close leg fired): keep per-kind handling.
        WtxRoutingOutcome outcome;
        switch (e.kind()) {
            case INSUFFICIENT_MARGIN -> {
                outcome = WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN;
                // Leave row non-terminal (still pending) — no position change at the broker.
                row.setStatusReason("WTX " + action.name() + " skipped — insufficient margin: " + shortMsg);
                log.warn("WTX [{} {}] open leg skipped — INSUFFICIENT_MARGIN ({})",
                        row.getInstrument(), row.getTimeframe(), shortMsg);
            }
            case BROKER_REJECT, CANCELLED, UNKNOWN -> {
                outcome = WtxRoutingOutcome.FAILED_BROKER_REJECT;
                row.setStatus(ExecutionStatus.FAILED);
                row.setStatusReason("WTX " + action.name() + " rejected: " + shortMsg);
                log.error("WTX [{} {}] open leg rejected — {} ({})",
                        row.getInstrument(), row.getTimeframe(), e.kind(), shortMsg);
            }
            default -> {
                outcome = WtxRoutingOutcome.FAILED;
                row.setStatus(ExecutionStatus.FAILED);
                row.setStatusReason("WTX " + action.name() + " failed: " + shortMsg);
            }
        }
        row.setUpdatedAt(Instant.now());
        executionRepository.save(row);
        return WtxRoutingResult.of(outcome, shortMsg);
    }

    /**
     * CLOSE — submit the broker-side flatten order against the bridge's own open WTX
     * execution row. Never creates a new row, so an ATR / max-loss / NY-force exit can't
     * leak a phantom "active" close order. When no open WTX row exists the close is logged
     * and skipped — submitting a naked order would risk opening an unintended position.
     */
    private WtxRoutingResult handleClose(WtxSignal signal, WtxStrategyState state, Instrument instrument,
                                         WtxAction action, BigDecimal referencePrice) {
        String tf = signal.timeframe();
        Optional<TradeExecutionRecord> open = findOpenWtxExecution(state.instrument(), tf);
        if (open.isEmpty()) {
            log.info("WTX [{} {}] close requested ({}) but no open WTX execution row found — "
                    + "skipping IBKR submission to avoid a naked order", state.instrument(), tf, action);
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW);
        }
        TradeExecutionRecord row = open.get();
        // Guard against a second flatten while the first close is still in flight. The fill
        // tracker reconciles EXIT_SUBMITTED -> CLOSED once the broker confirms the fill.
        if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            log.info("WTX [{} {}] close requested ({}) but execution {} is already EXIT_SUBMITTED — "
                    + "skipping duplicate flatten", state.instrument(), tf, action, row.getId());
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_DUPLICATE);
        }
        // IBKR truth: if the broker is already flat AND this is a FILLED (ACTIVE) position, the row
        // is stale (manual close, or a position opened while auto-exec was OFF). Flattening now would
        // be a NAKED order that OPENS an unintended position. Void the row and skip — never send a
        // naked flatten. DB-only reconcile. Restricted to ACTIVE: an ENTRY_SUBMITTED row is an entry
        // resting unfilled at the broker (IBKR legitimately reads flat) — falling through keeps the
        // pre-existing handling and never corrupts the in-flight order.
        BigDecimal livePos = readLiveIbkrPosition(instrument);
        if (livePos != null && livePos.signum() == 0 && row.getStatus() == ExecutionStatus.ACTIVE) {
            voidRow(row, "WTX " + action.name()
                    + " — IBKR already flat; flatten skipped (stale row voided, no naked order)");
            log.warn("WTX [{} {}] close requested ({}) but IBKR is flat — row {} voided, no naked order",
                    state.instrument(), tf, action, row.getId());
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_OPEN_ROW, "IBKR already flat — flatten skipped");
        }
        int qty = row.getQuantity() != null && row.getQuantity() > 0 ? row.getQuantity() : positionQuantity(state);
        if (qty <= 0) {
            log.info("WTX [{} {}] routing skipped — non-positive close quantity {}",
                    state.instrument(), tf, qty);
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_QTY);
        }
        BigDecimal price = referencePrice != null ? referencePrice : row.getNormalizedEntryPrice();
        if (price == null) {
            log.info("WTX [{} {}] routing skipped — missing reference price for {} close",
                    state.instrument(), tf, action);
            return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_NO_PRICE);
        }
        CloseLegResult closeResult = submitCloseLeg(row, instrument, mapToOrderAction(action), qty, price,
                "WTX " + action.name());
        if (closeResult.accepted()) {
            return WtxRoutingResult.of(WtxRoutingOutcome.ROUTED);
        }
        return WtxRoutingResult.of(closeResult.outcomeOnFailure(), closeResult.errorMessage());
    }

    /**
     * Submits a broker-side flatten order against an existing WTX execution row and
     * transitions it to {@code EXIT_SUBMITTED} (non-terminal). Shared by {@link #handleClose}
     * and the close leg of a REVERSE.
     *
     * <p>The row stays non-terminal until {@code ExecutionFillTrackingService} reconciles the
     * fill to {@code CLOSED}. That callback is located ONLY by {@code orderId}
     * ({@code onOrderStatus} receives no {@code orderRef}), so the broker order id is persisted
     * on {@code ibkrOrderId} here — otherwise an early {@code Filled} status arriving before
     * {@code execDetails} would be dropped and the row would stay {@code EXIT_SUBMITTED} forever.</p>
     *
     * @return a {@link CloseLegResult} carrying acceptance, the typed outcome on failure,
     *         and the broker message. The row is left non-terminal on failure so the live
     *         position stays visible and the close is retryable next bar.
     */
    private CloseLegResult submitCloseLeg(TradeExecutionRecord row, Instrument instrument,
                                          String orderAction, int qty, BigDecimal price, String reasonPrefix) {
        try {
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
            row.setStatusReason(reasonPrefix + " — IBKR close submitted: " + submission.brokerOrderStatus()
                    + " (broker order " + submission.brokerOrderId() + ")");
            row.setExitSubmittedAt(now);
            row.setUpdatedAt(now);
            executionRepository.save(row);
            log.info("WTX [{} {}] IBKR close leg submitted — orderAction={} qty={} executionId={} brokerOrderId={}",
                    row.getInstrument(), row.getTimeframe(), orderAction, qty, row.getId(), submission.brokerOrderId());
            return CloseLegResult.ok();
        } catch (IbkrOrderRejectionException e) {
            // Typed broker exception — keep the row non-terminal so the open position stays
            // visible and the close is retryable on the next bar / next signal.
            String brokerText = e.brokerMessage() != null ? e.brokerMessage() : e.getMessage();
            String shortMsg = truncate(brokerText, 200);
            Long brokerOrderId = e.brokerOrderId();
            WtxRoutingOutcome outcome = switch (e.kind()) {
                // A close/flatten REDUCES exposure — it can never legitimately require margin.
                // An IBKR margin code on a reducing order is spurious (typically a late async
                // reject after a timeout); treat it as a retryable broker reject, NOT a
                // "no margin" skip, so the UI never tells the user a close was blocked for funds.
                case INSUFFICIENT_MARGIN -> WtxRoutingOutcome.FAILED_BROKER_REJECT;
                case TIMEOUT -> brokerOrderId != null
                        ? WtxRoutingOutcome.ACK_PENDING
                        : WtxRoutingOutcome.FAILED_TIMEOUT;
                case BROKER_REJECT, CANCELLED -> WtxRoutingOutcome.FAILED_BROKER_REJECT;
                default -> WtxRoutingOutcome.FAILED;
            };
            String suffix;
            if (outcome == WtxRoutingOutcome.ACK_PENDING) {
                persistAckPending(row, brokerOrderId, ExecutionStatus.EXIT_SUBMITTED);
                suffix = " close sent to IBKR; acknowledgement pending (broker order " + brokerOrderId + ")";
                log.warn("WTX [{} {}] close leg ack pending — brokerOrderId={} saved for reconciliation",
                        row.getInstrument(), row.getTimeframe(), brokerOrderId);
            } else if (outcome == WtxRoutingOutcome.FAILED_TIMEOUT) {
                suffix = " close timeout — ack lost, manual reconcile required";
            } else if (e.kind() == IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN) {
                suffix = " close rejected by IBKR with a margin code on a reducing order "
                        + "(spurious — left active for retry): " + shortMsg;
            } else {
                suffix = " close failed: " + shortMsg;
            }
            row.setStatusReason(reasonPrefix + suffix);
            row.setUpdatedAt(Instant.now());
            executionRepository.save(row);
            if (outcome == WtxRoutingOutcome.ACK_PENDING) {
                log.warn("WTX [{} {}] IBKR close leg ack pending — {}",
                        row.getInstrument(), row.getTimeframe(), shortMsg);
            } else {
                log.error("WTX [{} {}] IBKR close leg rejected — {} ({})",
                        row.getInstrument(), row.getTimeframe(), e.kind(), shortMsg);
            }
            return CloseLegResult.rejected(outcome, shortMsg);
        } catch (RuntimeException e) {
            // Untyped path: legacy FAILED for backwards compatibility.
            String shortMsg = truncate(e.getMessage(), 200);
            row.setStatusReason(reasonPrefix + " close failed: " + shortMsg);
            row.setUpdatedAt(Instant.now());
            executionRepository.save(row);
            log.error("WTX [{} {}] IBKR close leg failed — {}",
                    row.getInstrument(), row.getTimeframe(), e.getMessage(), e);
            return CloseLegResult.rejected(WtxRoutingOutcome.FAILED, shortMsg);
        }
    }

    /** IBKR order ids fit in the int range; {@code ibkrOrderId} is the Integer key the fill tracker uses. */
    private Integer toIbkrOrderId(Long brokerOrderId) {
        return brokerOrderId == null ? null : brokerOrderId.intValue();
    }

    /**
     * Shared ack-pending mutation. Persists the broker order id on {@code ibkrOrderId} so
     * {@code ExecutionFillTrackingService.locate(orderId, null)} can match late callbacks,
     * sets the leg's submitted timestamp if not already set, and transitions the row to the
     * matching submitted state. The caller is responsible for {@code statusReason} and
     * {@code updatedAt} (set by the surrounding save flow).
     *
     * @param submittedStatus must be {@link ExecutionStatus#ENTRY_SUBMITTED} or
     *                        {@link ExecutionStatus#EXIT_SUBMITTED} — defines which timestamp
     *                        is initialized and whether {@code entryOrderId} is persisted.
     */
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

    /**
     * Reads IBKR's live net position for {@code instrument}. Sums signed positions across
     * every contract description that matches the instrument's symbol — necessary because
     * IBKR returns positions keyed by conid (front/back month each get their own row when a
     * rollover overlaps). Returns {@code null} when the portfolio service is not wired or
     * the snapshot is unavailable; treat null as "unknown, fall back to legacy behaviour".
     */
    private BigDecimal readLiveIbkrPosition(Instrument instrument) {
        if (ibkrPortfolioService == null) return null;
        // Scope reconcile to the same account the bridge will submit the order on. In a
        // multi-account gateway, querying the default selection could read account A's positions
        // while orders go to account B, producing spurious duplicate-skips or unwanted REVERSE
        // upgrades. The placeholder "wtx-default" means no real account is configured — fall
        // back to the gateway's default selection (legacy behaviour).
        String accountId = effectiveBrokerAccountId();
        IbkrPortfolioSnapshot snapshot;
        try {
            snapshot = ibkrPortfolioService.getPortfolio(accountId);
        } catch (RuntimeException e) {
            log.debug("WTX reconcile: portfolio snapshot unavailable for {} (account={}) — {}",
                    instrument, accountId, e.getMessage());
            return null;
        }
        if (snapshot == null || !snapshot.connected() || snapshot.positions() == null) {
            return null;
        }
        String symbol = ibkrSymbol(instrument);
        BigDecimal total = BigDecimal.ZERO;
        for (IbkrPositionView pos : snapshot.positions()) {
            if (pos == null || pos.position() == null) continue;
            // Second-line account filter: some gateways return positions across every account
            // attached to the session even when getPortfolio is given a specific id, so we filter
            // again here. When no account is configured the filter is a no-op.
            if (accountId != null && pos.accountId() != null && !accountId.equals(pos.accountId())) {
                continue;
            }
            if (matchesSymbol(pos.contractDesc(), symbol)) {
                total = total.add(pos.position());
            }
        }
        return total;
    }

    /**
     * The broker account id the bridge will submit orders on, or {@code null} when no real
     * account is configured (the default placeholder {@code "wtx-default"} means "let the
     * gateway pick"). Used to keep reconcile and submission scoped to the same account.
     */
    private String effectiveBrokerAccountId() {
        String id = wtxProperties.getBrokerAccountId();
        if (id == null || id.isBlank() || "wtx-default".equals(id)) return null;
        return id;
    }

    /** IBKR ticker for an Instrument enum value. Differs from {@link Enum#name()} for E6 (IBKR symbol "6E"). */
    private static String ibkrSymbol(Instrument instrument) {
        return switch (instrument) {
            case E6 -> "6E";
            default -> instrument.name();
        };
    }

    /**
     * Matches an IBKR {@code contractDesc} against an instrument symbol. {@code contractDesc}
     * is the localSymbol the gateway emits (e.g. "MNQH6", "6EM6", "MGC JUN26") — testing
     * for a leading-symbol prefix is the cheapest reliable match across native + client
     * portal gateways.
     */
    private static boolean matchesSymbol(String contractDesc, String symbol) {
        if (contractDesc == null || symbol == null) return false;
        String upper = contractDesc.toUpperCase().trim();
        return upper.startsWith(symbol);
    }

    private static boolean ibkrPositionOpposes(WtxAction action, BigDecimal livePos) {
        if (livePos == null) return false;
        boolean longAction = action == WtxAction.OPEN_LONG || action == WtxAction.REVERSE_TO_LONG;
        boolean shortAction = action == WtxAction.OPEN_SHORT || action == WtxAction.REVERSE_TO_SHORT;
        if (longAction) return livePos.signum() < 0;
        if (shortAction) return livePos.signum() > 0;
        return false;
    }

    /**
     * Resolves a WTX {@code OPEN_*} / {@code REVERSE_*} action against the live IBKR
     * position. Three outcomes:
     * <ul>
     *   <li>IBKR holds the same side already → return a {@code SKIPPED_DUPLICATE} result;
     *       the bridge stops without touching either store.</li>
     *   <li>IBKR holds the opposite side and the WTX action is {@code OPEN_*} → upgrade to
     *       the matching {@code REVERSE_*} so the bridge flattens IBKR before opening fresh.</li>
     *   <li>IBKR <b>confirmed flat</b> ({@code livePos == 0}) and the WTX action is a
     *       {@code REVERSE_*} → <b>downgrade to the matching {@code OPEN_*}</b>. A reverse has no
     *       broker-side position to flatten, so its close leg would be a NAKED order — it would
     *       OPEN an unintended position instead of flattening. Downgrading opens only the new
     *       side (one order at the panel qty). This is the manual-close / auto-exec-was-OFF
     *       drift case: WTX still thinks it holds a position the broker no longer has.</li>
     *   <li>Snapshot unavailable ({@code livePos == null}) → return the original action
     *       unchanged (can't reconcile, trust the WTX state view — legacy behaviour).</li>
     * </ul>
     */
    private ReconcileOutcome reconcileWithIbkr(WtxAction action, BigDecimal livePos,
                                               String instrument, String timeframe) {
        if (livePos == null) {
            // Snapshot unavailable / portfolio service not wired — can't reconcile.
            return ReconcileOutcome.passthrough(action);
        }
        if (livePos.signum() == 0) {
            // IBKR CONFIRMED FLAT. Downgrade a REVERSE to a plain OPEN so the close leg never
            // fires a naked order; an OPEN stays an OPEN.
            if (action == WtxAction.REVERSE_TO_LONG) {
                log.warn("WTX [{} {}] reconcile: IBKR flat — downgrading REVERSE_TO_LONG to OPEN_LONG "
                        + "(nothing to flatten; close leg suppressed to avoid a naked order)",
                        instrument, timeframe);
                return ReconcileOutcome.passthrough(WtxAction.OPEN_LONG);
            }
            if (action == WtxAction.REVERSE_TO_SHORT) {
                log.warn("WTX [{} {}] reconcile: IBKR flat — downgrading REVERSE_TO_SHORT to OPEN_SHORT "
                        + "(nothing to flatten; close leg suppressed to avoid a naked order)",
                        instrument, timeframe);
                return ReconcileOutcome.passthrough(WtxAction.OPEN_SHORT);
            }
            return ReconcileOutcome.passthrough(action);
        }
        boolean longAction = action == WtxAction.OPEN_LONG || action == WtxAction.REVERSE_TO_LONG;
        boolean shortAction = action == WtxAction.OPEN_SHORT || action == WtxAction.REVERSE_TO_SHORT;
        boolean ibkrLong = livePos.signum() > 0;
        boolean ibkrShort = livePos.signum() < 0;

        if (longAction && ibkrLong) {
            String msg = "IBKR already long " + livePos.abs() + " — no order submitted";
            log.warn("WTX [{} {}] reconcile: {} (action was {})", instrument, timeframe, msg, action);
            return ReconcileOutcome.skip(WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_DUPLICATE, msg));
        }
        if (shortAction && ibkrShort) {
            String msg = "IBKR already short " + livePos.abs() + " — no order submitted";
            log.warn("WTX [{} {}] reconcile: {} (action was {})", instrument, timeframe, msg, action);
            return ReconcileOutcome.skip(WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_DUPLICATE, msg));
        }
        if (action == WtxAction.OPEN_LONG && ibkrShort) {
            log.warn("WTX [{} {}] reconcile: IBKR holds short {} — upgrading OPEN_LONG to REVERSE_TO_LONG",
                    instrument, timeframe, livePos.abs());
            return ReconcileOutcome.passthrough(WtxAction.REVERSE_TO_LONG);
        }
        if (action == WtxAction.OPEN_SHORT && ibkrLong) {
            log.warn("WTX [{} {}] reconcile: IBKR holds long {} — upgrading OPEN_SHORT to REVERSE_TO_SHORT",
                    instrument, timeframe, livePos.abs());
            return ReconcileOutcome.passthrough(WtxAction.REVERSE_TO_SHORT);
        }
        return ReconcileOutcome.passthrough(action);
    }

    /**
     * Phantom prior-row used for the close leg of a reconcile-driven REVERSE when WTX has no
     * tracked execution for the IBKR-side position. Persisted with {@code WTX_AUTO} trigger
     * source and a {@code wtx-reconcile:} executionKey so the standard fill tracker reconciles
     * the close fill. {@code normalizedEntryPrice} uses the signal reference price — we don't
     * have the IBKR entry price here, and {@code submitCloseLeg} only uses it for the order
     * price (which IBKR ignores for a flatten beyond tick-snap), not for P&L.
     */
    private TradeExecutionRecord synthesizeReconcileRow(WtxStrategyState state, String timeframe, int qty,
                                                        BigDecimal livePos, BigDecimal price) {
        TradeExecutionRecord row = new TradeExecutionRecord();
        row.setExecutionKey("wtx-reconcile:" + state.instrument() + ":" + timeframe + ":"
                + Instant.now().getEpochSecond());
        row.setBrokerAccountId(firstNonBlank(wtxProperties.getBrokerAccountId(), "wtx-default"));
        row.setInstrument(state.instrument());
        row.setTimeframe(timeframe);
        row.setAction(livePos.signum() > 0 ? "LONG" : "SHORT");
        row.setQuantity(qty);
        row.setTriggerSource(ExecutionTriggerSource.WTX_AUTO);
        row.setRequestedBy("wtx-reconcile");
        row.setStatus(ExecutionStatus.ACTIVE);
        row.setStatusReason("WTX reconcile: synthesized prior row from IBKR live position " + livePos);
        row.setNormalizedEntryPrice(price);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        log.warn("WTX [{} {}] reconcile: synthesizing prior row for IBKR position {} (qty={}) — close leg will flatten it",
                state.instrument(), timeframe, livePos, qty);
        return executionRepository.createIfAbsent(row);
    }

    /**
     * Creates an ACTIVE tracking row for an IBKR-side position the bridge isn't following yet —
     * invoked when reconcile would skip an OPEN as duplicate but no local row exists (typical
     * post-restart or manual-trade drift). Idempotency is keyed on "any non-terminal WTX row
     * already exists for this (instrument, timeframe)" rather than on a stable executionKey —
     * if the previous tracking row went terminal (CLOSED/CANCELLED/REJECTED/FAILED), a new
     * ACTIVE row is created with a timestamped key so a fresh IBKR position acquired later
     * still gets a closable local row and isn't invisible to CLOSE / MAX_LOSS flows.
     * Quantity comes straight from {@code |livePos|}.
     */
    private void ensureTrackedRowForLivePosition(WtxStrategyState state, String timeframe,
                                                 BigDecimal livePos, BigDecimal price) {
        if (findOpenWtxExecution(state.instrument(), timeframe).isPresent()) {
            // A non-terminal WTX row is already managing a position for this panel — repeated
            // same-side signals within the same lifecycle land here and stay idempotent.
            return;
        }
        int qty = Math.max(1, livePos.abs().intValue());
        // Timestamped key (per-second granularity) prevents the unique-constraint collision
        // with any prior terminal {@code wtx-track:} row from the same panel.
        String trackingKey = "wtx-track:" + state.instrument() + ":" + timeframe + ":"
                + Instant.now().getEpochSecond();
        TradeExecutionRecord row = new TradeExecutionRecord();
        row.setExecutionKey(trackingKey);
        row.setBrokerAccountId(firstNonBlank(wtxProperties.getBrokerAccountId(), "wtx-default"));
        row.setInstrument(state.instrument());
        row.setTimeframe(timeframe);
        row.setAction(livePos.signum() > 0 ? "LONG" : "SHORT");
        row.setQuantity(qty);
        row.setTriggerSource(ExecutionTriggerSource.WTX_AUTO);
        row.setRequestedBy("wtx-reconcile-track");
        row.setStatus(ExecutionStatus.ACTIVE);
        row.setStatusReason("WTX reconcile: tracking IBKR live position " + livePos
                + " (duplicate OPEN skipped — row created so CLOSE/MAX_LOSS can flatten)");
        row.setNormalizedEntryPrice(price);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        executionRepository.createIfAbsent(row);
        log.warn("WTX [{} {}] reconcile: created tracking row for IBKR live position {} (qty={}) — "
                + "future CLOSE/MAX_LOSS will flatten this row",
                state.instrument(), timeframe, livePos, qty);
    }

    /**
     * Quantity of the position the REVERSE close-leg will actually flatten <em>in this same
     * routing call</em>. Used by the preflight to size the margin check on the NET delta of
     * the reverse (open-leg qty minus close-leg qty).
     *
     * <p>Mirrors the exact condition under which {@link #handleEntry}'s REVERSE branch fires a
     * close-leg order:</p>
     * <ul>
     *   <li>If an open WTX row exists and is <b>not</b> already {@code EXIT_SUBMITTED}, the
     *       close leg will fire — return that row's quantity.</li>
     *   <li>If the open WTX row is in {@code EXIT_SUBMITTED} (a flatten is already in flight from
     *       a previous bar), {@link #handleEntry} skips the close-leg submission and the prior
     *       position's margin won't be released in time for the new open. Return 0 so the
     *       preflight gates on the full positionQty — exactly as for a pure OPEN.</li>
     *   <li>If there is no WTX row but IBKR holds an opposite live position, reconcile will
     *       synthesize a close leg for that position — return {@code |liveIbkrPos|}.</li>
     *   <li>Otherwise return 0.</li>
     * </ul>
     */
    private int priorReverseQty(WtxStrategyState state, String timeframe, BigDecimal liveIbkrPosition) {
        Optional<TradeExecutionRecord> prior = findOpenWtxExecution(state.instrument(), timeframe);
        if (prior.isPresent()) {
            TradeExecutionRecord row = prior.get();
            // EXIT_SUBMITTED → a close leg from a previous bar is already in flight (ack-pending).
            // If IBKR STILL holds the position, the flatten has not completed, so the contracts
            // are really there — size against the live broker position so the reverse's delta is
            // correct (a same-size reverse then nets to ~0 and skips the heuristic preflight),
            // instead of treating them as already gone (the old return 0 under-counted and made a
            // 1→1 reverse gate the full qty). Only when IBKR confirms flat (or the snapshot is
            // unavailable) do we fall back to 0.
            if (row.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
                if (liveIbkrPosition != null && liveIbkrPosition.signum() != 0) {
                    return liveIbkrPosition.abs().intValue();
                }
                return 0;
            }
            if (row.getQuantity() != null && row.getQuantity() > 0) {
                return row.getQuantity();
            }
            return 0;
        }
        if (liveIbkrPosition != null && liveIbkrPosition.signum() != 0) {
            return Math.max(0, liveIbkrPosition.abs().intValue());
        }
        return 0;
    }

    /** Carrier for {@link #reconcileWithIbkr}. Exactly one of the fields is non-null. */
    private record ReconcileOutcome(WtxAction action, WtxRoutingResult skipResult) {
        static ReconcileOutcome passthrough(WtxAction action) {
            return new ReconcileOutcome(action, null);
        }
        static ReconcileOutcome skip(WtxRoutingResult result) {
            return new ReconcileOutcome(null, result);
        }
    }

    /**
     * The bridge's own (WTX_AUTO) most-recent non-terminal execution for an
     * (instrument, timeframe). Scoped to the timeframe because WTX state is
     * per-timeframe — a 10m close/reverse must never target a 5m row.
     */
    private Optional<TradeExecutionRecord> findOpenWtxExecution(String instrument, String timeframe) {
        return executionRepository.findActiveByInstrumentAndTimeframeAndTriggerSource(
                instrument, timeframe, ExecutionTriggerSource.WTX_AUTO);
    }

    /** Marks a row {@code CANCELLED} with a reason and persists it. No broker side effect. */
    private void voidRow(TradeExecutionRecord row, String reason) {
        row.setStatus(ExecutionStatus.CANCELLED);
        row.setStatusReason(reason);
        row.setUpdatedAt(Instant.now());
        executionRepository.save(row);
    }

    /**
     * Maps a WTX action to the broker-side direction token carried on {@code TradeExecutionRecord.action}
     * and {@code BrokerEntryOrderRequest.action}.
     *
     * Returns "LONG" / "SHORT" — the convention {@code IbGatewayBrokerGateway} understands
     * (only "SHORT" maps to {@code Action.SELL}; anything else is a BUY) and which mentor
     * reviews already use. "BUY"/"SELL" would be silently treated as a BUY by the native
     * gateway, so a CLOSE_LONG would increase the long instead of flattening it.
     */
    private String mapToOrderAction(WtxAction action) {
        return switch (action) {
            case OPEN_LONG, REVERSE_TO_LONG, CLOSE_SHORT -> "LONG";   // buy side
            case OPEN_SHORT, REVERSE_TO_SHORT, CLOSE_LONG -> "SHORT"; // sell side
            default -> "";
        };
    }

    /**
     * Resulting WTX position size (contracts). Prefers the user-configured panel quantity
     * (the value backing the qty input on the WTX panel); falls back to {@code state.entryQty}
     * for legacy paths where the state hasn't yet been updated by {@code applyAction}. Never
     * the doubled REVERSE order quantity — close and open legs are sized independently.
     */
    private int positionQuantity(WtxStrategyState state) {
        if (state.configuredOrderQty() > 0) {
            return state.configuredOrderQty();
        }
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

    /**
     * Internal carrier for the outcome of {@link #submitCloseLeg}. Replaces the old
     * boolean return so the caller (handleEntry's REVERSE branch, handleClose) can propagate
     * the typed outcome and the broker message into the final {@link WtxRoutingResult}.
     */
    private record CloseLegResult(boolean accepted, WtxRoutingOutcome outcomeOnFailure, String errorMessage) {
        static CloseLegResult ok() {
            return new CloseLegResult(true, null, null);
        }
        static CloseLegResult rejected(WtxRoutingOutcome outcome, String message) {
            return new CloseLegResult(false, outcome, message);
        }
    }
}

package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrMarginPreflightService;
import com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision;
import com.riskdesk.application.service.IbkrOrderService;
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

    /** Test-only legacy constructor — production code uses the 5-arg variant via Spring autowiring. */
    public WtxExecutionBridge(IbkrOrderService ibkrOrderService,
                              TradeExecutionRepositoryPort executionRepository,
                              IbkrProperties ibkrProperties,
                              WtxStrategyProperties wtxProperties) {
        this(ibkrOrderService, executionRepository, ibkrProperties, wtxProperties, null);
    }

    @Autowired
    public WtxExecutionBridge(IbkrOrderService ibkrOrderService,
                              TradeExecutionRepositoryPort executionRepository,
                              IbkrProperties ibkrProperties,
                              WtxStrategyProperties wtxProperties,
                              IbkrMarginPreflightService marginPreflight) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.wtxProperties = wtxProperties;
        this.marginPreflight = marginPreflight;
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
        String orderAction = mapToOrderAction(action);

        // Pre-flight margin check (slice 2): for a REVERSE we estimate the worst-case
        // exposure as close-leg + open-leg = 2 × positionQty. This is conservative — IBKR
        // typically nets out the close-leg margin once the fill arrives, but the broker may
        // still reject the first leg if account funds are below the gross margin at submit
        // time (the prod bug: Equity 9757 < InitMargin 11729 at 09:20Z).
        if (marginPreflight != null) {
            int preflightQty = isReverse ? 2 * positionQty : positionQty;
            PreflightDecision decision = marginPreflight.canAffordOrder(instrument, orderAction, preflightQty, price);
            if (!decision.allowed()) {
                log.warn("WTX [{} {}] routing denied by pre-flight — {}",
                        state.instrument(), tf, decision.denyReason());
                return WtxRoutingResult.of(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN,
                        truncate(decision.denyReason(), 200));
            }
        }

        // REVERSE: flatten the prior position with its own close-leg order BEFORE opening the
        // new one. Every open-leg precondition is already validated above, so the close leg
        // only fires once we know the open will proceed. If the close leg is rejected we abort
        // — nothing is opened and the live position keeps its active row for the next bar.
        if (isReverse) {
            Optional<TradeExecutionRecord> prior = findOpenWtxExecution(state.instrument(), tf);
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
            }
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
            // Typed broker exception: map kind → outcome and persist a clean reason.
            return handleEntryRejection(persisted, action, e);
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
     */
    private WtxRoutingResult handleEntryRejection(TradeExecutionRecord row, WtxAction action,
                                                  IbkrOrderRejectionException e) {
        String brokerText = e.brokerMessage() != null ? e.brokerMessage() : e.getMessage();
        String shortMsg = truncate(brokerText, 200);
        WtxRoutingOutcome outcome;
        switch (e.kind()) {
            case INSUFFICIENT_MARGIN -> {
                outcome = WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN;
                // Leave row ACTIVE (still pending) — no position change at the broker.
                row.setStatusReason("WTX " + action.name() + " skipped — insufficient margin: " + shortMsg);
                log.warn("WTX [{} {}] open leg skipped — INSUFFICIENT_MARGIN ({})",
                        row.getInstrument(), row.getTimeframe(), shortMsg);
            }
            case TIMEOUT -> {
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
                case INSUFFICIENT_MARGIN -> WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN;
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
            } else if (outcome == WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN) {
                suffix = " close skipped — insufficient margin: " + shortMsg;
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
     * The bridge's own (WTX_AUTO) most-recent non-terminal execution for an
     * (instrument, timeframe). Scoped to the timeframe because WTX state is
     * per-timeframe — a 10m close/reverse must never target a 5m row.
     */
    private Optional<TradeExecutionRecord> findOpenWtxExecution(String instrument, String timeframe) {
        return executionRepository.findActiveByInstrumentAndTimeframeAndTriggerSource(
                instrument, timeframe, ExecutionTriggerSource.WTX_AUTO);
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

    /** Resulting WTX position size (contracts). Never the doubled REVERSE order quantity. */
    private int positionQuantity(WtxStrategyState state) {
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

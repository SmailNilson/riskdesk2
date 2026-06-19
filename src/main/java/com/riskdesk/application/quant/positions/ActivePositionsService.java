package com.riskdesk.application.quant.positions;

import com.riskdesk.application.dto.ActivePositionView;
import com.riskdesk.application.execution.OrderRouter;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.positions.ActivePositionChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Application-layer use case for the Active Positions panel and the chart-trading surface.
 *
 * <p>Three responsibilities:
 * <ol>
 *   <li>{@link #listActive()} — fetch every non-terminal execution and
 *       enrich each row with a server-side PnL snapshot computed from the
 *       latest live-price reading. This is the cold-start payload used by
 *       the panel before the WS price stream warms up.</li>
 *   <li>{@link #closePosition(Long, String)} — operator-driven close. Rows that
 *       never reached the broker (PENDING_ENTRY_SUBMISSION) transition straight to
 *       {@link ExecutionStatus#CANCELLED}; an unfilled resting entry (ENTRY_SUBMITTED)
 *       is cancelled at the broker via {@link #cancelEntry(Long, String)}; a live
 *       position is flattened through the unified {@link OrderRouter} (broker-truth
 *       reconciliation, marketable exit pricing, stuck-close re-fire) so the close
 *       actually reaches IBKR — marking EXIT_SUBMITTED without a broker order would
 *       strand the live position (app/broker drift).</li>
 *   <li>{@link #cancelEntry(Long, String)} — operator-driven cancel of an entry that
 *       has NOT filled. PENDING rows cancel locally; ENTRY_SUBMITTED rows fire a real
 *       broker cancel by {@code ibkrOrderId}; the row is finalized to CANCELLED by the
 *       broker's Cancelled callback (ExecutionFillTrackingService), not here.</li>
 * </ol>
 *
 * <p>An {@link ActivePositionChangedEvent} is published on every state
 * transition so the WebSocket adapter can fan out the new list without the
 * caller having to know about messaging. Event publication failures are
 * never propagated — the executed close must remain atomic from the API
 * caller's perspective.</p>
 */
@Service
public class ActivePositionsService {

    private static final Logger log = LoggerFactory.getLogger(ActivePositionsService.class);

    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final LivePricePort livePricePort;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderRouter orderRouter;
    private final IbkrOrderService ibkrOrderService;
    private final Clock clock;

    public ActivePositionsService(TradeExecutionRepositoryPort tradeExecutionRepository,
                                  LivePricePort livePricePort,
                                  ApplicationEventPublisher eventPublisher,
                                  OrderRouter orderRouter,
                                  IbkrOrderService ibkrOrderService,
                                  Clock clock) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.livePricePort = livePricePort;
        this.eventPublisher = eventPublisher;
        this.orderRouter = orderRouter;
        this.ibkrOrderService = ibkrOrderService;
        this.clock = clock;
    }

    /**
     * Snapshot of every currently-open (non-terminal) execution, enriched
     * with a server-side PnL computation. The price cache is keyed by
     * instrument so we hit the live-price port once per distinct symbol
     * rather than once per row.
     */
    public List<ActivePositionView> listActive() {
        List<TradeExecutionRecord> active = tradeExecutionRepository.findAllActive();
        if (active.isEmpty()) {
            return List.of();
        }
        Map<Instrument, BigDecimal> priceCache = new EnumMap<>(Instrument.class);
        return active.stream()
            .map(rec -> ActivePositionView.from(rec, priceFor(rec.getInstrument(), priceCache)))
            .toList();
    }

    /**
     * Close a position by execution id.
     *
     * <p>Branches on what actually exists at the broker:
     * <ul>
     *   <li>{@code PENDING_ENTRY_SUBMISSION} — nothing at the broker: cancel locally.</li>
     *   <li>{@code ENTRY_SUBMITTED} (no fills) — a resting limit order: cancel it at the
     *       broker (delegates to {@link #cancelEntry}); "closing" would be a naked order.</li>
     *   <li>broker-known position states — route a FLATTEN intent through the unified
     *       router, which reads broker truth, prices the exit marketable and re-fires a
     *       stuck close. {@code SKIPPED_IBKR_DISABLED} falls back to the legacy local
     *       EXIT_SUBMITTED mark (no broker to talk to — e.g. tests / paper without IBKR).</li>
     * </ul>
     *
     * @return the updated execution view, or empty if the id does not exist
     * @throws IllegalStateException when the close cannot be executed safely (surfaced as 409)
     */
    public Optional<ActivePositionView> closePosition(Long executionId, String requestedBy) {
        if (executionId == null) {
            return Optional.empty();
        }
        Optional<TradeExecutionRecord> opt = tradeExecutionRepository.findByIdForUpdate(executionId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        TradeExecutionRecord exec = opt.get();
        ExecutionStatus current = exec.getStatus();

        if (current == null) {
            throw new IllegalStateException("execution status is null — cannot close");
        }
        if (isTerminal(current)) {
            // Idempotence — surface the existing terminal row rather than 409.
            return Optional.of(view(exec));
        }

        Instant now = clock.instant();
        String who = (requestedBy == null || requestedBy.isBlank()) ? "operator" : requestedBy;

        if (current == ExecutionStatus.PENDING_ENTRY_SUBMISSION) {
            cancelLocally(exec, "Cancelled by " + who + " — close requested before broker submission.", now);
            return Optional.of(view(exec));
        }

        if (current == ExecutionStatus.ENTRY_SUBMITTED && !hasFills(exec)) {
            // A resting, unfilled limit order — there is no position to flatten. Cancel the
            // order instead of firing a reducing leg that would be naked once it filled.
            return cancelEntry(executionId, requestedBy);
        }

        Instrument instrument = parseInstrument(exec.getInstrument());
        if (instrument == null) {
            throw new IllegalStateException("unknown instrument on execution row: " + exec.getInstrument());
        }

        TradeIntent intent = flattenIntent(exec, instrument, now);
        RoutingResult result = orderRouter.route(intent);
        log.info("Active position close routed executionId={} instrument={} outcome={} message={} by={}",
            exec.getId(), exec.getInstrument(), result.outcome(), result.message(), who);

        switch (result.outcome()) {
            case ROUTED, ROUTED_FLATTEN_ONLY, ACK_PENDING, SKIPPED_DUPLICATE, SKIPPED_NO_OPEN_ROW -> {
                // The router mutated (or deliberately voided / left) the row — reload broker truth's
                // version of it rather than the stale pre-route copy.
                TradeExecutionRecord refreshed = tradeExecutionRepository.findById(executionId).orElse(exec);
                publishSafely(changeEvent(refreshed, now));
                return Optional.of(view(refreshed));
            }
            case SKIPPED_IBKR_DISABLED -> {
                // Legacy behaviour for IBKR-less environments: mark the exit locally so the
                // simulation / virtual-exit flow can finish the lifecycle.
                exec.setStatus(ExecutionStatus.EXIT_SUBMITTED);
                exec.setStatusReason("Exit requested by " + who + " (IBKR disabled — local mark only).");
                exec.setExitSubmittedAt(now);
                exec.setUpdatedAt(now);
                TradeExecutionRecord saved = tradeExecutionRepository.save(exec);
                publishSafely(changeEvent(saved, now));
                return Optional.of(view(saved));
            }
            case SKIPPED_ENTRY_IN_FLIGHT -> throw new IllegalStateException(
                "entry order not (fully) filled — cancel the entry instead of closing: " + result.message());
            default -> throw new IllegalStateException(
                "close not executed (" + result.outcome() + "): " + result.message());
        }
    }

    /**
     * Cancel an entry order that has not produced any fill. PENDING rows cancel locally
     * (idempotent with {@link #closePosition}); ENTRY_SUBMITTED rows fire a broker cancel by
     * {@code ibkrOrderId} and KEEP their status — the broker's {@code Cancelled} callback
     * (ExecutionFillTrackingService) is the single writer that finalizes the row to CANCELLED,
     * so a cancel raced by a fill can still resolve to ACTIVE instead of masking a live position.
     *
     * @return the (possibly unchanged) execution view, or empty if the id does not exist
     * @throws IllegalStateException when the row has fills / is broker-live (use close), or the
     *                               broker refuses the cancel (surfaced as 409)
     */
    public Optional<ActivePositionView> cancelEntry(Long executionId, String requestedBy) {
        if (executionId == null) {
            return Optional.empty();
        }
        Optional<TradeExecutionRecord> opt = tradeExecutionRepository.findByIdForUpdate(executionId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        TradeExecutionRecord exec = opt.get();
        ExecutionStatus current = exec.getStatus();
        if (current == null) {
            throw new IllegalStateException("execution status is null — cannot cancel");
        }
        if (isTerminal(current)) {
            return Optional.of(view(exec));
        }

        Instant now = clock.instant();
        String who = (requestedBy == null || requestedBy.isBlank()) ? "operator" : requestedBy;

        if (current == ExecutionStatus.PENDING_ENTRY_SUBMISSION) {
            cancelLocally(exec, "Cancelled by " + who + " — entry was never submitted to the broker.", now);
            return Optional.of(view(exec));
        }
        if (current != ExecutionStatus.ENTRY_SUBMITTED || hasFills(exec)) {
            throw new IllegalStateException("execution " + executionId + " is " + current
                + (hasFills(exec) ? " with fills" : "") + " — cancel only applies to an unfilled entry; use close");
        }
        Integer ibkrOrderId = exec.getIbkrOrderId();
        if (ibkrOrderId == null) {
            throw new IllegalStateException("execution " + executionId
                + " has no broker order id yet (late ack) — retry shortly or wait for the reconciler");
        }

        String brokerFeedback = ibkrOrderService.cancelOrder(ibkrOrderId);

        // Deliberately NOT CANCELLED here: the Cancelled orderStatus callback owns that transition.
        exec.setStatusReason("Cancel requested by " + who + " — broker says: " + brokerFeedback);
        exec.setUpdatedAt(now);
        TradeExecutionRecord saved = tradeExecutionRepository.save(exec);
        publishSafely(changeEvent(saved, now));
        log.info("Entry cancel requested executionId={} ibkrOrderId={} feedback={} by={}",
            saved.getId(), ibkrOrderId, brokerFeedback, who);
        return Optional.of(view(saved));
    }

    /**
     * Reverse a live position by execution id: flip to the opposite side at the same size, orchestrated
     * atomically by the unified {@link OrderRouter} (REVERSE intent — broker-truth reconciliation,
     * marketable exit pricing, deferred open after the close leg fills).
     *
     * <p>Only a broker-live position can be reversed. A row that never reached the broker
     * ({@code PENDING_ENTRY_SUBMISSION}) or a resting, unfilled entry ({@code ENTRY_SUBMITTED} with no
     * fills) has no position to flip — cancel it and place the opposite entry instead (surfaced as 409).</p>
     *
     * @return the updated execution view (the original row, flattened by the reverse — the freshly
     *         opened opposite position arrives via {@code /topic/positions}), or empty if the id is unknown
     * @throws IllegalStateException when there is no live position to reverse or the router refuses it
     *                               (surfaced as 409)
     */
    public Optional<ActivePositionView> reversePosition(Long executionId, String requestedBy) {
        if (executionId == null) {
            return Optional.empty();
        }
        Optional<TradeExecutionRecord> opt = tradeExecutionRepository.findByIdForUpdate(executionId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        TradeExecutionRecord exec = opt.get();
        ExecutionStatus current = exec.getStatus();
        if (current == null) {
            throw new IllegalStateException("execution status is null — cannot reverse");
        }
        if (isTerminal(current)) {
            // Idempotence — surface the existing terminal row rather than 409.
            return Optional.of(view(exec));
        }
        // Reverse only flips a CONFIRMED, live position. Every other non-terminal state is unsafe to
        // reverse: a resting / unfilled entry has nothing to flip (cancel it instead), and an exit already
        // in flight (EXIT_SUBMITTED / VIRTUAL_EXIT_TRIGGERED) must resolve first — the unified router does
        // NOT re-fire a close leg for an already-EXIT_SUBMITTED row, so it would open the opposite leg on
        // top of the still-unflattened position (stacked orders). Refuse here rather than route it.
        if (current != ExecutionStatus.ACTIVE) {
            String hint = (current == ExecutionStatus.EXIT_SUBMITTED || current == ExecutionStatus.VIRTUAL_EXIT_TRIGGERED)
                ? "a close is already in progress — wait for it to resolve"
                : "no live position to reverse; cancel the resting entry and place the opposite order";
            throw new IllegalStateException("execution " + executionId + " is " + current + " — " + hint);
        }

        Instrument instrument = parseInstrument(exec.getInstrument());
        if (instrument == null) {
            throw new IllegalStateException("unknown instrument on execution row: " + exec.getInstrument());
        }

        Instant now = clock.instant();
        String who = (requestedBy == null || requestedBy.isBlank()) ? "operator" : requestedBy;

        TradeIntent intent = reverseIntent(exec, instrument, now);
        RoutingResult result = orderRouter.route(intent);
        log.info("Active position reverse routed executionId={} instrument={} toSide={} outcome={} message={} by={}",
            exec.getId(), exec.getInstrument(), intent.side(), result.outcome(), result.message(), who);

        switch (result.outcome()) {
            case ROUTED, ROUTED_FLATTEN_ONLY, ACK_PENDING, SKIPPED_DUPLICATE, SKIPPED_NO_OPEN_ROW -> {
                // The router mutated (closed the old row, opened the new side, or voided) — reload broker
                // truth's version rather than the stale pre-route copy.
                TradeExecutionRecord refreshed = tradeExecutionRepository.findById(executionId).orElse(exec);
                publishSafely(changeEvent(refreshed, now));
                return Optional.of(view(refreshed));
            }
            case SKIPPED_IBKR_DISABLED -> {
                // IBKR-less env (paper / tests): degrade like closePosition rather than a confusing 409 — mark
                // the exit locally (the open-opposite leg can't happen without a broker).
                exec.setStatus(ExecutionStatus.EXIT_SUBMITTED);
                exec.setStatusReason("Reverse requested by " + who + " (IBKR disabled — local close mark only).");
                exec.setExitSubmittedAt(now);
                exec.setUpdatedAt(now);
                TradeExecutionRecord saved = tradeExecutionRepository.save(exec);
                publishSafely(changeEvent(saved, now));
                return Optional.of(view(saved));
            }
            case SKIPPED_ENTRY_IN_FLIGHT -> throw new IllegalStateException(
                "entry order not (fully) filled — cancel the entry instead of reversing: " + result.message());
            default -> throw new IllegalStateException(
                "reverse not executed (" + result.outcome() + "): " + result.message());
        }
    }

    /**
     * Partially close (scale out) a live position by {@code qty} contracts, keeping the remainder ACTIVE.
     * Routes a {@link com.riskdesk.domain.execution.IntentKind#REDUCE} intent through the unified router
     * (broker-truth reconciliation, marketable exit pricing). Reducing by the whole size (or more) is a full
     * close, delegated to {@link #closePosition}. Only an ACTIVE position can be reduced — a resting / unfilled
     * entry has nothing to scale out (cancel it instead).
     *
     * @return the updated execution view, or empty if the id does not exist
     * @throws IllegalArgumentException when {@code qty < 1} (400)
     * @throws IllegalStateException    when the row is not a live position or the router refuses it (409)
     */
    public Optional<ActivePositionView> reducePosition(Long executionId, int qty, String requestedBy) {
        if (executionId == null) {
            return Optional.empty();
        }
        if (qty < 1) {
            throw new IllegalArgumentException("reduce quantity must be >= 1");
        }
        Optional<TradeExecutionRecord> opt = tradeExecutionRepository.findByIdForUpdate(executionId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        TradeExecutionRecord exec = opt.get();
        ExecutionStatus current = exec.getStatus();
        if (current == null) {
            throw new IllegalStateException("execution status is null — cannot reduce");
        }
        if (isTerminal(current)) {
            return Optional.of(view(exec));
        }
        if (current != ExecutionStatus.ACTIVE) {
            throw new IllegalStateException("execution " + executionId + " is " + current
                + " — only a live ACTIVE position can be partially closed; cancel a resting entry instead");
        }
        int positionQty = exec.getQuantity() != null && exec.getQuantity() > 0 ? exec.getQuantity() : 1;
        if (qty >= positionQty) {
            // Reducing the whole position (or more) is a full close — reuse the flatten path (allowed for any
            // source, exactly like the "Fermer" button is a manual override that can flatten anything).
            return closePosition(executionId, requestedBy);
        }
        // A TRUE partial scale-out is a manual chart feature. A strategy (WTX / playbook / auto-arm) manages its
        // own sizing; a partial reduce here would desync it and overwrite the entry fill metadata its reconciler
        // reads back as the position basis. Refuse non-manual rows (a full close above stays available).
        if (exec.getTriggerSource() != ExecutionTriggerSource.MANUAL_QUANT_PANEL) {
            throw new IllegalStateException("execution " + executionId + " is " + exec.getTriggerSource()
                + " — partial scale-out is only available for manual chart positions");
        }

        Instrument instrument = parseInstrument(exec.getInstrument());
        if (instrument == null) {
            throw new IllegalStateException("unknown instrument on execution row: " + exec.getInstrument());
        }

        Instant now = clock.instant();
        String who = (requestedBy == null || requestedBy.isBlank()) ? "operator" : requestedBy;

        TradeIntent intent = reduceIntent(exec, instrument, qty, now);
        RoutingResult result = orderRouter.route(intent);
        log.info("Active position reduce routed executionId={} instrument={} qty={} outcome={} message={} by={}",
            exec.getId(), exec.getInstrument(), qty, result.outcome(), result.message(), who);

        switch (result.outcome()) {
            case ROUTED, ROUTED_FLATTEN_ONLY, ACK_PENDING, SKIPPED_DUPLICATE, SKIPPED_NO_OPEN_ROW -> {
                TradeExecutionRecord refreshed = tradeExecutionRepository.findById(executionId).orElse(exec);
                publishSafely(changeEvent(refreshed, now));
                return Optional.of(view(refreshed));
            }
            case SKIPPED_IBKR_DISABLED -> {
                // IBKR-less env: a partial reduce needs a broker fill to be real — there is nothing to decrement
                // locally. Return the unchanged position (with a note) rather than a confusing 409.
                exec.setStatusReason("Reduce requested by " + who + " (IBKR disabled — no-op, position unchanged).");
                exec.setUpdatedAt(now);
                TradeExecutionRecord saved = tradeExecutionRepository.save(exec);
                publishSafely(changeEvent(saved, now));
                return Optional.of(view(saved));
            }
            case SKIPPED_ENTRY_IN_FLIGHT -> throw new IllegalStateException(
                "entry order not (fully) filled — cancel the entry instead of reducing: " + result.message());
            default -> throw new IllegalStateException(
                "reduce not executed (" + result.outcome() + "): " + result.message());
        }
    }

    /**
     * Modify the VIRTUAL stop-loss / take-profit of a non-terminal position (chart drag-to-edit). Each
     * supplied level is validated against the entry geometry (LONG: SL &lt; entry &lt; TP; SHORT mirror),
     * normalized to the contract tick, persisted, and broadcast on {@code /topic/positions}. NO broker
     * order is placed — the levels stay virtual; {@link VirtualStopWatcher} acts on them app-side when
     * enabled. Pass either level (or both); at least one is required.
     *
     * @return the updated execution view, or empty if the id does not exist
     * @throws IllegalArgumentException when neither level is supplied or a level is on the wrong side (400)
     * @throws IllegalStateException    when the row is terminal (409)
     */
    public Optional<ActivePositionView> modifyProtection(Long executionId, BigDecimal stopLoss,
                                                         BigDecimal takeProfit, String requestedBy) {
        if (executionId == null) {
            return Optional.empty();
        }
        if (stopLoss == null && takeProfit == null) {
            throw new IllegalArgumentException("at least one of stopLoss / takeProfit is required");
        }
        Optional<TradeExecutionRecord> opt = tradeExecutionRepository.findByIdForUpdate(executionId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        TradeExecutionRecord exec = opt.get();
        ExecutionStatus current = exec.getStatus();
        if (current == null || isTerminal(current)) {
            throw new IllegalStateException("execution " + executionId + " is " + current
                + " — cannot modify protection on a terminal position");
        }
        // Chart drag-to-edit owns the VIRTUAL SL/TP for MANUAL positions only. Strategy rows (WTX / playbook /
        // auto-arm) store their OWN protective / trailing levels in these same fields and their reconcilers read
        // them back — letting the chart overwrite them would corrupt live strategy state. Refuse non-manual rows.
        if (exec.getTriggerSource() != ExecutionTriggerSource.MANUAL_QUANT_PANEL) {
            throw new IllegalStateException("execution " + executionId + " is " + exec.getTriggerSource()
                + " — SL/TP can only be edited on a manual chart position");
        }

        Instrument instrument = parseInstrument(exec.getInstrument());
        BigDecimal ref = exec.getNormalizedEntryPrice();
        boolean shortSide = "SHORT".equalsIgnoreCase(exec.getAction()) || "SELL".equalsIgnoreCase(exec.getAction());
        // Normalize to the contract tick BEFORE validating geometry. Validating the raw value then storing the
        // rounded one (HALF_UP) could snap a just-inside level ONTO the entry — e.g. MNQ entry 27000.00, SL
        // 26999.90 passes "< entry" but rounds to 27000.00 == entry — silently defeating the side check and
        // leaving the position with no protection (an immediate breach once the watcher is on).
        BigDecimal sl = stopLoss == null ? null : normalizeTick(stopLoss, instrument);
        BigDecimal tp = takeProfit == null ? null : normalizeTick(takeProfit, instrument);
        if (ref != null && ref.signum() > 0) {
            if (sl != null && !shortSide && sl.compareTo(ref) >= 0) {
                throw new IllegalArgumentException("LONG stopLoss must be below the entry " + ref);
            }
            if (sl != null && shortSide && sl.compareTo(ref) <= 0) {
                throw new IllegalArgumentException("SHORT stopLoss must be above the entry " + ref);
            }
            if (tp != null && !shortSide && tp.compareTo(ref) <= 0) {
                throw new IllegalArgumentException("LONG takeProfit must be above the entry " + ref);
            }
            if (tp != null && shortSide && tp.compareTo(ref) >= 0) {
                throw new IllegalArgumentException("SHORT takeProfit must be below the entry " + ref);
            }
        }

        Instant now = clock.instant();
        if (sl != null) {
            exec.setVirtualStopLoss(sl);
        }
        if (tp != null) {
            exec.setVirtualTakeProfit(tp);
        }
        exec.setUpdatedAt(now);
        TradeExecutionRecord saved = tradeExecutionRepository.save(exec);
        publishSafely(changeEvent(saved, now));
        log.info("Protection modified executionId={} sl={} tp={} by={}",
            saved.getId(), saved.getVirtualStopLoss(), saved.getVirtualTakeProfit(),
            (requestedBy == null || requestedBy.isBlank()) ? "operator" : requestedBy);
        return Optional.of(view(saved));
    }

    /**
     * FLATTEN intent for an operator close: per-request idempotency key (each click is a fresh
     * close attempt — required for the router's stuck-close re-fire), the row's own scope
     * (source / timeframe / account) so {@code findOpenRow} resolves this strategy's row, and the
     * latest live price as the passive limit fallback (the router crosses it marketable when
     * enabled). Falls back to the entry price when no live reading exists — still a valid limit,
     * and strictly better than refusing the close.
     */
    private TradeIntent flattenIntent(TradeExecutionRecord exec, Instrument instrument, Instant now) {
        BigDecimal limitPrice = priceFor(exec.getInstrument(), new EnumMap<>(Instrument.class));
        if (limitPrice == null || limitPrice.signum() <= 0) {
            limitPrice = exec.getNormalizedEntryPrice();
        }
        if (limitPrice == null || limitPrice.signum() <= 0) {
            throw new IllegalStateException("no usable price to build the close order for " + exec.getInstrument());
        }
        ExecutionTriggerSource source = exec.getTriggerSource() != null
            ? exec.getTriggerSource() : ExecutionTriggerSource.MANUAL_QUANT_PANEL;
        String timeframe = exec.getTimeframe() == null || exec.getTimeframe().isBlank()
            ? "manual" : exec.getTimeframe();
        int quantity = exec.getQuantity() != null && exec.getQuantity() > 0 ? exec.getQuantity() : 1;
        return TradeIntent.flatten(
            "panel-close:" + exec.getId() + ":" + now.toEpochMilli(),
            source, instrument, timeframe, quantity, limitPrice, exec.getBrokerAccountId());
    }

    /**
     * REVERSE intent for an operator flip: target the side OPPOSITE the current position at the same
     * size, with the latest live price as the marketable-limit reference (router crosses it when
     * enabled). Mirrors {@link #flattenIntent}'s price / source / timeframe resolution.
     */
    private TradeIntent reverseIntent(TradeExecutionRecord exec, Instrument instrument, Instant now) {
        BigDecimal limitPrice = priceFor(exec.getInstrument(), new EnumMap<>(Instrument.class));
        if (limitPrice == null || limitPrice.signum() <= 0) {
            limitPrice = exec.getNormalizedEntryPrice();
        }
        if (limitPrice == null || limitPrice.signum() <= 0) {
            throw new IllegalStateException("no usable price to build the reverse order for " + exec.getInstrument());
        }
        ExecutionTriggerSource source = exec.getTriggerSource() != null
            ? exec.getTriggerSource() : ExecutionTriggerSource.MANUAL_QUANT_PANEL;
        String timeframe = exec.getTimeframe() == null || exec.getTimeframe().isBlank()
            ? "manual" : exec.getTimeframe();
        int quantity = exec.getQuantity() != null && exec.getQuantity() > 0 ? exec.getQuantity() : 1;
        return TradeIntent.reverse(
            "panel-reverse:" + exec.getId() + ":" + now.toEpochMilli(),
            source, instrument, timeframe, oppositeSide(exec.getAction()), quantity, limitPrice,
            exec.getBrokerAccountId());
    }

    /**
     * REDUCE intent for an operator scale-out: reduce the HELD side by {@code qty} contracts at the latest
     * live price (marketable). Mirrors {@link #flattenIntent}'s price / source / timeframe resolution.
     */
    private TradeIntent reduceIntent(TradeExecutionRecord exec, Instrument instrument, int qty, Instant now) {
        BigDecimal limitPrice = priceFor(exec.getInstrument(), new EnumMap<>(Instrument.class));
        if (limitPrice == null || limitPrice.signum() <= 0) {
            limitPrice = exec.getNormalizedEntryPrice();
        }
        if (limitPrice == null || limitPrice.signum() <= 0) {
            throw new IllegalStateException("no usable price to build the reduce order for " + exec.getInstrument());
        }
        ExecutionTriggerSource source = exec.getTriggerSource() != null
            ? exec.getTriggerSource() : ExecutionTriggerSource.MANUAL_QUANT_PANEL;
        String timeframe = exec.getTimeframe() == null || exec.getTimeframe().isBlank()
            ? "manual" : exec.getTimeframe();
        return TradeIntent.reduce(
            "panel-reduce:" + exec.getId() + ":" + now.toEpochMilli(),
            source, instrument, timeframe, sideOf(exec.getAction()), qty, limitPrice, exec.getBrokerAccountId());
    }

    /** The row's current side. Accepts the LONG/SHORT and BUY/SELL action tokens. */
    private static Side sideOf(String action) {
        boolean shortNow = action != null
            && ("SHORT".equalsIgnoreCase(action) || "SELL".equalsIgnoreCase(action));
        return shortNow ? Side.SHORT : Side.LONG;
    }

    /** Opposite of the row's current side. */
    private static Side oppositeSide(String action) {
        return sideOf(action) == Side.LONG ? Side.SHORT : Side.LONG;
    }

    /** Round a price to the instrument's tick (mirrors the manual-trade ticket); pass-through if unknown. */
    private static BigDecimal normalizeTick(BigDecimal price, Instrument instrument) {
        if (price == null || instrument == null) {
            return price;
        }
        BigDecimal tick = instrument.getTickSize();
        return price.divide(tick, 0, RoundingMode.HALF_UP)
            .multiply(tick)
            .setScale(tick.scale(), RoundingMode.HALF_UP);
    }

    private void cancelLocally(TradeExecutionRecord exec, String reason, Instant now) {
        exec.setStatus(ExecutionStatus.CANCELLED);
        exec.setStatusReason(reason);
        exec.setUpdatedAt(now);
        tradeExecutionRepository.save(exec);
        publishSafely(changeEvent(exec, now));
    }

    private ActivePositionView view(TradeExecutionRecord exec) {
        return ActivePositionView.from(exec, priceFor(exec.getInstrument(), new EnumMap<>(Instrument.class)));
    }

    private static ActivePositionChangedEvent changeEvent(TradeExecutionRecord exec, Instant now) {
        return new ActivePositionChangedEvent(
            exec.getId(),
            exec.getInstrument(),
            exec.getStatus(),
            ActivePositionChangedEvent.Kind.CLOSE_REQUESTED,
            now
        );
    }

    private static boolean hasFills(TradeExecutionRecord exec) {
        return exec.getFilledQuantity() != null && exec.getFilledQuantity().signum() > 0;
    }

    private BigDecimal priceFor(String instrumentName, Map<Instrument, BigDecimal> cache) {
        Instrument instrument = parseInstrument(instrumentName);
        if (instrument == null) return null;
        if (cache.containsKey(instrument)) return cache.get(instrument);
        BigDecimal price = livePricePort.current(instrument)
            .map(snap -> BigDecimal.valueOf(snap.price()))
            .orElse(null);
        cache.put(instrument, price);
        return price;
    }

    private static boolean isTerminal(ExecutionStatus status) {
        return switch (status) {
            case CLOSED, CANCELLED, REJECTED, FAILED -> true;
            default -> false;
        };
    }

    private static Instrument parseInstrument(String name) {
        try {
            return name == null ? null : Instrument.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void publishSafely(ActivePositionChangedEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            // Notification hiccups must never break a successful close.
            log.debug("ActivePositionChangedEvent publish failed: {}", e.toString());
        }
    }
}

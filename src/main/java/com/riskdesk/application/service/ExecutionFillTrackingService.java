package com.riskdesk.application.service;

import com.riskdesk.application.execution.DefaultOrderRouter;
import com.riskdesk.domain.execution.port.ExecutionFillListener;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.application.execution.ExecutionTopicPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Slice 3a — IBKR execDetails + orderStatus fill tracking.
 *
 * <p>Receives raw broker feedback from the IBKR adapter (via the
 * {@link ExecutionFillListener} domain port) and persists it on the matching
 * {@link TradeExecutionRecord}. Updates fill-specific fields and the domain
 * lifecycle on {@code Filled}: an entry fill transitions to
 * {@link ExecutionStatus#ACTIVE}, while a fill on an {@link ExecutionStatus#EXIT_SUBMITTED}
 * row (e.g. a WTX auto-routed close) transitions to {@link ExecutionStatus#CLOSED}.
 * It DOES NOT place any IBKR child orders — bracket / virtual exit orchestration
 * is future slice 3c.</p>
 *
 * <p>Idempotence:</p>
 * <ul>
 *   <li>{@code execDetails} is deduped by IBKR {@code execId} stored in
 *       {@code lastExecId}. A replay of the same fill is a no-op.</li>
 *   <li>{@code orderStatus} is deduped by comparing the new raw fields against
 *       the persisted ones — if nothing material changed, no save, no publish.</li>
 * </ul>
 *
 * <p>Every state-changing update is published on {@code /topic/executions}.</p>
 */
@Service
public class ExecutionFillTrackingService implements ExecutionFillListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionFillTrackingService.class);
    private static final String IBKR_STATUS_FILLED = "Filled";
    private static final String IBKR_STATUS_SUBMITTED = "Submitted";
    private static final String IBKR_STATUS_PRE_SUBMITTED = "PreSubmitted";
    private static final String IBKR_STATUS_PENDING_SUBMIT = "PendingSubmit";
    private static final String IBKR_STATUS_CANCELLED = "Cancelled";
    private static final String IBKR_STATUS_API_CANCELLED = "ApiCancelled";
    private static final String IBKR_STATUS_INACTIVE = "Inactive";

    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final ExecutionTopicPublisher executionTopicPublisher;

    public ExecutionFillTrackingService(TradeExecutionRepositoryPort tradeExecutionRepository,
                                        ExecutionTopicPublisher executionTopicPublisher) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.executionTopicPublisher = executionTopicPublisher;
    }

    @Override
    public synchronized void onExecDetails(int orderId,
                                           long permId,
                                           String execId,
                                           String orderRef,
                                           BigDecimal cumQty,
                                           BigDecimal avgPrice,
                                           BigDecimal lastFillPrice,
                                           String side,
                                           Instant time) {
        Optional<TradeExecutionRecord> found = locate(permId, orderId, orderRef);
        if (found.isEmpty()) {
            log.debug("execDetails ignored — no TradeExecution for permId={} orderId={} orderRef={}",
                permId, orderId, orderRef);
            return;
        }
        TradeExecutionRecord execution = found.get();

        if (execId != null && !execId.isBlank() && execId.equals(execution.getLastExecId())) {
            log.debug("execDetails dedup — execId={} already applied to execution {}", execId, execution.getId());
            return;
        }

        boolean dirty = capturePermId(execution, permId);
        if (execution.getIbkrOrderId() == null || execution.getIbkrOrderId() != orderId) {
            execution.setIbkrOrderId(orderId);
            dirty = true;
        }
        if (cumQty != null && !cumQty.equals(execution.getFilledQuantity())) {
            execution.setFilledQuantity(cumQty);
            dirty = true;
        }
        if (avgPrice != null && !avgPrice.equals(execution.getAvgFillPrice())) {
            execution.setAvgFillPrice(avgPrice);
            dirty = true;
        }
        if (time != null && !time.equals(execution.getLastFillTime())) {
            execution.setLastFillTime(time);
            dirty = true;
        }
        if (execId != null && !execId.isBlank()) {
            execution.setLastExecId(execId);
            dirty = true;
        }

        if (!dirty) {
            return;
        }

        Instant now = Instant.now();
        execution.setUpdatedAt(now);

        TradeExecutionRecord saved = tradeExecutionRepository.save(execution);
        log.info("IBKR execDetails applied — execution={} orderId={} execId={} cumQty={} avgPx={} side={}",
            saved.getId(), orderId, execId, cumQty, avgPrice, side);
        executionTopicPublisher.publish(saved);
    }

    @Override
    public synchronized void onOrderStatus(int orderId,
                                           long permId,
                                           String status,
                                           BigDecimal filled,
                                           BigDecimal remaining,
                                           BigDecimal avgFillPrice,
                                           Instant lastFillTime) {
        Optional<TradeExecutionRecord> found = locate(permId, orderId, null);
        if (found.isEmpty()) {
            log.debug("orderStatus ignored — no TradeExecution for permId={} orderId={}", permId, orderId);
            return;
        }
        TradeExecutionRecord execution = found.get();

        boolean dirty = capturePermId(execution, permId);
        if (execution.getIbkrOrderId() == null || execution.getIbkrOrderId() != orderId) {
            execution.setIbkrOrderId(orderId);
            dirty = true;
        }
        if (status != null && !status.equals(execution.getOrderStatus())) {
            execution.setOrderStatus(status);
            dirty = true;
        }
        if (filled != null && !filled.equals(execution.getFilledQuantity())) {
            execution.setFilledQuantity(filled);
            dirty = true;
        }
        if (avgFillPrice != null && avgFillPrice.signum() > 0 && !avgFillPrice.equals(execution.getAvgFillPrice())) {
            execution.setAvgFillPrice(avgFillPrice);
            dirty = true;
        }
        if (lastFillTime != null && !lastFillTime.equals(execution.getLastFillTime())) {
            execution.setLastFillTime(lastFillTime);
            dirty = true;
        }

        // Domain lifecycle transitions based on IBKR status.
        if (isAcceptedEntryStatus(status)
            && execution.getStatus() == ExecutionStatus.PENDING_ENTRY_SUBMISSION) {
            execution.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            execution.setStatusReason("IBKR entry order acknowledged: " + status);
            dirty = true;
        }

        if (IBKR_STATUS_FILLED.equalsIgnoreCase(status)
            && execution.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
            Integer closingQty = execution.getClosingQuantity();
            Integer rowQty = execution.getQuantity();
            if (closingQty != null && closingQty > 0 && rowQty != null && closingQty < rowQty) {
                // A PARTIAL close (REDUCE) order filled — decrement and keep the remainder LIVE. Without this
                // the whole row would be marked CLOSED while the broker still holds the remainder (drift).
                execution.setQuantity(rowQty - closingQty);
                execution.setClosingQuantity(null);
                execution.setStatus(ExecutionStatus.ACTIVE);
                execution.setStatusReason("IBKR partial close filled — reduced to " + execution.getQuantity());
            } else {
                if (closingQty != null && closingQty > 0) {
                    // Defensive: closingQuantity was stamped but is NOT a strict partial (>= quantity). The
                    // router only ever stamps it for a strict reduce, so this signals an inconsistency — flag
                    // it but still close fully (the safe outcome) so the row never stays stuck non-terminal.
                    log.warn("exit fill on execution {} had closingQuantity={} >= quantity={} — closing fully",
                        execution.getId(), closingQty, rowQty);
                }
                // A submitted (full) exit/close order filled — the position is now flat. Without this
                // branch an EXIT_SUBMITTED row would stay non-terminal forever (still in
                // findAllActive / findActiveByInstrument*), so the position would look open and
                // a later close signal could submit another flatten order.
                execution.setStatus(ExecutionStatus.CLOSED);
                execution.setStatusReason("IBKR exit order fully filled");
                if (execution.getClosedAt() == null) {
                    execution.setClosedAt(lastFillTime == null ? Instant.now() : lastFillTime);
                }
            }
            dirty = true;
        } else if (IBKR_STATUS_FILLED.equalsIgnoreCase(status)
            && execution.getStatus() != ExecutionStatus.ACTIVE
            && execution.getStatus() != ExecutionStatus.CLOSED
            && execution.getStatus() != ExecutionStatus.EXIT_SUBMITTED
            && execution.getStatus() != ExecutionStatus.VIRTUAL_EXIT_TRIGGERED) {
            execution.setStatus(ExecutionStatus.ACTIVE);
            execution.setStatusReason("IBKR order fully filled");
            if (execution.getEntryFilledAt() == null) {
                execution.setEntryFilledAt(lastFillTime == null ? Instant.now() : lastFillTime);
            }
            dirty = true;
        } else if ((IBKR_STATUS_CANCELLED.equalsIgnoreCase(status)
                || IBKR_STATUS_API_CANCELLED.equalsIgnoreCase(status)
                || IBKR_STATUS_INACTIVE.equalsIgnoreCase(status))
            && execution.getStatus() != ExecutionStatus.CANCELLED
            && execution.getStatus() != ExecutionStatus.CLOSED) {
            boolean nothingFilled = execution.getFilledQuantity() == null
                || execution.getFilledQuantity().signum() == 0;
            if (nothingFilled) {
                if (execution.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
                    // A CLOSE/exit order that cancelled WITHOUT a fill did NOT flatten — the position is
                    // still live. Revive the row to ACTIVE (not CANCELLED) so it stays managed; otherwise
                    // the live position is orphaned (a later CLOSE/FLATTEN finds no row) and the WTX close
                    // settler would read the row as gone and wrongly FINALIZE a phantom close's P&L. Detach
                    // BOTH the dead close order id AND its permId so a replayed cancel callback can't
                    // re-locate the revived row (permId-first locate would otherwise re-target it by the
                    // close's still-attached permId and CANCEL a row whose position is still live).
                    execution.setStatus(ExecutionStatus.ACTIVE);
                    execution.setStatusReason("IBKR close order " + status
                        + " without a fill — position still live, revived to ACTIVE");
                    execution.setIbkrOrderId(null);
                    execution.setPermId(null);
                    // A partial reduce that cancelled without a fill leaves the position at its FULL size —
                    // drop the stale reduce marker so the fill tracker won't later decrement on a replay.
                    execution.setClosingQuantity(null);
                } else {
                    // An entry order (or any non-exit) that cancelled without a fill never opened — terminal.
                    execution.setStatus(ExecutionStatus.CANCELLED);
                    execution.setStatusReason("IBKR order " + status);
                }
                dirty = true;
            } else if (execution.getStatus() == ExecutionStatus.EXIT_SUBMITTED) {
                // Slice 3c — a close/reduce leg PARTIALLY filled then cancelled (cumulative filled > 0). The
                // filled contracts LEFT the position; the remainder is STILL LIVE. The `filled` we received IS
                // broker truth for how much of this leg executed, so decrement the row's quantity by it (the
                // quantity stays the FULL pre-exit size while EXIT_SUBMITTED, for both a full close and a reduce)
                // and revive to ACTIVE — or finalize CLOSED if the partial actually covered the whole position.
                // Without this the row stays stuck EXIT_SUBMITTED with a now-wrong quantity (and, for a reduce, a
                // stale closingQuantity) while the broker holds a different size. Detach BOTH dead ids so a
                // replayed cancel can't re-target the revived row (same reasoning as the no-fill revive above).
                int filledQty = execution.getFilledQuantity().intValue();
                int rowQty = execution.getQuantity() != null ? execution.getQuantity() : 0;
                int remainder = rowQty - filledQty;
                execution.setClosingQuantity(null);
                execution.setIbkrOrderId(null);
                execution.setPermId(null);
                if (remainder > 0) {
                    execution.setQuantity(remainder);
                    execution.setStatus(ExecutionStatus.ACTIVE);
                    execution.setStatusReason("IBKR close " + status + " after partial fill " + filledQty
                        + " — position still live, reduced to " + remainder);
                } else {
                    // filled >= quantity — the partial fill(s) actually covered the position: it is flat now.
                    execution.setStatus(ExecutionStatus.CLOSED);
                    execution.setStatusReason("IBKR close " + status + " after fill " + filledQty
                        + " covered the position — closed");
                    if (execution.getClosedAt() == null) {
                        execution.setClosedAt(lastFillTime == null ? Instant.now() : lastFillTime);
                    }
                }
                dirty = true;
            }
            // else: a non-exit (entry) order partially filled then cancelled leaves a partly-OPEN position —
            // out of scope here (entry partial-fill reconciliation is separate); left unchanged.
        }

        if (!dirty) {
            return;
        }

        execution.setUpdatedAt(Instant.now());
        TradeExecutionRecord saved = tradeExecutionRepository.save(execution);
        log.info("IBKR orderStatus applied — execution={} orderId={} status={} filled={}/{} avgPx={}",
            saved.getId(), orderId, status, filled, remaining, avgFillPrice);
        executionTopicPublisher.publish(saved);
    }

    /**
     * Locate the execution row for a callback. <b>permId first</b> — it is the durable, never-reused
     * broker order id, so it is unambiguous; {@code orderId} is session-scoped and REUSED after a gateway
     * reconnect, so multiple rows can share one and {@code findByIbkrOrderId} would resolve the wrong row.
     * Falls back to {@code orderId} then {@code orderRef}/{@code executionKey} (for callbacks that arrive
     * before the id is persisted, or when permId is 0/unknown).
     */
    private Optional<TradeExecutionRecord> locate(long permId, int orderId, String orderRef) {
        if (permId > 0) {
            Optional<TradeExecutionRecord> byPermId = tradeExecutionRepository.findByPermId(permId);
            if (byPermId.isPresent()) {
                return byPermId;
            }
        }
        Optional<TradeExecutionRecord> byOrderId = tradeExecutionRepository.findByIbkrOrderId(orderId);
        if (byOrderId.isPresent()) {
            return byOrderId;
        }
        if (orderRef == null || orderRef.isBlank()) {
            return Optional.empty();
        }
        // Fallback: orderRef is set at submission to executionKey, so we can
        // recover the linkage even if the TWS orderId hasn't been persisted yet.
        Optional<TradeExecutionRecord> byKey = tradeExecutionRepository.findByExecutionKey(orderRef);
        if (byKey.isPresent()) {
            return byKey;
        }
        // Exit legs submit under "<executionKey>:exit[:<discriminator>]" — a distinct orderRef (so
        // placeLimitOrder's idempotency can't return the completed ENTRY order) plus an optional
        // per-attempt discriminator (so a retried close after a terminal-non-filled one gets a fresh ref).
        // Map any such ref back to the row by the base executionKey (everything before ":exit") so a close
        // callback arriving before the close orderId is persisted is not silently dropped.
        int exitAt = orderRef.indexOf(DefaultOrderRouter.EXIT_ORDER_REF_SUFFIX);
        if (exitAt > 0) {
            return tradeExecutionRepository.findByExecutionKey(orderRef.substring(0, exitAt));
        }
        return Optional.empty();
    }

    /** Persist the durable permId the first time we learn it. Returns true if the row changed. */
    private static boolean capturePermId(TradeExecutionRecord execution, long permId) {
        if (permId > 0 && (execution.getPermId() == null || execution.getPermId() != permId)) {
            execution.setPermId(permId);
            return true;
        }
        return false;
    }

    private boolean isAcceptedEntryStatus(String status) {
        return IBKR_STATUS_SUBMITTED.equalsIgnoreCase(status)
            || IBKR_STATUS_PRE_SUBMITTED.equalsIgnoreCase(status)
            || IBKR_STATUS_PENDING_SUBMIT.equalsIgnoreCase(status);
    }
}

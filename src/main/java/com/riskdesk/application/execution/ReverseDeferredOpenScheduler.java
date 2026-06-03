package com.riskdesk.application.execution;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Slice D — D2: submits each deferred REVERSE open leg once its close has FILLED.
 *
 * <p>The unified router holds a REVERSE's open leg back behind the close FILL (see
 * {@link DefaultOrderRouter#submitDeferredReverseOpen}) to eliminate the open-before-close fill-ordering
 * transient. The close FILL is observed on the IBKR EReader thread ({@code ExecutionFillTrackingService},
 * which MUST stay non-blocking), so the open submission is offloaded to THIS scheduler thread.</p>
 *
 * <p>Each tick scans the {@code PENDING_ENTRY_SUBMISSION} rows carrying a {@code deferredReverseCloseRowId}
 * and, per row, reads the linked close ROW's status (by PK — stable across the fill tracker's order-id
 * detach):</p>
 * <ul>
 *   <li>close {@code CLOSED} (filled → broker flat) → submit the open leg now;</li>
 *   <li>close {@code ACTIVE} (cancelled/expired without a fill — the fill tracker revives such a close row to
 *       {@code ACTIVE}) or any terminal-without-completion ({@code CANCELLED}/{@code REJECTED}/{@code FAILED})
 *       → the position is still live, so opening would STACK → CANCEL the deferred open;</li>
 *   <li>close still resting ({@code EXIT_SUBMITTED} / {@code VIRTUAL_EXIT_TRIGGERED}) or its row not found →
 *       wait; a later tick retries.</li>
 * </ul>
 *
 * <p>No-op whenever nothing was deferred (the scan returns empty). It does NOT gate on
 * {@code riskdesk.execution.unified-router}, so an already-deferred open still completes even if the flag is
 * flipped off after it was created. Single-threaded; the open row is mutated only here.</p>
 */
@Component
public class ReverseDeferredOpenScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReverseDeferredOpenScheduler.class);

    private final TradeExecutionRepositoryPort executionRepository;
    private final DefaultOrderRouter orderRouter;
    private final IbkrProperties ibkrProperties;
    private final ExecutionReadinessGate readinessGate;

    public ReverseDeferredOpenScheduler(TradeExecutionRepositoryPort executionRepository,
                                        DefaultOrderRouter orderRouter,
                                        IbkrProperties ibkrProperties,
                                        ExecutionReadinessGate readinessGate) {
        this.executionRepository = executionRepository;
        this.orderRouter = orderRouter;
        this.ibkrProperties = ibkrProperties;
        this.readinessGate = readinessGate;
    }

    @Scheduled(fixedDelayString = "${riskdesk.execution.reverse-deferred-open.interval-ms:3000}")
    public void submitReadyDeferredOpens() {
        if (!ibkrProperties.isEnabled()) {
            return; // can't submit anything; the deferred rows wait until IBKR is back
        }
        if (!readinessGate.isReady()) {
            // The boot gate is still CLOSED — broker position truth is not readable yet. Submitting a
            // deferred open now (e.g. for a row whose linked close reads CLOSED in the DB) would bypass the
            // exact reconciliation the gate exists to guarantee, risking a startup double-submit / stack.
            // Leave the deferred rows pending until the gate opens.
            return;
        }
        List<TradeExecutionRecord> deferred = executionRepository.findPendingDeferredReverseOpens();
        for (TradeExecutionRecord open : deferred) {
            try {
                processOne(open);
            } catch (RuntimeException e) {
                log.warn("reverse deferred-open: row {} skipped this tick — {}", open.getId(), e.getMessage());
            }
        }
    }

    private void processOne(TradeExecutionRecord open) {
        Long closeRowId = open.getDeferredReverseCloseRowId();
        if (closeRowId == null) {
            return; // the query guarantees non-null, but stay defensive
        }
        TradeExecutionRecord close = executionRepository.findById(closeRowId).orElse(null);
        if (close == null) {
            return; // can't read the close row to confirm the flatten → wait
        }
        ExecutionStatus closeStatus = close.getStatus();
        if (closeStatus == ExecutionStatus.CLOSED) {
            // Close FILLED → broker flat → safe to open the deferred leg now.
            log.info("reverse deferred-open: close row {} CLOSED — submitting deferred open row {}",
                closeRowId, open.getId());
            orderRouter.submitDeferredReverseOpen(open);
            return;
        }
        if (isCloseDidNotComplete(closeStatus)) {
            // The close did not fill (revived to ACTIVE by the fill tracker, or terminal without a fill) →
            // the position is still live → opening would STACK on it → cancel the deferred open instead.
            cancel(open, "reverse deferred-open cancelled — close row " + closeRowId + " is " + closeStatus
                + " (close did not complete); position still live, open not submitted");
            return;
        }
        // EXIT_SUBMITTED / VIRTUAL_EXIT_TRIGGERED — normally still resting → wait. BUT a close that PARTIALLY
        // filled then cancelled stays EXIT_SUBMITTED (the fill tracker only auto-transitions a ZERO-fill
        // cancel; the partial case is a separate 3c gap) while its raw orderStatus is a terminal cancel — the
        // close order is DEAD (partially flattened, NOT flat). Don't wait forever (which would freeze routing
        // for this signal/timeframe): the position remainder is still live, so cancel the deferred open.
        if (isTerminalCancelStatus(close.getOrderStatus())) {
            // Revive the stuck close row to ACTIVE for the remaining live position (reduced by the partial
            // fill) + detach the dead order id BEFORE dropping the deferred open. Otherwise the next reverse's
            // findOpenRow returns this stale EXIT_SUBMITTED row, skips submitting a close for it, and STACKS a
            // new entry on the still-live remainder.
            reviveStuckPartialCancelClose(close);
            cancel(open, "reverse deferred-open cancelled — close row " + closeRowId + " was stuck " + closeStatus
                + " with terminal orderStatus '" + close.getOrderStatus() + "' (partial-cancel); close row revived"
                + " to ACTIVE for the live remainder, open not submitted");
            return;
        }
        // genuinely resting → wait for the fill; a later tick retries.
    }

    /**
     * A close that PARTIALLY filled then died (terminal orderStatus while the lifecycle stayed
     * EXIT_SUBMITTED): revive the row to ACTIVE for the remaining live position (original qty minus the
     * partial close fill) and detach the dead close order id, so a later reverse flattens it first (capped to
     * broker truth) instead of stacking. Full partial-fill quantity reconciliation remains the 3c gap; this
     * is the minimal correct un-sticking.
     */
    private void reviveStuckPartialCancelClose(TradeExecutionRecord close) {
        BigDecimal filled = close.getFilledQuantity();
        Integer qty = close.getQuantity();
        if (qty != null && qty > 0 && filled != null && filled.signum() > 0) {
            int remaining = qty - filled.intValue();
            if (remaining > 0) {
                close.setQuantity(remaining); // position reduced by the partial close fill
            }
        }
        close.setStatus(ExecutionStatus.ACTIVE);
        close.setStatusReason("close partially filled then " + close.getOrderStatus()
            + " — remaining position revived to ACTIVE");
        close.setIbkrOrderId(null); // detach the dead close order id (replayed cancel can't re-touch it)
        close.setUpdatedAt(Instant.now());
        executionRepository.save(close);
        log.warn("reverse deferred-open: revived stuck partial-cancel close row {} to ACTIVE (remaining qty {})",
            close.getId(), close.getQuantity());
    }

    /** Close states that mean the close did NOT complete (the position is, or may still be, live). {@code
     *  ACTIVE} is the fill tracker's revive of a cancelled-without-fill close; the terminal trio covers a
     *  close that went terminal without flattening. {@code CLOSED} (filled) is handled separately above. */
    private static boolean isCloseDidNotComplete(ExecutionStatus status) {
        return status == ExecutionStatus.ACTIVE
            || status == ExecutionStatus.CANCELLED
            || status == ExecutionStatus.REJECTED
            || status == ExecutionStatus.FAILED;
    }

    /** Raw IBKR terminal-cancel order statuses — a close stuck EXIT_SUBMITTED (partial fill) carrying one of
     *  these is a dead order, not a resting one. */
    private static boolean isTerminalCancelStatus(String orderStatus) {
        if (orderStatus == null) {
            return false;
        }
        String s = orderStatus.trim();
        return s.equalsIgnoreCase("Cancelled")
            || s.equalsIgnoreCase("ApiCancelled")
            || s.equalsIgnoreCase("Inactive");
    }

    private void cancel(TradeExecutionRecord open, String reason) {
        open.setStatus(ExecutionStatus.CANCELLED);
        open.setStatusReason(reason.length() <= 256 ? reason : reason.substring(0, 256));
        open.setDeferredReverseCloseRowId(null);
        open.setUpdatedAt(Instant.now());
        executionRepository.save(open);
        log.warn("reverse deferred-open: {}", reason);
    }
}

package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerOrderLookup;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Reconciles {@code EXIT_SUBMITTED} execution rows whose close FILLED at the broker but whose
 * {@code orderStatus(Filled)} callback was never applied — so the row stays non-terminal forever,
 * shows as a phantom open position, and (via the one-position-per-instrument guards) blocks new
 * routing on that instrument. Affects EVERY strategy that places a marketable close
 * ({@code WTX_AUTO} / {@code WTXRSI_AUTO} / {@code PLAYBOOK_AUTO} / {@code QUANT_SIM_AUTO} / …).
 *
 * <p><b>Why it happens.</b> A marketable close fills <i>during</i> {@code submitEntryOrder}; the
 * {@code orderStatus(Filled)} callback arrives before the bridge has persisted the close
 * {@code ibkrOrderId} on the row, so {@code ExecutionFillTrackingService.onOrderStatus} cannot
 * {@code findByIbkrOrderId} it and drops it. No later callback re-fires → the row is stuck.
 *
 * <p><b>How this fixes it, safely.</b> Out-of-band (never on the order-placement hot path), for each
 * stale {@code EXIT_SUBMITTED} row it determines whether the close actually completed using
 * <b>broker truth</b> and, when confirmed flat, flips <b>that exact row</b> to {@code CLOSED} directly.
 * It deliberately does NOT route through the fill-tracker by {@code orderId}: reused/colliding IBKR
 * orderIds (a known reconnect hazard) make {@code findByIbkrOrderId} ambiguous, so an orderId-keyed
 * replay resolved to the wrong row (or none) and left the stuck row untouched. The WTX close-P&L settler
 * still finalizes on its next bar ("no non-terminal row" → finalize) and the active-positions publisher
 * drops the row from the live list. It never places a broker order and never changes how strategies
 * submit/close.
 *
 * <p><b>Authoritative signal = position flatness, not the order lookup.</b> Legacy bridges submit the
 * close under the SAME {@code orderRef} (= {@code executionKey}) as the entry, so a by-{@code orderRef}
 * status is ambiguous ("close Filled" vs "close Cancelled while the entry is Filled"). Confirmed-flat
 * is unambiguous: if IBKR holds no position for the instrument, the close is done. The reconcile is
 * therefore deliberately conservative:</p>
 * <ul>
 *   <li>a live order still working under the ref → skip (the close is genuinely resting);</li>
 *   <li>else, instrument <b>confirmed flat</b> → replay {@code Filled} → row {@code CLOSED};</li>
 *   <li>anything uncertain (lookup UNAVAILABLE, portfolio unreadable, a position still open) → skip —
 *       it never marks a row CLOSED while a real position could exist.</li>
 * </ul>
 *
 * <p><b>ACTIVE phantoms.</b> It also closes rows the app believes {@code ACTIVE} but IBKR holds no
 * position for (a missed close fill, or an external close) so the WTX position reconciler can resync the
 * virtual state to FLAT. This path is <b>debounced</b> ({@code active-phantom-confirm-seconds}): it only
 * acts after IBKR has been confirmed flat for that row continuously across the window, so a transient
 * empty snapshot can never close a real open position (which would leave it unmanaged). Gated by
 * {@code reconcile-active-phantoms} (default on).</p>
 */
@Component
public class StaleCloseReconciler {

    private static final Logger log = LoggerFactory.getLogger(StaleCloseReconciler.class);

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrPortfolioService ibkrPortfolioService;
    private final ExecutionReadinessGate readinessGate;
    private final IbkrProperties ibkrProperties;
    private final boolean enabled;
    private final boolean reconcileActivePhantoms;
    private final Duration grace;
    private final Duration activeFlatConfirm;

    /** One-shot boot replay — fires once as soon as broker truth is readable. */
    private volatile boolean bootReplayDone = false;

    /**
     * Per-ACTIVE-row "first confirmed flat at" — debounce so a transient empty portfolio snapshot can
     * never close a real open position (no resident stop, no force-close). An ACTIVE row is only closed
     * once IBKR has been confirmed flat for it continuously for {@link #activeFlatConfirm}.
     */
    private final java.util.Map<Long, Instant> activeFlatSince = new ConcurrentHashMap<>();

    public StaleCloseReconciler(IbkrOrderService ibkrOrderService,
                                    TradeExecutionRepositoryPort executionRepository,
                                    IbkrPortfolioService ibkrPortfolioService,
                                    ExecutionReadinessGate readinessGate,
                                    IbkrProperties ibkrProperties,
                                    @Value("${riskdesk.execution.close-reconcile.enabled:true}") boolean enabled,
                                    @Value("${riskdesk.execution.close-reconcile.reconcile-active-phantoms:true}") boolean reconcileActivePhantoms,
                                    @Value("${riskdesk.execution.close-reconcile.grace-seconds:90}") long graceSeconds,
                                    @Value("${riskdesk.execution.close-reconcile.active-phantom-confirm-seconds:120}") long activeConfirmSeconds) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrPortfolioService = ibkrPortfolioService;
        this.readinessGate = readinessGate;
        this.ibkrProperties = ibkrProperties;
        this.enabled = enabled;
        this.reconcileActivePhantoms = reconcileActivePhantoms;
        this.grace = Duration.ofSeconds(Math.max(0, graceSeconds));
        this.activeFlatConfirm = Duration.ofSeconds(Math.max(0, activeConfirmSeconds));
    }

    @Scheduled(fixedDelayString = "${riskdesk.execution.close-reconcile.interval-ms:60000}",
               initialDelayString = "${riskdesk.execution.close-reconcile.initial-delay-ms:90000}")
    public void reconcileStaleCloses() {
        if (!enabled || !ibkrProperties.isEnabled()) {
            return;
        }
        List<TradeExecutionRecord> rows = executionRepository.findAllActive();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (TradeExecutionRecord row : rows) {
            try {
                ExecutionStatus st = row.getStatus();
                if (st == ExecutionStatus.EXIT_SUBMITTED) {
                    reconcileStuckExit(row, now);
                } else if (reconcileActivePhantoms && st == ExecutionStatus.ACTIVE) {
                    reconcileActivePhantom(row, now);
                }
            } catch (RuntimeException e) {
                log.debug("close-reconcile: row {} skipped — {}", row.getId(), e.toString());
            }
        }
        // Drop debounce state for rows that are no longer ACTIVE in the live set (closed, gone, …).
        Set<Long> activeIds = rows.stream()
            .filter(r -> r.getStatus() == ExecutionStatus.ACTIVE)
            .map(TradeExecutionRecord::getId)
            .collect(Collectors.toSet());
        activeFlatSince.keySet().retainAll(activeIds);
    }

    /** One-shot boot replay so a restart that stranded an EXIT_SUBMITTED row unblocks within seconds. */
    @Scheduled(fixedDelayString = "${riskdesk.execution.close-reconcile.boot-replay-retry-ms:5000}")
    void bootReplayWhenReady() {
        if (bootReplayDone || !readinessGate.isReady()) {
            return;
        }
        bootReplayDone = true;
        reconcileStaleCloses();
    }

    /** A stuck EXIT_SUBMITTED close whose fill callback was lost → replay it once broker-confirmed flat. */
    private void reconcileStuckExit(TradeExecutionRecord row, Instant now) {
        if (tooFresh(row, now)) {
            return; // let the normal fill callback resolve it first
        }
        if (!confirmedFlatAndNotWorking(row)) {
            return;
        }
        // Flip THIS row directly — do NOT route through fill-tracking by orderId. Reused/colliding IBKR
        // orderIds (a known reconnect hazard) make findByIbkrOrderId ambiguous, so an orderId-keyed replay
        // resolved to the wrong row (or none) and left the stuck row untouched. We hold the exact row from
        // findAllActive, and confirmed-flat means the close completed → close it. The WTX close-P&L settler
        // finalizes on its next bar ("no non-terminal row" → finalize); the active-positions publisher
        // drops it from the live list.
        log.warn("close-reconcile: closing stuck EXIT_SUBMITTED execution={} instrument={} src={} "
                + "(IBKR flat → close completed)",
            row.getId(), row.getInstrument(), row.getTriggerSource());
        markClosed(row, "Reconciled: IBKR confirmed flat — stuck close completed");
    }

    /**
     * An ACTIVE row the app believes is open but IBKR holds no position for → a phantom (the close fill
     * was missed, or the position was closed externally). Closes it so the WTX position reconciler can
     * resync the virtual state to FLAT. <b>Debounced</b>: only acts after IBKR has been confirmed flat
     * for this row continuously for {@link #activeFlatConfirm} — closing a real open position on a single
     * transient empty snapshot would leave it unmanaged (no stop, no force-close), so this is deliberately
     * slower and more cautious than the EXIT path.
     */
    private void reconcileActivePhantom(TradeExecutionRecord row, Instant now) {
        if (tooFresh(row, now) || !confirmedFlatAndNotWorking(row)) {
            activeFlatSince.remove(row.getId()); // not (still) a confirmed phantom → reset the debounce
            return;
        }
        Instant since = activeFlatSince.computeIfAbsent(row.getId(), k -> now);
        if (Duration.between(since, now).compareTo(activeFlatConfirm) < 0) {
            return; // confirmed flat, but not for long enough yet
        }
        activeFlatSince.remove(row.getId());
        log.warn("close-reconcile: closing ACTIVE phantom execution={} instrument={} src={} entry={} "
                + "(IBKR confirmed flat for ≥{}s — position gone)",
            row.getId(), row.getInstrument(), row.getTriggerSource(), row.getNormalizedEntryPrice(),
            activeFlatConfirm.toSeconds());
        markClosed(row, "Reconciled: IBKR confirmed flat — phantom ACTIVE closed (missed close fill / external close)");
    }

    /** Flip a confirmed-flat non-terminal row to CLOSED directly (orderId-collision-proof). */
    private void markClosed(TradeExecutionRecord row, String reason) {
        Instant ts = Instant.now();
        row.setStatus(ExecutionStatus.CLOSED);
        row.setStatusReason(reason);
        if (row.getClosedAt() == null) {
            row.setClosedAt(ts);
        }
        row.setUpdatedAt(ts);
        executionRepository.save(row);
    }

    private boolean tooFresh(TradeExecutionRecord row, Instant now) {
        Instant lastTouched = referenceTimestamp(row);
        return lastTouched != null && Duration.between(lastTouched, now).compareTo(grace) < 0;
    }

    /**
     * True only when, for this row's instrument, IBKR is reachable, no order is still working under the
     * row's {@code orderRef}, AND the position is confirmed flat. Anything uncertain (lookup UNAVAILABLE,
     * an order still live, portfolio unreadable, a position still open) returns false — the reconciler
     * never acts on a guess.
     */
    private boolean confirmedFlatAndNotWorking(TradeExecutionRecord row) {
        if (row.getExecutionKey() == null || row.getExecutionKey().isBlank()) {
            return false;
        }
        BrokerOrderLookup lookup;
        try {
            lookup = ibkrOrderService.findOrder(row.getBrokerAccountId(), row.getExecutionKey());
        } catch (RuntimeException e) {
            return false; // can't confirm — leave it
        }
        if (lookup.isUnavailable()) {
            return false; // never act on an outage
        }
        if (lookup.isFound() && isLive(lookup.order().status())) {
            return false; // an order is genuinely still resting at the broker
        }
        return isInstrumentFlat(row.getInstrument(), row.getBrokerAccountId());
    }

    /**
     * True only when IBKR is connected and holds no nonzero leg for the instrument's symbol. Mirrors
     * the confirmed-flat definition used across the execution stack. Returns false when the snapshot is
     * unavailable — "can't read positions" must never be treated as flat.
     */
    private boolean isInstrumentFlat(String instrument, String brokerAccountId) {
        if (instrument == null) return false;
        IbkrPortfolioSnapshot snapshot;
        try {
            snapshot = ibkrPortfolioService.getPortfolio(brokerAccountId);
        } catch (RuntimeException e) {
            return false;
        }
        if (snapshot == null || !snapshot.connected() || snapshot.positions() == null) {
            return false;
        }
        String account = effectiveAccount(brokerAccountId);
        String symbol = ibkrSymbol(instrument);
        for (IbkrPositionView pos : snapshot.positions()) {
            if (pos == null || pos.position() == null) continue;
            if (account != null && pos.accountId() != null && !account.equals(pos.accountId())) {
                continue;
            }
            if (matchesSymbol(pos.contractDesc(), symbol) && pos.position().signum() != 0) {
                return false; // a live leg → not flat
            }
        }
        return true;
    }

    /**
     * Filter the flat-check to the row's account only when it is a real IBKR account (live {@code U…} /
     * paper {@code DU…}). Bridge placeholders ({@code quant-sim-default}, {@code wtxrsi-default},
     * {@code __default__}, blank, …) mean "no specific account configured" → no filter (any account's
     * matching leg counts as a live position — conservative, errs toward NOT reconciling).
     */
    private static String effectiveAccount(String brokerAccountId) {
        if (brokerAccountId == null || brokerAccountId.isBlank()) return null;
        boolean realAccount = brokerAccountId.startsWith("DU") || brokerAccountId.startsWith("U");
        return realAccount ? brokerAccountId : null;
    }

    private static String ibkrSymbol(String instrument) {
        return "E6".equals(instrument) ? "6E" : instrument;
    }

    private static boolean matchesSymbol(String contractDesc, String symbol) {
        if (contractDesc == null || symbol == null) return false;
        return contractDesc.toUpperCase(Locale.ROOT).trim().startsWith(symbol.toUpperCase(Locale.ROOT));
    }

    private static Instant referenceTimestamp(TradeExecutionRecord row) {
        if (row.getExitSubmittedAt() != null) return row.getExitSubmittedAt();
        if (row.getUpdatedAt() != null) return row.getUpdatedAt();
        return row.getCreatedAt();
    }

    private static boolean isLive(String status) {
        if (status == null) return false;
        String s = status.trim().toLowerCase(Locale.ROOT);
        return s.equals("submitted") || s.equals("presubmitted") || s.equals("pendingsubmit")
            || s.equals("apipending") || s.equals("pendingcancel") || s.equals("partiallyfilled");
    }
}

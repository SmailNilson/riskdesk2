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
 *
 * <p><b>Zombie entries.</b> It also cancels {@code ENTRY_SUBMITTED} rows whose order never reached the
 * exchange — a {@code PendingSubmit} that was never transmitted (gateway disconnected at submit, …) or a
 * broker order that's simply gone — while IBKR holds no position. These freeze a reversal strategy (every
 * new signal hits {@code SKIPPED_ENTRY_IN_FLIGHT}) AND keep its virtual state desynced from a flat broker.
 * It only cancels when the broker order is NOT genuinely working ({@code Submitted}/{@code PreSubmitted}/
 * {@code PartiallyFilled} are left alone — those are real resting limits that may still fill) and IBKR is
 * confirmed flat, debounced like the ACTIVE path. Gated by {@code reconcile-stuck-entries} (default on).</p>
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
    private final boolean reconcileStuckEntries;
    private final Duration grace;
    private final Duration flatConfirm;

    /** One-shot boot replay — fires once as soon as broker truth is readable. */
    private volatile boolean bootReplayDone = false;

    /**
     * Per-row "first confirmed reconcilable at" — debounce for the destructive paths (close an ACTIVE
     * phantom, cancel a zombie entry), so a transient empty portfolio snapshot can never act on a real
     * open position / a real resting entry. The row is only acted on once IBKR has been confirmed
     * reconcilable for it continuously for {@link #flatConfirm}.
     */
    private final java.util.Map<Long, Instant> flatConfirmSince = new ConcurrentHashMap<>();

    public StaleCloseReconciler(IbkrOrderService ibkrOrderService,
                                    TradeExecutionRepositoryPort executionRepository,
                                    IbkrPortfolioService ibkrPortfolioService,
                                    ExecutionReadinessGate readinessGate,
                                    IbkrProperties ibkrProperties,
                                    @Value("${riskdesk.execution.close-reconcile.enabled:true}") boolean enabled,
                                    @Value("${riskdesk.execution.close-reconcile.reconcile-active-phantoms:true}") boolean reconcileActivePhantoms,
                                    @Value("${riskdesk.execution.close-reconcile.reconcile-stuck-entries:true}") boolean reconcileStuckEntries,
                                    @Value("${riskdesk.execution.close-reconcile.grace-seconds:90}") long graceSeconds,
                                    @Value("${riskdesk.execution.close-reconcile.confirm-seconds:120}") long confirmSeconds) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrPortfolioService = ibkrPortfolioService;
        this.readinessGate = readinessGate;
        this.ibkrProperties = ibkrProperties;
        this.enabled = enabled;
        this.reconcileActivePhantoms = reconcileActivePhantoms;
        this.reconcileStuckEntries = reconcileStuckEntries;
        this.grace = Duration.ofSeconds(Math.max(0, graceSeconds));
        this.flatConfirm = Duration.ofSeconds(Math.max(0, confirmSeconds));
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
                } else if (reconcileStuckEntries && st == ExecutionStatus.ENTRY_SUBMITTED) {
                    reconcileZombieEntry(row, now);
                }
            } catch (RuntimeException e) {
                log.debug("close-reconcile: row {} skipped — {}", row.getId(), e.toString());
            }
        }
        // Drop debounce state for rows no longer in a debounced status in the live set (acted on, gone, …).
        Set<Long> debouncedIds = rows.stream()
            .filter(r -> r.getStatus() == ExecutionStatus.ACTIVE || r.getStatus() == ExecutionStatus.ENTRY_SUBMITTED)
            .map(TradeExecutionRecord::getId)
            .collect(Collectors.toSet());
        flatConfirmSince.keySet().retainAll(debouncedIds);
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
     * for this row continuously for {@link #flatConfirm} — closing a real open position on a single
     * transient empty snapshot would leave it unmanaged (no stop, no force-close), so this is deliberately
     * slower and more cautious than the EXIT path.
     */
    private void reconcileActivePhantom(TradeExecutionRecord row, Instant now) {
        if (tooFresh(row, now) || !confirmedFlatAndNotWorking(row)) {
            flatConfirmSince.remove(row.getId()); // not (still) a confirmed phantom → reset the debounce
            return;
        }
        if (!debounceElapsed(row, now)) {
            return; // confirmed flat, but not for long enough yet
        }
        log.warn("close-reconcile: closing ACTIVE phantom execution={} instrument={} src={} entry={} "
                + "(IBKR confirmed flat for ≥{}s — position gone)",
            row.getId(), row.getInstrument(), row.getTriggerSource(), row.getNormalizedEntryPrice(),
            flatConfirm.toSeconds());
        markClosed(row, "Reconciled: IBKR confirmed flat — phantom ACTIVE closed (missed close fill / external close)");
    }

    /**
     * An ENTRY_SUBMITTED row whose order never made it onto the exchange ({@code PendingSubmit} never
     * transmitted, or the broker order is gone) while IBKR is flat → a zombie entry that freezes a reversal
     * strategy ({@code SKIPPED_ENTRY_IN_FLIGHT}) and desyncs its virtual state. Cancels it so the strategy
     * resyncs to FLAT. Leaves a genuinely-working resting limit ({@code Submitted}/{@code PreSubmitted}/
     * {@code PartiallyFilled}) and a {@code Filled} order untouched. <b>Debounced</b> + grace-protected, so
     * a freshly-submitted entry (briefly {@code PendingSubmit}) is never cancelled.
     */
    private void reconcileZombieEntry(TradeExecutionRecord row, Instant now) {
        if (tooFresh(row, now) || !confirmedFlatAndEntryDead(row)) {
            flatConfirmSince.remove(row.getId());
            return;
        }
        if (!debounceElapsed(row, now)) {
            return;
        }
        log.warn("close-reconcile: cancelling zombie ENTRY_SUBMITTED execution={} instrument={} src={} "
                + "(IBKR flat ≥{}s, order never reached the exchange)",
            row.getId(), row.getInstrument(), row.getTriggerSource(), flatConfirm.toSeconds());
        Instant ts = Instant.now();
        row.setStatus(ExecutionStatus.CANCELLED);
        row.setStatusReason("Reconciled: IBKR confirmed flat, entry never transmitted/filled — phantom entry cancelled");
        row.setUpdatedAt(ts);
        executionRepository.save(row);
    }

    /** Debounce gate shared by the destructive paths: confirmed reconcilable continuously for {@link #flatConfirm}. */
    private boolean debounceElapsed(TradeExecutionRecord row, Instant now) {
        Instant since = flatConfirmSince.computeIfAbsent(row.getId(), k -> now);
        if (Duration.between(since, now).compareTo(flatConfirm) < 0) {
            return false;
        }
        flatConfirmSince.remove(row.getId());
        return true;
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
     * True only when the entry's order is NOT genuinely working at the exchange and IBKR is confirmed
     * flat — i.e. the entry is a zombie safe to cancel. A {@code Submitted}/{@code PreSubmitted}/
     * {@code PartiallyFilled} order is a real resting limit (may still fill) → false; a {@code Filled}
     * order means the entry actually filled (a missed entry-fill, not a zombie) → false; UNAVAILABLE →
     * false. Only {@code NOT_FOUND} (gone) or a non-working status like {@code PendingSubmit} (never
     * transmitted) qualifies, and only when the position is confirmed flat.
     */
    private boolean confirmedFlatAndEntryDead(TradeExecutionRecord row) {
        if (row.getExecutionKey() == null || row.getExecutionKey().isBlank()) {
            return false;
        }
        BrokerOrderLookup lookup;
        try {
            lookup = ibkrOrderService.findOrder(row.getBrokerAccountId(), row.getExecutionKey());
        } catch (RuntimeException e) {
            return false;
        }
        if (lookup.isUnavailable()) {
            return false; // never act on an outage
        }
        if (lookup.isFound()) {
            String status = lookup.order().status();
            if (isGenuinelyWorking(status) || isFilled(status)) {
                return false; // a real resting limit, or it actually filled → not a zombie
            }
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

    /**
     * Genuinely working at the exchange — a real resting limit that may still fill. Deliberately EXCLUDES
     * {@code PendingSubmit} (never transmitted to the exchange) and {@code ApiPending}: those are pending
     * client-side, not live in the book, so a long-stuck one on a flat account is a zombie, not a resting
     * entry.
     */
    private static boolean isGenuinelyWorking(String status) {
        if (status == null) return false;
        String s = status.trim().toLowerCase(Locale.ROOT);
        return s.equals("submitted") || s.equals("presubmitted") || s.equals("partiallyfilled");
    }

    private static boolean isFilled(String status) {
        return status != null && status.trim().equalsIgnoreCase("filled");
    }
}

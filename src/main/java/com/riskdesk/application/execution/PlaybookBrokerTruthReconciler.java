package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerOrderLookup;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.notification.event.ExecutionReconciledEvent;
import com.riskdesk.domain.notification.port.NotificationPort;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Broker-truth-anchored reconciliation for live {@link ExecutionTriggerSource#PLAYBOOK_AUTO} rows
 * whose app status has DRIFTED from what IBKR actually holds.
 *
 * <p><b>Why this exists.</b> {@code PlaybookAutomationService.routeLive} submits only the entry order
 * and stores the SL/TP as virtual levels; the SL is enforced app-side by {@link PlaybookPositionReconciler}
 * — but only on rows the app knows are {@code ACTIVE}. When a broker callback is missed (disconnect,
 * restart, the {@code orderStatus(Filled)} drop that is root cause R2) the app state diverges and the
 * existing watchers all fall through the cracks: {@link PlaybookEntryInvalidationWatcher} only cancels
 * <em>unfilled</em> entries, {@code PlaybookPositionReconciler} only scans {@code ACTIVE}, and
 * {@code StaleCloseReconciler} only acts when IBKR is <em>confirmed flat</em>. A position IBKR actually
 * holds, on a row stuck in {@code ENTRY_SUBMITTED}, is therefore left unprotected. This reconciler closes
 * that gap by reading broker truth and realigning the app row — at which point the SL reconciler protects
 * it again.
 *
 * <p><b>P1 — stale-entry recovery (state only).</b> Scans {@code ENTRY_SUBMITTED} rows and looks each up
 * by {@code executionKey} (= IBKR {@code orderRef}), live orders first then completed:
 * <ul>
 *   <li>order live → leave it (genuinely resting);</li>
 *   <li>order {@code Filled} → mark the row {@code ACTIVE} (missed fill callback) so the SL reconciler
 *       takes over;</li>
 *   <li>order {@code Cancelled}/{@code Inactive} → mark the row {@code CANCELLED};</li>
 *   <li>order in neither set (gone) AND IBKR flat for the instrument → mark {@code CANCELLED}.</li>
 * </ul>
 * It places NO broker order — it only writes the local row, fully within the "reconcilers fix state, never
 * trade" rule. Mirrors {@code WtxStaleEntryReconciler}.
 *
 * <p><b>P2 — orphan-order cancel (the one bounded broker action).</b> Scans recently-terminal
 * {@code CANCELLED} rows that still carry an {@code ibkrOrderId}. If the order is still FOUND working at
 * the broker, the app's cancel never really landed (or the order resurfaced) — so it re-issues the cancel
 * against THAT order. This only cancels an order we can map to our own already-cancelled row; it never
 * flattens an unknown position. If the order is FOUND {@code Filled} (the cancel raced a fill → an
 * unprotected live position the app abandoned), it does NOT act — that recovery is out of scope here — but
 * raises a loud divergence alarm for manual review.
 *
 * <p><b>Safety.</b> Never acts on a {@link BrokerOrderLookup#isUnavailable() UNAVAILABLE} lookup (gateway
 * down ≠ order gone). Honours a grace window so the normal ack/fill flow resolves first. Permanent
 * order-id semantics are handled upstream by the fill tracker (permId-first); here the {@code orderRef}
 * lookup is the durable key. Every correction fires the R7 divergence alarm. Gated by
 * {@code riskdesk.playbook.broker-reconcile.enabled} (default true; disable requires a restart with the
 * property set false) and by IBKR being enabled. A one-shot boot replay runs P1 as soon as broker truth
 * is readable so a restart unblocks within seconds.
 */
@Component
@ConditionalOnProperty(name = "riskdesk.playbook.broker-reconcile.enabled", havingValue = "true",
        matchIfMissing = true)
public class PlaybookBrokerTruthReconciler {

    private static final Logger log = LoggerFactory.getLogger(PlaybookBrokerTruthReconciler.class);

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrProperties ibkrProperties;
    private final IbkrPortfolioService ibkrPortfolioService;
    private final ExecutionReadinessGate readinessGate;
    private final NotificationPort notificationPort;

    /** Don't touch a row younger than this — let the normal ack/fill/cancel flow land first. */
    private final Duration grace;
    /** P2 only re-checks terminal rows updated within this window — older cancels are long settled. */
    private final Duration orphanCancelMaxAge;

    /** The one-shot boot replay fires exactly once, as soon as broker truth is readable. */
    private volatile boolean bootReplayDone = false;

    public PlaybookBrokerTruthReconciler(
            IbkrOrderService ibkrOrderService,
            TradeExecutionRepositoryPort executionRepository,
            IbkrProperties ibkrProperties,
            IbkrPortfolioService ibkrPortfolioService,
            ExecutionReadinessGate readinessGate,
            NotificationPort notificationPort,
            @Value("${riskdesk.playbook.broker-reconcile.grace-seconds:120}") long graceSeconds,
            @Value("${riskdesk.playbook.broker-reconcile.orphan-cancel-max-age-minutes:30}") long orphanMaxAgeMinutes) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.ibkrPortfolioService = ibkrPortfolioService;
        this.readinessGate = readinessGate;
        this.notificationPort = notificationPort;
        this.grace = Duration.ofSeconds(Math.max(0, graceSeconds));
        this.orphanCancelMaxAge = Duration.ofMinutes(Math.max(1, orphanMaxAgeMinutes));
    }

    // ─── P1: stale-entry recovery (state only) ──────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${riskdesk.playbook.broker-reconcile.stale-entry-interval-ms:60000}",
            initialDelayString = "${riskdesk.playbook.broker-reconcile.initial-delay-ms:60000}")
    public void reconcileStaleEntries() {
        if (!ibkrProperties.isEnabled()) {
            return;
        }
        List<TradeExecutionRecord> rows = executionRepository.findByTriggerSourceAndStatus(
                ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.ENTRY_SUBMITTED);
        if (rows.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (TradeExecutionRecord row : rows) {
            try {
                reconcileStaleEntry(row, now);
            } catch (RuntimeException e) {
                log.debug("PLAYBOOK broker-reconcile (entry): row {} skipped — {}", row.getId(), e.getMessage());
            }
        }
    }

    /**
     * One-shot boot replay. Fires once, as soon as the readiness gate reports broker truth is readable,
     * then never again — so a restart that stranded an {@code ENTRY_SUBMITTED} row reconciles within
     * seconds rather than waiting for the first steady-state interval.
     */
    @Scheduled(fixedDelayString = "${riskdesk.playbook.broker-reconcile.boot-replay-retry-ms:5000}")
    void bootReplayWhenReady() {
        if (bootReplayDone || !readinessGate.isReady()) {
            return; // gate still closed → broker truth not readable yet; retry next tick
        }
        bootReplayDone = true; // latch on the first ready tick so this never runs twice
        if (!ibkrProperties.isEnabled()) {
            return; // the gate opened only because IBKR is disabled — nothing to replay
        }
        log.info("PLAYBOOK broker-reconcile boot replay — reconciling stranded ENTRY_SUBMITTED rows against broker truth");
        reconcileStaleEntries();
    }

    private void reconcileStaleEntry(TradeExecutionRecord row, Instant now) {
        if (tooFresh(referenceTimestamp(row), now)) {
            return; // let the normal ack/fill flow resolve it first
        }
        if (isBlank(row.getExecutionKey())) {
            return; // can't look it up without the orderRef
        }
        BrokerOrderLookup lookup = safeLookup(row);
        if (lookup == null || lookup.isUnavailable()) {
            return; // can't confirm — never reconcile on an outage
        }
        if (lookup.isFound()) {
            String status = lookup.order() == null ? null : lookup.order().status();
            if (isLive(status)) {
                return; // genuinely resting — not stale
            }
            if (isFilled(status)) {
                activate(row, now); // missed fill callback → recover so the SL reconciler protects it
            } else if (isCancelled(status)) {
                markCancelled(row, "PLAYBOOK broker-reconcile: IBKR entry order " + status + " — row reconciled");
            }
            return; // unknown status → leave untouched (conservative)
        }
        // NOT_FOUND: live + completed both queried, order in neither → it is gone. Only cancel when IBKR
        // also holds no position for the instrument (proves it did not fill). A live position means the
        // fill likely aged out of completed orders → leave it (cancelling would hide a real position).
        if (isInstrumentFlat(row.getInstrument(), row.getBrokerAccountId())) {
            markCancelled(row, "PLAYBOOK broker-reconcile: no live/completed IBKR order and IBKR flat — phantom reconciled");
        }
    }

    // ─── P2: orphan-order cancel (one bounded broker action) ────────────────────────────────────

    @Scheduled(fixedDelayString = "${riskdesk.playbook.broker-reconcile.orphan-cancel-interval-ms:60000}",
            initialDelayString = "${riskdesk.playbook.broker-reconcile.orphan-cancel-initial-delay-ms:75000}")
    public void cancelOrphanOrders() {
        if (!ibkrProperties.isEnabled()) {
            return;
        }
        List<TradeExecutionRecord> rows = executionRepository.findByTriggerSourceAndStatus(
                ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.CANCELLED);
        if (rows.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (TradeExecutionRecord row : rows) {
            try {
                cancelOrphanOrder(row, now);
            } catch (RuntimeException e) {
                log.debug("PLAYBOOK broker-reconcile (orphan): row {} skipped — {}", row.getId(), e.getMessage());
            }
        }
    }

    private void cancelOrphanOrder(TradeExecutionRecord row, Instant now) {
        Integer orderId = row.getIbkrOrderId();
        if (orderId == null) {
            return; // nothing ever rested at the broker for this row
        }
        // Age a CANCELLED row from when it was last changed (= when it was cancelled), NOT from entry
        // submission: a row submitted long ago but only just cancelled is a fresh orphan candidate, and
        // ageing it from entrySubmittedAt would wrongly push it past the re-check window.
        Instant ref = lastChanged(row);
        if (tooFresh(ref, now)) {
            return; // the app's own cancel may still be propagating — let it land first
        }
        if (ref != null && Duration.between(ref, now).compareTo(orphanCancelMaxAge) > 0) {
            return; // long-settled terminal row — out of the re-check window
        }
        if (isBlank(row.getExecutionKey())) {
            return;
        }
        BrokerOrderLookup lookup = safeLookup(row);
        if (lookup == null || lookup.isUnavailable() || lookup.isNotFound()) {
            return; // gone or unconfirmable → nothing orphaned to cancel
        }
        String status = lookup.order() == null ? null : lookup.order().status();
        if (isCancelled(status)) {
            return; // broker agrees it is cancelled — consistent
        }
        if (isFilled(status)) {
            // The cancel raced a fill: the app abandoned a position IBKR actually holds. Recovering it is
            // out of scope (P3) — but this is the dangerous, money-losing divergence, so alarm loudly and
            // do NOT issue a cancel (a filled order can't be cancelled and a live position must not be hidden).
            log.error("PLAYBOOK broker-reconcile: row {} ({} {}) is CANCELLED in-app but IBKR order {} is FILLED "
                    + "— UNPROTECTED live position, manual review required",
                row.getId(), row.getInstrument(), row.getAction(), orderId);
            alarm(row, "CANCELLED", "CANCELLED",
                "IBKR order " + orderId + " FILLED while app CANCELLED — unprotected position, manual review");
            return;
        }
        if (!isLive(status)) {
            return; // unknown status → conservative, leave it
        }
        // FOUND + live: the order is still working despite the app considering it cancelled. Re-issue the
        // cancel against this exact order. The row stays CANCELLED (already terminal); the broker's Cancelled
        // callback is idempotent against it.
        try {
            String feedback = ibkrOrderService.cancelOrder(orderId);
            log.warn("PLAYBOOK broker-reconcile: re-cancelled orphan IBKR order {} for CANCELLED row {} ({} {}) — broker: {}",
                orderId, row.getId(), row.getInstrument(), row.getAction(), feedback);
            alarm(row, "CANCELLED", "CANCELLED",
                "orphan IBKR order " + orderId + " still working on a CANCELLED row — re-cancel issued (" + feedback + ")");
        } catch (RuntimeException e) {
            // Most likely the order just filled/cancelled between lookup and cancel (e.g. IBKR 161). Don't
            // rewrite the row; a later tick (or the Filled branch above) re-evaluates against fresh truth.
            log.warn("PLAYBOOK broker-reconcile: cancel of orphan order {} for row {} failed — {}",
                orderId, row.getId(), e.getMessage());
        }
    }

    // ─── shared helpers ─────────────────────────────────────────────────────────────────────────

    private BrokerOrderLookup safeLookup(TradeExecutionRecord row) {
        try {
            return ibkrOrderService.findOrder(row.getBrokerAccountId(), row.getExecutionKey());
        } catch (RuntimeException e) {
            log.debug("PLAYBOOK broker-reconcile: lookup failed for row {} (key={}) — {}",
                row.getId(), row.getExecutionKey(), e.getMessage());
            return null;
        }
    }

    private void activate(TradeExecutionRecord row, Instant now) {
        row.setStatus(ExecutionStatus.ACTIVE);
        row.setStatusReason("PLAYBOOK broker-reconcile: IBKR entry order Filled — missed fill callback, row activated");
        if (row.getEntryFilledAt() == null) {
            row.setEntryFilledAt(now);
        }
        row.setUpdatedAt(Instant.now());
        executionRepository.save(row);
        log.warn("PLAYBOOK [{} {}] stale ENTRY_SUBMITTED row {} reconciled to ACTIVE — IBKR reports Filled (SL now enforced)",
            row.getInstrument(), row.getTimeframe(), row.getId());
        alarm(row, "ENTRY_SUBMITTED", "ACTIVE",
            "IBKR entry order Filled but fill callback missed — row activated so the app-side SL is enforced");
    }

    private void markCancelled(TradeExecutionRecord row, String reason) {
        row.setStatus(ExecutionStatus.CANCELLED);
        row.setStatusReason(reason);
        row.setUpdatedAt(Instant.now());
        executionRepository.save(row);
        log.warn("PLAYBOOK [{} {}] stale ENTRY_SUBMITTED row {} reconciled to CANCELLED — {}",
            row.getInstrument(), row.getTimeframe(), row.getId(), reason);
        alarm(row, "ENTRY_SUBMITTED", "CANCELLED", reason);
    }

    private void alarm(TradeExecutionRecord row, String fromStatus, String toStatus, String reason) {
        if (notificationPort == null) {
            return;
        }
        try {
            notificationPort.sendExecutionReconciled(new ExecutionReconciledEvent(
                row.getInstrument(),
                ExecutionTriggerSource.PLAYBOOK_AUTO.name(),
                fromStatus, toStatus, reason, Instant.now()));
        } catch (RuntimeException e) {
            log.debug("PLAYBOOK broker-reconcile: divergence alarm failed for row {} — {}", row.getId(), e.getMessage());
        }
    }

    /**
     * True only when IBKR is connected and holds no nonzero leg for the instrument's symbol in the row's
     * account. Mirrors the confirmed-flat definition used by the other reconcilers. Returns false when the
     * snapshot is unavailable — never treat "can't read positions" as flat.
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
                return false; // a live leg in this account → not flat
            }
        }
        return true;
    }

    private static String effectiveAccount(String brokerAccountId) {
        if (isBlank(brokerAccountId)
                || "wtx-default".equals(brokerAccountId)
                || DefaultOrderRouter.DEFAULT_BROKER_ACCOUNT.equals(brokerAccountId)) {
            return null; // no specific account → don't filter the flat-check
        }
        return brokerAccountId;
    }

    /** IBKR ticker for a Playbook instrument name. Differs only for E6 (IBKR symbol "6E"). */
    private static String ibkrSymbol(String instrument) {
        return "E6".equals(instrument) ? "6E" : instrument;
    }

    private static boolean matchesSymbol(String contractDesc, String symbol) {
        if (contractDesc == null || symbol == null) return false;
        return contractDesc.toUpperCase(Locale.ROOT).trim().startsWith(symbol.toUpperCase(Locale.ROOT));
    }

    private boolean tooFresh(Instant ref, Instant now) {
        return ref != null && Duration.between(ref, now).compareTo(grace) < 0;
    }

    /** Best "resting since" instant for ageing a pending entry (P1): how long it has rested at the broker. */
    private static Instant referenceTimestamp(TradeExecutionRecord row) {
        if (row.getEntrySubmittedAt() != null) return row.getEntrySubmittedAt();
        if (row.getUpdatedAt() != null) return row.getUpdatedAt();
        return row.getCreatedAt();
    }

    /** "Last changed" instant for ageing a terminal row (P2): when it was cancelled, not when submitted. */
    private static Instant lastChanged(TradeExecutionRecord row) {
        if (row.getUpdatedAt() != null) return row.getUpdatedAt();
        return row.getCreatedAt();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isLive(String status) {
        return matches(status, "submitted", "presubmitted", "pendingsubmit", "apipending",
                "pendingcancel", "partiallyfilled");
    }

    private static boolean isCancelled(String status) {
        return matches(status, "cancelled", "apicancelled", "inactive");
    }

    private static boolean isFilled(String status) {
        return matches(status, "filled");
    }

    private static boolean matches(String status, String... candidates) {
        if (status == null) return false;
        String s = status.trim().toLowerCase(Locale.ROOT);
        for (String c : candidates) {
            if (s.equals(c)) return true;
        }
        return false;
    }
}

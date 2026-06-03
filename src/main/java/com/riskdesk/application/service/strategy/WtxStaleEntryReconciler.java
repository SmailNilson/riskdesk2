package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.BrokerOrderLookup;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.execution.DefaultOrderRouter;
import com.riskdesk.application.execution.ExecutionReadinessGate;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
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
 * Reconciles WTX {@code ENTRY_SUBMITTED} rows that the live fill-tracker never resolved against
 * the broker's truth.
 *
 * <p><b>Why this exists.</b> WTX entries are {@code LMT} {@code DAY} limit orders. The fill tracker
 * only flips a row {@code ENTRY_SUBMITTED → ACTIVE/CANCELLED} when it receives the matching
 * {@code orderStatus} callback. If that callback is missed — a disconnect, a restart, or the DAY
 * order expiring while the app is down — the row stays {@code ENTRY_SUBMITTED} forever. The
 * confirmed-flat reconcile in {@link WtxExecutionBridge} then reads it as an entry still "in flight"
 * and returns {@code SKIPPED_ENTRY_IN_FLIGHT} on every new signal, <b>freezing the strategy</b>.
 *
 * <p><b>What it does.</b> On a schedule it looks up each stale {@code ENTRY_SUBMITTED} row by its
 * {@code executionKey} (which is the IBKR {@code orderRef}) — live orders first, then completed
 * orders — and reconciles deterministically:
 * <ul>
 *   <li>order live ({@code Submitted}/{@code PreSubmitted}/…) → leave it (genuinely resting);</li>
 *   <li>order {@code Cancelled}/{@code ApiCancelled}/{@code Inactive} → mark the row CANCELLED;</li>
 *   <li>order {@code Filled} → mark the row ACTIVE (the fill callback was missed);</li>
 *   <li>order in neither set (a confirmed query, so the order is gone — rejected at submit, never
 *       placed, or aged out) AND IBKR holds <b>no position</b> for the instrument → mark the row
 *       CANCELLED. The flat check is the safety gate: a NOT_FOUND order with a live position likely
 *       filled (its fill aged out of completed orders), so it is left untouched.</li>
 * </ul>
 * Anything uncertain (lookup UNAVAILABLE, unknown status, NOT_FOUND while a position exists or the
 * portfolio can't be read) is left untouched — the reconciler never guesses. No broker side effect:
 * it only writes the local tracking row.
 *
 * <p>The bridge routing path is unchanged and stays fast (it reads the cached position snapshot);
 * this runs out-of-band and unblocks the strategy within one interval.
 *
 * <p><b>Unified-router rows (Slice D — D3).</b> When {@code riskdesk.execution.unified-router} is on,
 * WTX entries are persisted by {@link DefaultOrderRouter} under the {@code WTX_AUTO} trigger source —
 * the exact set this reconciler scans — so this IS the per-row {@code ENTRY_SUBMITTED} boot/stale replay
 * the live flip requires. Router rows carry {@link DefaultOrderRouter#DEFAULT_BROKER_ACCOUNT} as their
 * account placeholder; {@link #effectiveAccount} treats it (like {@code "wtx-default"} / blank) as "no
 * specific account", so the NOT_FOUND flat-check is account-unscoped for them — WITHOUT this, the
 * placeholder would match no live account, every position would be filtered out, a phantom-looking
 * NOT_FOUND would be read as flat, and a row actually backed by a live position would be wrongly
 * CANCELLED (orphaning the position). A one-shot {@link #bootReplayWhenReady() boot replay} runs as soon
 * as broker truth is readable (the {@link ExecutionReadinessGate}) so a restart unblocks the strategy in
 * seconds, not after the first steady-state interval.
 */
@Component
@ConditionalOnProperty(name = "riskdesk.wtx.enabled", havingValue = "true")
public class WtxStaleEntryReconciler {

    private static final Logger log = LoggerFactory.getLogger(WtxStaleEntryReconciler.class);

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrProperties ibkrProperties;
    private final IbkrPortfolioService ibkrPortfolioService;
    private final ExecutionReadinessGate readinessGate;

    /** Don't touch a row younger than this — give the normal ack/fill flow time to land. */
    private final Duration grace;

    /** The one-shot boot replay (D3) fires exactly once, as soon as broker truth is readable. */
    private volatile boolean bootReplayDone = false;

    /**
     * Outcome of reconciling a single {@code ENTRY_SUBMITTED} row against broker truth. Lets an on-demand
     * caller (e.g. {@code WtxPositionReconciler}) react to a phantom row immediately instead of waiting for
     * the next scheduled sweep.
     */
    public enum Resolution {
        /** The order is genuinely resting at the broker — the row is correct, left untouched. */
        LEFT_RESTING,
        /** The order Filled (missed callback) — the row was flipped to ACTIVE. */
        ACTIVATED,
        /** The order is gone and IBKR is flat (or the broker reports it cancelled) — the row was CANCELLED. */
        CANCELLED,
        /** Could not confirm (too fresh, no orderRef, lookup unavailable, NOT_FOUND but a position exists). */
        UNRESOLVED
    }

    public WtxStaleEntryReconciler(
            IbkrOrderService ibkrOrderService,
            TradeExecutionRepositoryPort executionRepository,
            IbkrProperties ibkrProperties,
            IbkrPortfolioService ibkrPortfolioService,
            ExecutionReadinessGate readinessGate,
            @Value("${riskdesk.wtx.stale-entry.grace-seconds:120}") long graceSeconds) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.ibkrPortfolioService = ibkrPortfolioService;
        this.readinessGate = readinessGate;
        this.grace = Duration.ofSeconds(Math.max(0, graceSeconds));
    }

    @Scheduled(fixedDelayString = "${riskdesk.wtx.stale-entry.reconcile-interval-ms:60000}",
            initialDelayString = "${riskdesk.wtx.stale-entry.initial-delay-ms:60000}")
    public void reconcileStaleEntries() {
        if (!ibkrProperties.isEnabled()) {
            return;
        }
        List<TradeExecutionRecord> rows = executionRepository.findByTriggerSourceAndStatus(
                ExecutionTriggerSource.WTX_AUTO, ExecutionStatus.ENTRY_SUBMITTED);
        if (rows.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (TradeExecutionRecord row : rows) {
            try {
                reconcileOne(row, now);
            } catch (RuntimeException e) {
                log.debug("WTX stale-entry reconcile: row {} skipped — {}", row.getId(), e.getMessage());
            }
        }
    }

    /**
     * One-shot boot replay (Slice D — D3). Fires once, as soon as the execution readiness gate reports
     * broker position truth is readable, then never again — so a restart that stranded an
     * {@code ENTRY_SUBMITTED} row reconciles it against broker truth within seconds rather than waiting
     * for the first steady-state interval (up to a minute of a frozen strategy). The steady-state
     * {@link #reconcileStaleEntries()} schedule continues independently afterwards.
     */
    @Scheduled(fixedDelayString = "${riskdesk.wtx.stale-entry.boot-replay-retry-ms:5000}")
    void bootReplayWhenReady() {
        if (bootReplayDone || !readinessGate.isReady()) {
            return; // gate still closed → broker truth not readable yet; retry next tick
        }
        bootReplayDone = true; // latch on the first ready tick so this never runs twice
        if (!ibkrProperties.isEnabled()) {
            return; // the gate opened only because IBKR is disabled — nothing to replay
        }
        log.info("WTX boot replay — reconciling stranded ENTRY_SUBMITTED rows against broker truth");
        reconcileStaleEntries();
    }

    /**
     * Reconciles one {@code ENTRY_SUBMITTED} row against broker truth and reports what it did. Public so an
     * on-demand caller ({@code WtxPositionReconciler}) can clear a phantom row the instant it blocks a
     * same-side signal, instead of waiting up to a scheduled interval. Idempotent and side-effect-safe:
     * never reconciles on uncertainty (too fresh, no orderRef, lookup UNAVAILABLE, or NOT_FOUND while a
     * position exists).
     */
    public Resolution reconcileOne(TradeExecutionRecord row, Instant now) {
        Instant lastTouched = referenceTimestamp(row);
        if (lastTouched != null && Duration.between(lastTouched, now).compareTo(grace) < 0) {
            return Resolution.UNRESOLVED; // too fresh — let the normal ack/fill flow resolve it first
        }
        if (row.getExecutionKey() == null || row.getExecutionKey().isBlank()) {
            return Resolution.UNRESOLVED; // can't look it up without the orderRef
        }

        BrokerOrderLookup lookup;
        try {
            lookup = ibkrOrderService.findOrder(row.getBrokerAccountId(), row.getExecutionKey());
        } catch (RuntimeException e) {
            log.debug("WTX stale-entry reconcile: lookup failed for row {} (key={}) — {}",
                    row.getId(), row.getExecutionKey(), e.getMessage());
            return Resolution.UNRESOLVED; // can't confirm — leave it
        }

        // CRITICAL: never reconcile on UNAVAILABLE. The gateway couldn't be queried (disabled,
        // disconnected, no resolved account) — an empty result here is NOT evidence the order is gone.
        // Treating it as absence during an outage could cancel a filled-but-unreconciled row and hide
        // a real broker position. Only NOT_FOUND (live + completed both queried, order in neither) and
        // an explicit FOUND status drive a reconcile.
        if (lookup == null || lookup.isUnavailable()) {
            return Resolution.UNRESOLVED;
        }

        if (lookup.isFound()) {
            String status = lookup.order().status();
            if (isLive(status)) {
                return Resolution.LEFT_RESTING; // genuinely resting at the broker — not stale
            }
            if (isCancelled(status)) {
                cancel(row, "WTX stale-entry reconcile: IBKR order " + status + " — row reconciled");
                return Resolution.CANCELLED;
            }
            if (isFilled(status)) {
                activate(row, now);
                return Resolution.ACTIVATED;
            }
            return Resolution.UNRESOLVED; // unknown status → leave untouched (conservative).
        }

        // NOT_FOUND — the live AND completed sets were both queried and the order is in neither, so it
        // is gone (rejected at submit, never placed, or aged out of completed orders). Only cancel when
        // IBKR also holds NO position for the instrument: that proves the order did not fill into a
        // position. If a position exists, the order likely filled and its fill aged out of completed
        // orders — leave it (cancelling would hide a real position). If the portfolio can't be read,
        // we can't confirm flat → leave it too.
        if (isInstrumentFlat(row.getInstrument(), row.getBrokerAccountId())) {
            cancel(row, "WTX stale-entry reconcile: no live/completed IBKR order and IBKR flat — phantom row reconciled");
            return Resolution.CANCELLED;
        }
        return Resolution.UNRESOLVED;
    }

    /**
     * True only when IBKR is connected and holds <b>no nonzero leg</b> for the instrument's symbol.
     * Mirrors {@code WtxExecutionBridge}'s confirmed-flat definition (offsetting rollover legs count
     * as a live position, so they are NOT flat). Returns false when the snapshot is unavailable — we
     * must not treat "can't read positions" as flat.
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
        // Scope to the row's account: a multi-account gateway can return positions across every
        // attached account, so an unrelated account holding the same instrument must not make this
        // row's account look non-flat (which would leave the phantom row stuck forever). The
        // placeholder "wtx-default" / blank means no specific account is configured — no filter.
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
        // null / blank / the legacy "wtx-default" / the unified router's "__default__" placeholder all mean
        // "no specific account configured" → no account filter on the flat-check (any account's matching leg
        // counts as a live position). Treating the router placeholder as a real account here would filter
        // out the genuine position and wrongly CANCEL a row backed by a live position (Slice D — D3).
        if (brokerAccountId == null || brokerAccountId.isBlank()
                || "wtx-default".equals(brokerAccountId)
                || DefaultOrderRouter.DEFAULT_BROKER_ACCOUNT.equals(brokerAccountId)) {
            return null;
        }
        return brokerAccountId;
    }

    /** IBKR ticker for a WTX instrument name. Differs only for E6 (IBKR symbol "6E"). */
    private static String ibkrSymbol(String instrument) {
        return "E6".equals(instrument) ? "6E" : instrument;
    }

    private static boolean matchesSymbol(String contractDesc, String symbol) {
        if (contractDesc == null || symbol == null) return false;
        return contractDesc.toUpperCase(Locale.ROOT).trim().startsWith(symbol.toUpperCase(Locale.ROOT));
    }

    private void cancel(TradeExecutionRecord row, String reason) {
        row.setStatus(ExecutionStatus.CANCELLED);
        row.setStatusReason(reason);
        row.setUpdatedAt(Instant.now());
        executionRepository.save(row);
        log.warn("WTX [{} {}] stale ENTRY_SUBMITTED row {} reconciled to CANCELLED — {}",
                row.getInstrument(), row.getTimeframe(), row.getId(), reason);
    }

    private void activate(TradeExecutionRecord row, Instant now) {
        row.setStatus(ExecutionStatus.ACTIVE);
        row.setStatusReason("WTX stale-entry reconcile: IBKR order Filled — missed fill callback, row activated");
        if (row.getEntryFilledAt() == null) {
            row.setEntryFilledAt(now);
        }
        row.setUpdatedAt(Instant.now());
        executionRepository.save(row);
        log.warn("WTX [{} {}] stale ENTRY_SUBMITTED row {} reconciled to ACTIVE — IBKR reports Filled",
                row.getInstrument(), row.getTimeframe(), row.getId());
    }

    /** Best available "last touched" instant for ageing the row. */
    private static Instant referenceTimestamp(TradeExecutionRecord row) {
        if (row.getEntrySubmittedAt() != null) return row.getEntrySubmittedAt();
        if (row.getUpdatedAt() != null) return row.getUpdatedAt();
        return row.getCreatedAt();
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

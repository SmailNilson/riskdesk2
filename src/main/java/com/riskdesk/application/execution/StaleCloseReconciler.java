package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerOrderLookup;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.service.ExecutionFillTrackingService;
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
 * <b>broker truth</b> and, when confirmed, <b>replays the missed callback</b> through the existing
 * {@link ExecutionFillTrackingService#onOrderStatus} — the exact same transition path a live callback
 * would take, so every downstream effect (publish, WTX close-P&L settler, position reconciler) runs
 * identically. It never places a broker order and never changes how strategies submit/close.
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
 */
@Component
public class StaleCloseReconciler {

    private static final Logger log = LoggerFactory.getLogger(StaleCloseReconciler.class);

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrPortfolioService ibkrPortfolioService;
    private final ExecutionFillTrackingService fillTracking;
    private final ExecutionReadinessGate readinessGate;
    private final IbkrProperties ibkrProperties;
    private final boolean enabled;
    private final Duration grace;

    /** One-shot boot replay — fires once as soon as broker truth is readable. */
    private volatile boolean bootReplayDone = false;

    public StaleCloseReconciler(IbkrOrderService ibkrOrderService,
                                    TradeExecutionRepositoryPort executionRepository,
                                    IbkrPortfolioService ibkrPortfolioService,
                                    ExecutionFillTrackingService fillTracking,
                                    ExecutionReadinessGate readinessGate,
                                    IbkrProperties ibkrProperties,
                                    @Value("${riskdesk.execution.close-reconcile.enabled:true}") boolean enabled,
                                    @Value("${riskdesk.execution.close-reconcile.grace-seconds:90}") long graceSeconds) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrPortfolioService = ibkrPortfolioService;
        this.fillTracking = fillTracking;
        this.readinessGate = readinessGate;
        this.ibkrProperties = ibkrProperties;
        this.enabled = enabled;
        this.grace = Duration.ofSeconds(Math.max(0, graceSeconds));
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
            if (row.getStatus() != ExecutionStatus.EXIT_SUBMITTED) {
                continue;
            }
            try {
                reconcileOne(row, now);
            } catch (RuntimeException e) {
                log.debug("close-reconcile: row {} skipped — {}", row.getId(), e.toString());
            }
        }
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

    private void reconcileOne(TradeExecutionRecord row, Instant now) {
        Instant lastTouched = referenceTimestamp(row);
        if (lastTouched != null && Duration.between(lastTouched, now).compareTo(grace) < 0) {
            return; // too fresh — let the normal fill callback resolve it first
        }
        if (row.getIbkrOrderId() == null) {
            return; // no close order id → can't replay the callback safely
        }
        if (row.getExecutionKey() == null || row.getExecutionKey().isBlank()) {
            return;
        }

        // Is anything still working under this orderRef (the close resting unfilled)?
        BrokerOrderLookup lookup;
        try {
            lookup = ibkrOrderService.findOrder(row.getBrokerAccountId(), row.getExecutionKey());
        } catch (RuntimeException e) {
            return; // can't confirm — leave it
        }
        if (lookup.isUnavailable()) {
            return; // never act on an outage
        }
        if (lookup.isFound() && isLive(lookup.order().status())) {
            return; // a close (or order) is genuinely still resting at the broker
        }

        // Authoritative: nothing open under the ref AND IBKR confirmed flat → the close completed.
        if (isInstrumentFlat(row.getInstrument(), row.getBrokerAccountId())) {
            log.warn("close-reconcile: replaying missed Filled for stuck EXIT_SUBMITTED execution={} "
                    + "instrument={} src={} orderId={} (IBKR flat → close completed)",
                row.getId(), row.getInstrument(), row.getTriggerSource(), row.getIbkrOrderId());
            // Replay the missed callback through the SAME path a live fill would take. The fill tracker
            // locates the row by its (now-persisted) close orderId and applies EXIT_SUBMITTED → CLOSED,
            // publishes, and lets the WTX close-P&L settler finalize on its next bar.
            fillTracking.onOrderStatus(row.getIbkrOrderId(), "Filled", null, null, null, now);
        }
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

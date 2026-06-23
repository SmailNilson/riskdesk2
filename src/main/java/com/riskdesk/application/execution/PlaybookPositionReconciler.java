package com.riskdesk.application.execution;

import com.riskdesk.application.marketdata.LivePriceSource;
import com.riskdesk.application.quant.positions.ActivePositionsService;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * App-side enforcement of the VIRTUAL stop-loss / take-profit for <em>filled</em> live
 * {@link ExecutionTriggerSource#PLAYBOOK_AUTO} positions.
 *
 * <p><b>Why this exists.</b> {@code PlaybookAutomationService.routeLive} submits ONLY the confirmation
 * entry order to IBKR (a STOP / STOP-LIMIT) and stores the plan's SL/TP as
 * {@code virtualStopLoss}/{@code virtualTakeProfit} on the row — it never places a protective broker
 * order. Once the entry fills (status {@link ExecutionStatus#ACTIVE}) two existing watchers leave it
 * alone: {@link PlaybookEntryInvalidationWatcher} only cancels <em>unfilled</em> resting entries, and
 * {@link com.riskdesk.application.quant.positions.VirtualStopWatcher} is scoped to
 * {@code MANUAL_QUANT_PANEL} rows. The position therefore ran unprotected at the broker — when price
 * reached the stop nothing fired. This reconciler closes that gap.</p>
 *
 * <p>Each tick it scans ACTIVE Playbook rows and, when a fresh live price has crossed the row's virtual
 * SL or TP for its side, flattens the position through the very same unified-router flatten path the
 * operator's "Fermer" button uses ({@link ActivePositionsService#closePosition}). The stop wins a tie
 * (pessimistic — matches the paper-simulation convention).</p>
 *
 * <p><b>App-side only.</b> This is NOT a broker bracket order: it protects only while the backend is
 * running and connected to IBKR. A backend outage / disconnect leaves the position unprotected at the
 * broker — the residual gap that real OCO brackets would close (deferred). Gated by
 * {@code riskdesk.playbook.position-watch.enabled} (default true, read every tick as a kill-switch)
 * and by IBKR being enabled (PLAYBOOK_AUTO broker rows only exist when it is). Mirrors
 * {@link com.riskdesk.application.quant.positions.VirtualStopWatcher}: per-row isolation so one bad row
 * never aborts the sweep, a freshness-gated live quote so an irreversible auto-close never fires on a
 * stale tick, and a per-instrument price cache so a (possibly blocking) provider fetch runs once per
 * sweep.</p>
 */
@Component
public class PlaybookPositionReconciler {

    private static final Logger log = LoggerFactory.getLogger(PlaybookPositionReconciler.class);

    /** Max quote age for an auto-close decision (mirrors {@link VirtualStopWatcher}). */
    private static final long MAX_PRICE_AGE_SECONDS = 10;

    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final LivePricePort livePricePort;
    private final ActivePositionsService activePositionsService;
    private final IbkrProperties ibkrProperties;
    private final PlaybookPositionWatchProperties props;

    public PlaybookPositionReconciler(TradeExecutionRepositoryPort tradeExecutionRepository,
                                      LivePricePort livePricePort,
                                      ActivePositionsService activePositionsService,
                                      IbkrProperties ibkrProperties,
                                      PlaybookPositionWatchProperties props) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.livePricePort = livePricePort;
        this.activePositionsService = activePositionsService;
        this.ibkrProperties = ibkrProperties;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${riskdesk.playbook.position-watch.poll-ms:1500}")
    public void tick() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("playbook position-watch tick failed: {}", e.toString());
        }
    }

    /** Package-private so tests can drive a single iteration without a real scheduler. */
    void sweep() {
        // Kill-switch (read every tick), and no broker to flatten against ⇒ nothing to enforce. A
        // PLAYBOOK_AUTO broker row only exists when IBKR is enabled, so this also keeps the reconciler
        // off in paper / test envs where the trade simulator owns SL/TP resolution.
        if (!props.isEnabled() || !ibkrProperties.isEnabled()) return;
        List<TradeExecutionRecord> rows = new ArrayList<>(tradeExecutionRepository.findByTriggerSourceAndStatus(
            ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.ACTIVE));
        // Also re-drive EXIT_SUBMITTED rows whose virtual stop is STILL breached: a first auto-close can
        // rest unfilled (e.g. its marketable limit gapped through) and the row then leaves ACTIVE — so
        // without this the breached level would never be re-attempted until the slow StaleCloseReconciler.
        // closePosition is idempotent here: the router re-fires only a genuinely stuck close, else skips.
        rows.addAll(tradeExecutionRepository.findByTriggerSourceAndStatus(
            ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.EXIT_SUBMITTED));
        if (rows.isEmpty()) return;
        // Resolve each distinct instrument's live price ONCE per sweep — a (possibly blocking) provider fetch
        // must not run per row. Caches nulls too. Mirrors VirtualStopWatcher.
        Map<Instrument, BigDecimal> priceCache = new EnumMap<>(Instrument.class);
        for (TradeExecutionRecord row : rows) {
            try {
                String reason = breach(row, priceCache);
                if (reason == null) continue;
                log.info("playbook position-watch {} breached executionId={} instrument={} status={} — auto-closing (app-side)",
                    reason, row.getId(), row.getInstrument(), row.getStatus());
                activePositionsService.closePosition(row.getId(), "playbook-stop:" + reason);
            } catch (RuntimeException e) {
                // A bad row / failed close must not stop the rest of the batch or the scheduler.
                log.warn("playbook position-watch auto-close failed executionId={}: {}", row.getId(), e.toString());
            }
        }
    }

    /**
     * "SL" / "TP" when the live price has crossed that virtual level for the row's side, else null.
     * The stop wins a tie (pessimistic — matches the simulation convention).
     */
    private String breach(TradeExecutionRecord row, Map<Instrument, BigDecimal> priceCache) {
        BigDecimal sl = row.getVirtualStopLoss();
        BigDecimal tp = row.getVirtualTakeProfit();
        if (sl == null && tp == null) return null;
        Instrument instrument = parseInstrument(row.getInstrument());
        if (instrument == null) return null;
        BigDecimal live = cachedActionablePrice(instrument, priceCache);
        if (live == null || live.signum() <= 0) return null;

        boolean shortSide = "SHORT".equalsIgnoreCase(row.getAction()) || "SELL".equalsIgnoreCase(row.getAction());
        if (shortSide) {
            if (sl != null && live.compareTo(sl) >= 0) return "SL";
            if (tp != null && live.compareTo(tp) <= 0) return "TP";
        } else {
            if (sl != null && live.compareTo(sl) <= 0) return "SL";
            if (tp != null && live.compareTo(tp) >= 0) return "TP";
        }
        return null;
    }

    /**
     * Auto-close is IRREVERSIBLE, so only act on a GENUINELY LIVE and FRESH quote — never a stale cache
     * ({@code CACHE} / {@code FALLBACK_DB}) nor a LIVE-sourced cache entry the market has already moved past.
     * Mirrors {@link VirtualStopWatcher} and {@link PlaybookEntryInvalidationWatcher}: require a live source
     * AND a timestamp within {@link #MAX_PRICE_AGE_SECONDS}. Without this, a momentarily stale tick beyond the
     * virtual SL/TP would flatten a healthy position.
     */
    private static boolean isActionable(LivePriceSnapshot snap) {
        if (snap == null || !LivePriceSource.isLive(snap.source())) {
            return false;
        }
        Instant ts = snap.timestamp();
        return ts != null && ts.isAfter(Instant.now().minusSeconds(MAX_PRICE_AGE_SECONDS));
    }

    /** Per-sweep cached, freshness-gated live price for an instrument. Caches {@code null} too, so a stale /
     *  missing instrument is resolved at most once per sweep rather than once per row. */
    private BigDecimal cachedActionablePrice(Instrument instrument, Map<Instrument, BigDecimal> cache) {
        if (cache.containsKey(instrument)) {
            return cache.get(instrument);
        }
        BigDecimal price = livePricePort.current(instrument)
            .filter(PlaybookPositionReconciler::isActionable)
            .map(snap -> BigDecimal.valueOf(snap.price()))
            .orElse(null);
        cache.put(instrument, price);
        return price;
    }

    private static Instrument parseInstrument(String name) {
        try {
            return name == null ? null : Instrument.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

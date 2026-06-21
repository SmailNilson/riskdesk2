package com.riskdesk.application.quant.positions;

import com.riskdesk.application.marketdata.LivePriceSource;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
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
 * App-side auto-exit for MANUAL chart trades. Polls {@code ACTIVE} manual rows and, when the live
 * price crosses the row's VIRTUAL stop-loss or take-profit, closes the position through the very same
 * flatten path the operator's "Fermer" button uses ({@link ActivePositionsService#closePosition}).
 *
 * <p>This makes the chart's virtual SL/TP actually protect the position — but ONLY while the backend
 * is running and connected. It is NOT a broker bracket order: a backend outage / disconnect leaves the
 * position unprotected at IBKR. That residual gap is what real OCO brackets would close (deferred).</p>
 *
 * <p>Scope is deliberately {@link ExecutionTriggerSource#MANUAL_QUANT_PANEL} only — strategy rows
 * (WTX / auto-arm / playbook) own their exits through their own reconcilers and must not be
 * double-exited here. Gated by {@code riskdesk.quant.virtual-stop.enabled} (default false), read every
 * tick. The loop body is wrapped so a single bad row never stops future ticks, and once a close fires
 * the row leaves {@code ACTIVE} so it is not re-triggered.</p>
 */
@Component
public class VirtualStopWatcher {

    private static final Logger log = LoggerFactory.getLogger(VirtualStopWatcher.class);

    /** Max quote age for an auto-close decision (mirrors {@code PlaybookEntryInvalidationWatcher}). */
    private static final long MAX_PRICE_AGE_SECONDS = 10;

    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final LivePricePort livePricePort;
    private final ActivePositionsService activePositionsService;
    private final QuantVirtualStopProperties props;

    public VirtualStopWatcher(TradeExecutionRepositoryPort tradeExecutionRepository,
                              LivePricePort livePricePort,
                              ActivePositionsService activePositionsService,
                              QuantVirtualStopProperties props) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.livePricePort = livePricePort;
        this.activePositionsService = activePositionsService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${riskdesk.quant.virtual-stop.poll-ms:1500}")
    public void tick() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("virtual-stop watcher tick failed: {}", e.toString());
        }
    }

    /** Package-private so tests can drive a single iteration without a real scheduler. */
    void sweep() {
        if (!props.isEnabled()) return;
        List<TradeExecutionRecord> rows = new ArrayList<>(tradeExecutionRepository.findByTriggerSourceAndStatus(
            ExecutionTriggerSource.MANUAL_QUANT_PANEL, ExecutionStatus.ACTIVE));
        // Also re-drive EXIT_SUBMITTED manual rows whose virtual stop is STILL breached: a first auto-close can
        // rest unfilled (e.g. its marketable limit gapped through), and the row then leaves ACTIVE — so without
        // this the breached level would never be re-attempted until the slow background StaleCloseReconciler.
        // closePosition is idempotent here: the router re-fires only a genuinely stuck close, else skips.
        rows.addAll(tradeExecutionRepository.findByTriggerSourceAndStatus(
            ExecutionTriggerSource.MANUAL_QUANT_PANEL, ExecutionStatus.EXIT_SUBMITTED));
        if (rows.isEmpty()) return;
        // Resolve each distinct instrument's live price ONCE per sweep — a (possibly blocking) provider fetch
        // must not run per row. Caches nulls too. Mirrors ActivePositionsService.listActive's price cache.
        Map<Instrument, BigDecimal> priceCache = new EnumMap<>(Instrument.class);
        for (TradeExecutionRecord row : rows) {
            try {
                String reason = breach(row, priceCache);
                if (reason == null) continue;
                log.info("virtual-stop {} breached executionId={} instrument={} status={} — auto-closing (app-side)",
                    reason, row.getId(), row.getInstrument(), row.getStatus());
                activePositionsService.closePosition(row.getId(), "virtual-stop:" + reason);
            } catch (RuntimeException e) {
                // A bad row / failed close must not stop the rest of the batch or the scheduler.
                log.warn("virtual-stop auto-close failed executionId={}: {}", row.getId(), e.toString());
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
     * Mirrors {@code PlaybookEntryInvalidationWatcher}'s guard for its equally-irreversible cancel: require a
     * live source AND a timestamp within {@link #MAX_PRICE_AGE_SECONDS}. Without this, a momentarily stale tick
     * beyond the virtual SL/TP would flatten a healthy position.
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
            .filter(VirtualStopWatcher::isActionable)
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

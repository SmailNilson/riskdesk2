package com.riskdesk.application.execution;

import com.riskdesk.application.dto.TradeExecutionView;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.MarketDataService;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.playbook.automation.PlaybookDecision;
import com.riskdesk.domain.playbook.automation.port.PlaybookDecisionRepositoryPort;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Live parity for the Confirmation-entry invalidation rule.
 *
 * <p>The paper simulation ({@code TradeSimulationService.touchesInvalidation}) CANCELS a pending
 * STOP-entry setup the moment price first breaks the <em>far</em> side of the zone
 * ({@code zoneHigh + 0.5×ATR} for a SHORT, {@code zoneLow − 0.5×ATR} for a LONG) — the
 * "never trade the reclaim of a broken zone" rule from
 * {@link com.riskdesk.domain.playbook.automation.ConfirmationEntryPlanner}.</p>
 *
 * <p>When Auto-IBKR is armed, {@code PlaybookAutomationService.routeLive} submits the confirmation
 * entry as a <em>resting</em> STOP-LIMIT order at the broker. Nothing else watches that resting
 * order against the invalidation level: without this watcher the order would sit until it triggers
 * (possibly on a reclaim, long after the zone broke) or the user cancels it — diverging from the
 * validated paper behaviour and taking a trade the backtest never took.</p>
 *
 * <p>This scheduler closes that gap. Each tick it scans the resting live
 * {@link ExecutionTriggerSource#PLAYBOOK_AUTO} entries ({@link ExecutionStatus#ENTRY_SUBMITTED},
 * not yet filled), resolves the originating decision's {@code invalidationPrice}, and — if a fresh
 * live price has breached it — cancels the resting broker order and, only once the broker accepts
 * the cancel, marks the row {@link ExecutionStatus#CANCELLED}. It only cancels; it never opens or
 * modifies a position.</p>
 *
 * <p><b>Safety.</b> It acts only on rows that (a) carry a broker {@code ibkrOrderId} (something is
 * actually resting), (b) have zero filled quantity, and (c) are still pending after a fresh re-read
 * immediately before the terminal write — so a fill that races in between the price read and the
 * cancel wins and the row is left ACTIVE for the fill tracker. The broker {@code Cancelled} callback
 * that follows {@code cancelOrder} is idempotent against the CANCELLED we set here
 * ({@code ExecutionFillTrackingService} guards on the current status).</p>
 *
 * <p><b>Residual paper/live gap (documented, not a bug).</b> The paper sim tests the invalidation
 * level against each candle's high/low (it sees intrabar wicks); this watcher tests it against the
 * polled last trade price. A brief wick that pokes the level between two polls and recovers can let
 * the live order survive where the sim cancelled. The miss is in the conservative direction (live
 * keeps a setup the sim dropped, rather than dropping one the sim kept) and is bounded by the poll
 * interval.</p>
 */
@Component
public class PlaybookEntryInvalidationWatcher {

    private static final Logger log = LoggerFactory.getLogger(PlaybookEntryInvalidationWatcher.class);
    private static final String EXECUTIONS_TOPIC = "/topic/executions";
    private static final String LIVE_SOURCE_PREFIX = "LIVE";
    /** A cancel is irreversible, so only act on a quote no older than this (MarketDataService can
     *  return a LIVE-sourced cache entry up to 15s old, or any age on an instant-fetch failure). */
    private static final long MAX_PRICE_AGE_SECONDS = 10;
    private static final int MAX_REASON_LEN = 256;

    private final TradeExecutionRepositoryPort executionRepository;
    private final PlaybookDecisionRepositoryPort decisionRepository;
    private final IbkrOrderService ibkrOrderService;
    private final IbkrProperties ibkrProperties;
    private final ObjectProvider<MarketDataService> marketDataServiceProvider;
    private final ObjectProvider<SimpMessagingTemplate> messagingProvider;
    private final boolean enabled;

    public PlaybookEntryInvalidationWatcher(TradeExecutionRepositoryPort executionRepository,
                                            PlaybookDecisionRepositoryPort decisionRepository,
                                            IbkrOrderService ibkrOrderService,
                                            IbkrProperties ibkrProperties,
                                            ObjectProvider<MarketDataService> marketDataServiceProvider,
                                            ObjectProvider<SimpMessagingTemplate> messagingProvider,
                                            @Value("${riskdesk.playbook.invalidation-watch.enabled:true}")
                                            boolean enabled) {
        this.executionRepository = executionRepository;
        this.decisionRepository = decisionRepository;
        this.ibkrOrderService = ibkrOrderService;
        this.ibkrProperties = ibkrProperties;
        this.marketDataServiceProvider = marketDataServiceProvider;
        this.messagingProvider = messagingProvider;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${riskdesk.playbook.invalidation-watch.interval-ms:3000}")
    public void cancelInvalidatedEntries() {
        if (!enabled || !ibkrProperties.isEnabled()) {
            return; // disabled, or no broker to cancel against
        }
        List<TradeExecutionRecord> pending = pendingLivePlaybookEntries();
        for (TradeExecutionRecord row : pending) {
            try {
                evaluateOne(row);
            } catch (RuntimeException e) {
                log.warn("PLAYBOOK invalidation-watch: row {} skipped this tick — {}",
                    row.getId(), e.getMessage());
            }
        }
    }

    /**
     * Resting STOP entries — status {@link ExecutionStatus#ENTRY_SUBMITTED}. A
     * {@code PENDING_ENTRY_SUBMISSION} row is deliberately excluded: {@code routeLive} sets the
     * broker {@code ibkrOrderId} only at the same instant it leaves that state, so such a row has
     * nothing resting at the broker to cancel ({@code evaluateOne} would skip it anyway).
     */
    private List<TradeExecutionRecord> pendingLivePlaybookEntries() {
        return executionRepository.findByTriggerSourceAndStatus(
            ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.ENTRY_SUBMITTED);
    }

    private void evaluateOne(TradeExecutionRecord row) {
        if (hasFill(row)) {
            return; // partially/fully filled — a position exists; leave it to the fill tracker
        }
        Integer orderId = row.getIbkrOrderId();
        if (orderId == null) {
            return; // not yet resting at the broker; a later tick sees it once submitted
        }
        PlaybookDecision decision = row.getReviewAlertKey() == null
            ? null
            : decisionRepository.findByDecisionKey(row.getReviewAlertKey()).orElse(null);
        if (decision == null || !decision.isStopEntry() || decision.invalidationPrice() == null) {
            return; // legacy limit entries / unknown decision carry no invalidation level
        }
        Instrument instrument;
        try {
            instrument = Instrument.valueOf(row.getInstrument());
        } catch (IllegalArgumentException | NullPointerException e) {
            return;
        }
        Boolean shortSide = resolveShortSide(row.getAction());
        if (shortSide == null) {
            return; // unrecognized action — never cancel on a guessed direction
        }
        BigDecimal price = livePrice(instrument);
        if (price == null) {
            return; // no fresh live price — never cancel on stale/unknown data
        }
        if (!breached(shortSide, price, decision.invalidationPrice())) {
            return;
        }
        cancelInvalidated(row, decision, price, orderId);
    }

    /** TRUE for a short, FALSE for a long, {@code null} for any action we don't recognize. */
    private static Boolean resolveShortSide(String action) {
        if (action == null) {
            return null;
        }
        if ("SHORT".equalsIgnoreCase(action) || "SELL".equalsIgnoreCase(action)) {
            return Boolean.TRUE;
        }
        if ("LONG".equalsIgnoreCase(action) || "BUY".equalsIgnoreCase(action)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * SHORT is invalidated when price rises to/through the level (zoneHigh + 0.5×ATR); LONG when it
     * falls to/through the level (zoneLow − 0.5×ATR). Mirrors {@code TradeSimulationService.touchesInvalidation}.
     */
    private static boolean breached(boolean isShort, BigDecimal price, BigDecimal invalidation) {
        return isShort
            ? price.compareTo(invalidation) >= 0
            : price.compareTo(invalidation) <= 0;
    }

    private void cancelInvalidated(TradeExecutionRecord row,
                                   PlaybookDecision decision,
                                   BigDecimal price,
                                   int orderId) {
        try {
            ibkrOrderService.cancelOrder(orderId);
        } catch (RuntimeException e) {
            // The cancel did NOT go through: either the order just triggered/filled (an "already
            // filled" reject) or the gateway hiccupped (order still resting). Do NOT mark the row
            // CANCELLED — that would desync app state from a broker order that may still be working,
            // and CANCELLED is terminal so the watcher would never retry. Return and let a later tick
            // retry the actual cancel (the already-filled case self-heals once the fill callback lands).
            log.warn("PLAYBOOK invalidation-watch: cancelOrder({}) failed for row {} — not cancelling, "
                + "will retry next tick: {}", orderId, row.getId(), e.getMessage());
            return;
        }
        // Cancel accepted by the broker. Re-read before the terminal write: a fill that raced in just
        // before the cancel must win — the position is real, so leave the row for the fill tracker.
        TradeExecutionRecord fresh = executionRepository.findById(row.getId()).orElse(null);
        if (fresh == null) {
            // Can't confirm the row's current state — never write CANCELLED blind on the stale snapshot.
            log.warn("PLAYBOOK invalidation-watch: row {} not found on re-read — not cancelling", row.getId());
            return;
        }
        if (!isPending(fresh.getStatus()) || hasFill(fresh)) {
            log.warn("PLAYBOOK invalidation-watch: row {} raced to {} (filledQty={}) — not cancelling",
                fresh.getId(), fresh.getStatus(), fresh.getFilledQuantity());
            return;
        }
        fresh.setStatus(ExecutionStatus.CANCELLED);
        fresh.setStatusReason(truncate("PLAYBOOK confirmation invalidated — price " + price
            + " breached invalidation " + decision.invalidationPrice()
            + " before entry filled; resting order " + orderId + " cancelled"));
        fresh.setUpdatedAt(Instant.now());
        TradeExecutionRecord saved = executionRepository.save(fresh);
        publish(saved);
        log.info("PLAYBOOK invalidation-watch: cancelled resting entry row {} ({} {} {}) — "
                + "price {} breached invalidation {}",
            saved.getId(), saved.getInstrument(), saved.getTimeframe(), saved.getAction(),
            price, decision.invalidationPrice());
    }

    private BigDecimal livePrice(Instrument instrument) {
        MarketDataService marketDataService = marketDataServiceProvider.getIfAvailable();
        if (marketDataService == null) {
            return null;
        }
        MarketDataService.StoredPrice stored = marketDataService.currentPrice(instrument);
        if (stored == null || stored.price() == null) {
            return null;
        }
        // Only act on a genuinely live quote. A CACHE/FALLBACK_DB source could be stale and wrongly
        // drop a valid setup; cancelling is irreversible for that decision, so require LIVE.
        String source = stored.source();
        if (source == null || !source.startsWith(LIVE_SOURCE_PREFIX)) {
            return null;
        }
        // …and a genuinely fresh one. currentPrice returns a LIVE-sourced cache entry up to ~15s old
        // (and any age on an instant-fetch failure); acting on that could cancel on a price the market
        // has already moved away from. The source string alone is not a freshness guarantee.
        Instant timestamp = stored.timestamp();
        if (timestamp == null || timestamp.isBefore(Instant.now().minusSeconds(MAX_PRICE_AGE_SECONDS))) {
            return null;
        }
        return stored.price();
    }

    private static boolean isPending(ExecutionStatus status) {
        return status == ExecutionStatus.ENTRY_SUBMITTED;
    }

    private static boolean hasFill(TradeExecutionRecord row) {
        BigDecimal filled = row.getFilledQuantity();
        return filled != null && filled.signum() > 0;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_REASON_LEN ? value : value.substring(0, MAX_REASON_LEN);
    }

    private void publish(TradeExecutionRecord execution) {
        try {
            SimpMessagingTemplate messaging = messagingProvider.getIfAvailable();
            if (messaging != null) {
                messaging.convertAndSend(EXECUTIONS_TOPIC, TradeExecutionView.from(execution));
            }
        } catch (Exception e) {
            log.debug("PLAYBOOK invalidation-watch: could not publish row {} on {}: {}",
                execution.getId(), EXECUTIONS_TOPIC, e.getMessage());
        }
    }
}

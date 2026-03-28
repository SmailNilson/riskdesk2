package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.service.AlertDeduplicator;
import com.riskdesk.domain.alert.service.IndicatorAlertEvaluator;
import com.riskdesk.domain.alert.service.RiskAlertEvaluator;
import com.riskdesk.domain.alert.service.SignalPreFilterService;
import com.riskdesk.domain.alert.model.IndicatorAlertSnapshot;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import com.riskdesk.domain.trading.aggregate.Portfolio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates alert evaluation, deduplication, and publishing.
 * Delegates risk checks to RiskAlertEvaluator and indicator checks to IndicatorAlertEvaluator.
 * Publishes domain Alert objects to WebSocket /topic/alerts.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private static final int MAX_RECENT_ALERTS = 50;

    private final PositionService           positionService;
    private final IndicatorService          indicatorService;
    private final RiskAlertEvaluator        riskAlertEvaluator;
    private final IndicatorAlertEvaluator   indicatorAlertEvaluator;
    private final AlertDeduplicator         deduplicator;
    private final SignalPreFilterService    signalPreFilterService;
    private final MentorSignalReviewService mentorSignalReviewService;
    private final SimpMessagingTemplate     messagingTemplate;

    // Recent alerts buffer (last 50) for REST endpoint
    private final LinkedList<Map<String, Object>> recentAlerts = new LinkedList<>();

    public AlertService(PositionService positionService,
                        IndicatorService indicatorService,
                        RiskAlertEvaluator riskAlertEvaluator,
                        IndicatorAlertEvaluator indicatorAlertEvaluator,
                        AlertDeduplicator deduplicator,
                        SignalPreFilterService signalPreFilterService,
                        MentorSignalReviewService mentorSignalReviewService,
                        SimpMessagingTemplate messagingTemplate) {
        this.positionService           = positionService;
        this.indicatorService          = indicatorService;
        this.riskAlertEvaluator        = riskAlertEvaluator;
        this.indicatorAlertEvaluator   = indicatorAlertEvaluator;
        this.deduplicator              = deduplicator;
        this.signalPreFilterService    = signalPreFilterService;
        this.mentorSignalReviewService = mentorSignalReviewService;
        this.messagingTemplate         = messagingTemplate;
    }

    /**
     * Called by MarketDataService after prices are updated.
     * Evaluates risk and indicator conditions for the given instrument.
     */
    public void evaluate(Instrument instrument) {
        List<Alert> alerts = new ArrayList<>();

        // Risk evaluation via domain service
        try {
            Portfolio portfolio = positionService.getPortfolio();
            alerts.addAll(riskAlertEvaluator.evaluate(portfolio));
        } catch (Exception e) {
            log.debug("Risk evaluation error: {}", e.getMessage());
        }

        // Compute H1 snapshot first — used both for the H1 evaluation cycle and as the
        // Higher Timeframe reference for the HTF trend filter on 10m signals (Rule 1).
        IndicatorSnapshot h1Snap = null;
        String h1Trend = "UNDEFINED";
        try {
            h1Snap = indicatorService.computeSnapshot(instrument, "1h");
            if (h1Snap != null) h1Trend = h1Snap.marketStructureTrend();
        } catch (Exception e) {
            log.debug("H1 snapshot error for {}: {}", instrument, e.getMessage());
        }

        // Indicator evaluation via domain service (both timeframes)
        for (String timeframe : List.of("10m", "1h")) {
            try {
                // Reuse already-computed H1 snapshot; compute fresh snapshot for 10m
                IndicatorSnapshot snap = "1h".equals(timeframe) ? h1Snap
                        : indicatorService.computeSnapshot(instrument, timeframe);
                if (snap == null) continue;

                List<Alert> indicatorAlerts = indicatorAlertEvaluator.evaluate(instrument, timeframe, toAlertSnapshot(snap));
                alerts.addAll(indicatorAlerts);

                // Record all candidate signals for anti-chop detection BEFORE filtering
                signalPreFilterService.recordSignals(indicatorAlerts, timeframe);

                // Apply Rules 1 (HTF alignment) and 3 (anti-chop)
                List<Alert> filteredAlerts = signalPreFilterService.filter(indicatorAlerts, timeframe, h1Trend);

                // Publish with Rule 2: dynamic cooldown proportional to timeframe
                long cooldownSeconds = Timeframe.fromLabel(timeframe).periodSeconds();
                List<Alert> publishedAlerts = new ArrayList<>();
                for (Alert alert : filteredAlerts) {
                    if (publishAlertWithoutMentor(alert, cooldownSeconds)) {
                        publishedAlerts.add(alert);
                    }
                }
                if (!publishedAlerts.isEmpty()) {
                    mentorSignalReviewService.captureGroupReview(publishedAlerts, snap);
                }
            } catch (Exception e) {
                log.debug("Indicator evaluation error for {} {}: {}", instrument, timeframe, e.getMessage());
            }
        }

        for (Alert alert : alerts) {
            if (alert.category().name().equals("MACD")
                || alert.category().name().equals("WAVETREND")
                || alert.category().name().equals("RSI")
                || alert.category().name().equals("SMC")
                || alert.category().name().equals("ORDER_BLOCK")) {
                continue;
            }
            publishAlert(alert, null);
        }
    }

    /**
     * Publish a single alert directly (used by signal scanners).
     * Applies deduplication, buffers, and broadcasts via WebSocket.
     */
    public void publish(Alert alert) {
        publishAlert(alert, null);
    }

    /**
     * Returns recent alerts for REST consumers (newest first).
     */
    public List<Map<String, Object>> getRecentAlerts() {
        synchronized (recentAlerts) {
            return new ArrayList<>(recentAlerts);
        }
    }

    // -----------------------------------------------------------------------

    private Map<String, Object> toPayload(Alert alert) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("severity",   alert.severity().name());
        map.put("category",   alert.category().name());
        map.put("message",    alert.message());
        map.put("instrument", alert.instrument());
        map.put("timestamp",  alert.timestamp().toString());
        return map;
    }

    private IndicatorAlertSnapshot toAlertSnapshot(IndicatorSnapshot snapshot) {
        return new IndicatorAlertSnapshot(
            snapshot.emaCrossover(),
            snapshot.rsi(),
            snapshot.rsiSignal(),
            snapshot.macdCrossover(),
            snapshot.lastBreakType(),
            snapshot.lastInternalBreakType(),
            snapshot.lastSwingBreakType(),
            snapshot.wtWt1(),
            snapshot.wtCrossover(),
            snapshot.wtSignal(),
            snapshot.vwap(),
            snapshot.activeOrderBlocks() == null ? List.of() : snapshot.activeOrderBlocks().stream()
                .map(block -> new IndicatorAlertSnapshot.OrderBlockZone(block.type(), block.high(), block.low()))
                .toList(),
            snapshot.lastCandleTimestamp()
        );
    }

    /**
     * Publish alert to WebSocket without triggering mentor review.
     * Uses a timeframe-aware cooldown (Rule 2) instead of the global default.
     * Returns true if the alert passed dedup and was actually published.
     */
    private boolean publishAlertWithoutMentor(Alert alert, long cooldownSeconds) {
        if (!deduplicator.shouldFire(alert, cooldownSeconds)) {
            return false;
        }
        log.info("ALERT [{}] {}", alert.severity(), alert.message());
        Map<String, Object> payload = toPayload(alert);
        synchronized (recentAlerts) {
            recentAlerts.addFirst(payload);
            while (recentAlerts.size() > MAX_RECENT_ALERTS) {
                recentAlerts.removeLast();
            }
        }
        try {
            messagingTemplate.convertAndSend("/topic/alerts", payload);
        } catch (Exception e) {
            log.debug("WebSocket alert send failed: {}", e.getMessage());
        }
        return true;
    }

    private void publishAlert(Alert alert, IndicatorSnapshot snapshot) {
        if (!deduplicator.shouldFire(alert)) {
            return;
        }

        log.info("ALERT [{}] {}", alert.severity(), alert.message());
        Map<String, Object> payload = toPayload(alert);
        synchronized (recentAlerts) {
            recentAlerts.addFirst(payload);
            while (recentAlerts.size() > MAX_RECENT_ALERTS) {
                recentAlerts.removeLast();
            }
        }
        try {
            messagingTemplate.convertAndSend("/topic/alerts", payload);
        } catch (Exception e) {
            log.debug("WebSocket alert send failed: {}", e.getMessage());
        }

        if (snapshot != null) {
            mentorSignalReviewService.captureInitialReview(alert, snapshot);
        } else {
            mentorSignalReviewService.captureInitialReview(alert);
        }
    }
}

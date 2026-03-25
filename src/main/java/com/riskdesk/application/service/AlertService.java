package com.riskdesk.application.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.service.AlertDeduplicator;
import com.riskdesk.domain.alert.service.IndicatorAlertEvaluator;
import com.riskdesk.domain.alert.service.RiskAlertEvaluator;
import com.riskdesk.presentation.dto.IndicatorSnapshot;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.trading.aggregate.Portfolio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    private final PositionService         positionService;
    private final IndicatorService        indicatorService;
    private final RiskAlertEvaluator      riskAlertEvaluator;
    private final IndicatorAlertEvaluator indicatorAlertEvaluator;
    private final AlertDeduplicator       deduplicator;
    private final SimpMessagingTemplate   messagingTemplate;

    // Recent alerts buffer (last 50) for REST endpoint
    private final LinkedList<Map<String, Object>> recentAlerts = new LinkedList<>();

    public AlertService(PositionService positionService,
                        IndicatorService indicatorService,
                        RiskAlertEvaluator riskAlertEvaluator,
                        IndicatorAlertEvaluator indicatorAlertEvaluator,
                        AlertDeduplicator deduplicator,
                        SimpMessagingTemplate messagingTemplate) {
        this.positionService         = positionService;
        this.indicatorService        = indicatorService;
        this.riskAlertEvaluator      = riskAlertEvaluator;
        this.indicatorAlertEvaluator = indicatorAlertEvaluator;
        this.deduplicator            = deduplicator;
        this.messagingTemplate       = messagingTemplate;
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

        // Indicator evaluation via domain service (both timeframes)
        for (String timeframe : List.of("10m", "1h")) {
            try {
                IndicatorSnapshot snap = indicatorService.computeSnapshot(instrument, timeframe);
                alerts.addAll(indicatorAlertEvaluator.evaluate(instrument, timeframe, snap));
            } catch (Exception e) {
                log.debug("Indicator evaluation error for {} {}: {}", instrument, timeframe, e.getMessage());
            }
        }

        // Deduplicate, buffer, and publish
        for (Alert alert : alerts) {
            if (!deduplicator.shouldFire(alert)) continue;

            log.info("ALERT [{}] {}", alert.severity(), alert.message());
            Map<String, Object> payload = toPayload(alert);
            synchronized (recentAlerts) {
                recentAlerts.addFirst(payload);
                while (recentAlerts.size() > MAX_RECENT_ALERTS) recentAlerts.removeLast();
            }
            try {
                messagingTemplate.convertAndSend("/topic/alerts", payload);
            } catch (Exception e) {
                log.debug("WebSocket alert send failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Publish a single alert directly (used by signal scanners).
     * Applies deduplication, buffers, and broadcasts via WebSocket.
     */
    public void publish(Alert alert) {
        if (!deduplicator.shouldFire(alert)) return;

        log.info("ALERT [{}] {}", alert.severity(), alert.message());
        Map<String, Object> payload = toPayload(alert);
        synchronized (recentAlerts) {
            recentAlerts.addFirst(payload);
            while (recentAlerts.size() > MAX_RECENT_ALERTS) recentAlerts.removeLast();
        }
        try {
            messagingTemplate.convertAndSend("/topic/alerts", payload);
        } catch (Exception e) {
            log.debug("WebSocket alert send failed: {}", e.getMessage());
        }
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
        map.put("timestamp",  Instant.now().toString());
        return map;
    }
}

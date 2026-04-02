package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext.SrLevel;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;
import com.riskdesk.domain.behaviouralert.service.BehaviourAlertEvaluator;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application service for behaviour-based alerts.
 * Runs independently from {@link AlertService} — no shared state between the two circuits.
 *
 * <p>Triggered by {@link MarketDataService#pollPrices()} on each live price tick,
 * alongside the existing {@code AlertService.evaluate()} call.
 */
@Service
public class BehaviourAlertService {

    private static final Logger log = LoggerFactory.getLogger(BehaviourAlertService.class);
    private static final String[] TIMEFRAMES = {"10m", "1h"};
    private static final long DEDUP_COOLDOWN_SECONDS = 300L;

    private final IndicatorService indicatorService;
    private final BehaviourAlertEvaluator evaluator;
    private final SimpMessagingTemplate messagingTemplate;

    // Independent key-based deduplication (cooldown per signal key)
    private final Map<String, Instant> lastFired = new ConcurrentHashMap<>();

    public BehaviourAlertService(IndicatorService indicatorService,
                                  BehaviourAlertEvaluator evaluator,
                                  SimpMessagingTemplate messagingTemplate) {
        this.indicatorService  = indicatorService;
        this.evaluator         = evaluator;
        this.messagingTemplate = messagingTemplate;
    }

    public void evaluate(Instrument instrument) {
        for (String tf : TIMEFRAMES) {
            try {
                IndicatorSnapshot snap = indicatorService.computeSnapshot(instrument, tf);
                BehaviourAlertContext context = toContext(instrument, tf, snap);
                List<BehaviourAlertSignal> signals = evaluator.evaluate(context);
                for (BehaviourAlertSignal signal : signals) {
                    publish(signal);
                }
            } catch (Exception e) {
                log.debug("BehaviourAlertService error for {} {}: {}", instrument, tf, e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------

    private BehaviourAlertContext toContext(Instrument instrument, String tf, IndicatorSnapshot snap) {
        List<SrLevel> srLevels = new ArrayList<>();

        if (snap.equalHighs() != null) {
            for (var eq : snap.equalHighs()) {
                if (eq.price() != null) srLevels.add(new SrLevel("EQH", eq.price()));
            }
        }
        if (snap.equalLows() != null) {
            for (var eq : snap.equalLows()) {
                if (eq.price() != null) srLevels.add(new SrLevel("EQL", eq.price()));
            }
        }
        addIfNotNull(srLevels, "STRONG_HIGH", snap.strongHigh());
        addIfNotNull(srLevels, "STRONG_LOW",  snap.strongLow());
        addIfNotNull(srLevels, "WEAK_HIGH",   snap.weakHigh());
        addIfNotNull(srLevels, "WEAK_LOW",    snap.weakLow());

        return new BehaviourAlertContext(
                instrument.name(), tf,
                snap.lastPrice(),
                snap.ema50(),
                snap.ema200(),
                srLevels,
                snap.lastCandleTimestamp()
        );
    }

    private static void addIfNotNull(List<SrLevel> list, String type, BigDecimal price) {
        if (price != null) list.add(new SrLevel(type, price));
    }

    private void publish(BehaviourAlertSignal signal) {
        Instant now = Instant.now();
        Instant last = lastFired.get(signal.key());
        if (last != null && now.isBefore(last.plusSeconds(DEDUP_COOLDOWN_SECONDS))) {
            return;
        }
        lastFired.put(signal.key(), now);

        log.info("BEHAVIOUR_ALERT [WARNING] {} — {}", signal.category(), signal.message());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("severity",   "WARNING");
        payload.put("category",   signal.category().name());
        payload.put("message",    signal.message());
        payload.put("instrument", signal.instrument());
        payload.put("timestamp",  signal.timestamp().toString());

        try {
            messagingTemplate.convertAndSend("/topic/alerts", payload);
        } catch (Exception e) {
            log.debug("WebSocket behaviour alert send failed: {}", e.getMessage());
        }
    }
}

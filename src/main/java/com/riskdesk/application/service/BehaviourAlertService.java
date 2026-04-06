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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application service for behaviour-based alerts.
 * Runs independently from {@link AlertService} — no shared state between the two circuits.
 *
 * <p>Triggered by {@link MarketDataService#pollPrices()} on each live price tick,
 * alongside the existing {@code AlertService.evaluate()} call.
 *
 * <p>S/R levels are sourced exclusively from HTF snapshots (1h, 4h, 1d) so that
 * {@link com.riskdesk.domain.behaviouralert.rule.SupportResistanceTouchRule} only fires
 * against structurally significant levels, not short-term noise from the evaluation timeframe.
 */
@Service
public class BehaviourAlertService {

    private static final Logger log = LoggerFactory.getLogger(BehaviourAlertService.class);
    private static final String[] TIMEFRAMES = {"10m", "1h"};
    /** Timeframes from which S/R levels are sourced (HTF only). */
    private static final String[] SR_SOURCE_TIMEFRAMES = {"1h", "4h", "1d"};
    private static final long DEDUP_COOLDOWN_SECONDS = 900L;

    private final IndicatorService indicatorService;
    private final BehaviourAlertEvaluator evaluator;
    private final SimpMessagingTemplate messagingTemplate;
    private final MentorSignalReviewService mentorSignalReviewService;

    // Independent key-based deduplication (cooldown per signal key)
    private final Map<String, Instant> lastFired = new ConcurrentHashMap<>();

    public BehaviourAlertService(IndicatorService indicatorService,
                                  BehaviourAlertEvaluator evaluator,
                                  SimpMessagingTemplate messagingTemplate,
                                  MentorSignalReviewService mentorSignalReviewService) {
        this.indicatorService  = indicatorService;
        this.evaluator         = evaluator;
        this.messagingTemplate = messagingTemplate;
        this.mentorSignalReviewService = mentorSignalReviewService;
    }

    public void evaluate(Instrument instrument) {
        List<SrLevel> htfSrLevels = buildHtfSrLevels(instrument);

        for (String tf : TIMEFRAMES) {
            try {
                IndicatorSnapshot snap = indicatorService.computeSnapshot(instrument, tf);
                BehaviourAlertContext context = toContext(instrument, tf, snap, htfSrLevels);
                List<BehaviourAlertSignal> signals = evaluator.evaluate(context);
                BehaviourAlertSignal firstPublished = null;
                for (BehaviourAlertSignal signal : signals) {
                    boolean published = publish(signal);
                    if (published && firstPublished == null) {
                        firstPublished = signal;
                    }
                }
                if (firstPublished != null) {
                    try {
                        mentorSignalReviewService.captureBehaviourReview(firstPublished, tf, snap);
                    } catch (Exception e) {
                        log.debug("Behaviour mentor review capture failed for {} {}: {}", instrument, tf, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("BehaviourAlertService error for {} {}: {}", instrument, tf, e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Fetches S/R levels from HTF snapshots (1h, 4h, 1d) only.
     * Levels are deduplicated by price to avoid double-firing on identical values
     * that appear in multiple timeframes.
     */
    private List<SrLevel> buildHtfSrLevels(Instrument instrument) {
        List<SrLevel> levels = new ArrayList<>();
        for (String htfTf : SR_SOURCE_TIMEFRAMES) {
            try {
                IndicatorSnapshot htfSnap = indicatorService.computeSnapshot(instrument, htfTf);

                Optional.ofNullable(htfSnap.equalHighs()).orElse(List.of()).stream()
                        .filter(eq -> eq.price() != null)
                        .map(eq -> new SrLevel("EQH", eq.price()))
                        .forEach(levels::add);

                Optional.ofNullable(htfSnap.equalLows()).orElse(List.of()).stream()
                        .filter(eq -> eq.price() != null)
                        .map(eq -> new SrLevel("EQL", eq.price()))
                        .forEach(levels::add);

                addIfNotNull(levels, "STRONG_HIGH", htfSnap.strongHigh());
                addIfNotNull(levels, "STRONG_LOW",  htfSnap.strongLow());
                addIfNotNull(levels, "WEAK_HIGH",   htfSnap.weakHigh());
                addIfNotNull(levels, "WEAK_LOW",    htfSnap.weakLow());
            } catch (Exception e) {
                log.debug("BehaviourAlertService: could not fetch HTF S/R for {} {}: {}", instrument, htfTf, e.getMessage());
            }
        }
        return levels;
    }

    private BehaviourAlertContext toContext(Instrument instrument, String tf, IndicatorSnapshot snap,
                                            List<SrLevel> htfSrLevels) {
        return new BehaviourAlertContext(
                instrument.name(),
                tf,
                snap.lastPrice(),
                snap.ema50(),
                snap.ema200(),
                htfSrLevels,
                snap.lastCandleTimestamp(),
                snap.chaikinOscillator(),
                snap.cmf()
        );
    }

    private static void addIfNotNull(List<SrLevel> list, String type, BigDecimal price) {
        if (price != null) list.add(new SrLevel(type, price));
    }

    private boolean publish(BehaviourAlertSignal signal) {
        Instant now = Instant.now();
        Instant last = lastFired.get(signal.key());
        if (last != null && now.isBefore(last.plusSeconds(DEDUP_COOLDOWN_SECONDS))) {
            return false;
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
        return true;
    }
}

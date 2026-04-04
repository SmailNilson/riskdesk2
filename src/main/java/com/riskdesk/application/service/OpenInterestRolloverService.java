package com.riskdesk.application.service;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.OpenInterestRolloverRule;
import com.riskdesk.domain.contract.OpenInterestRolloverRule.OpenInterestSnapshot;
import com.riskdesk.domain.contract.RolloverRecommendation;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayResolvedContract;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Periodically compares Open Interest between the current and next contract month
 * for each futures instrument. When the next month's OI exceeds the current month's OI,
 * it either auto-confirms the rollover or publishes a recommendation alert.
 *
 * Runs every 6 hours (with 2-minute initial delay after startup).
 */
@Service
public class OpenInterestRolloverService {

    private static final Logger log = LoggerFactory.getLogger(OpenInterestRolloverService.class);

    private final ActiveContractRegistry    contractRegistry;
    private final IbGatewayContractResolver contractResolver;
    private final OpenInterestProvider      openInterestProvider;
    private final IbkrProperties            ibkrProperties;
    private final RolloverDetectionService  rolloverDetectionService;
    private final SimpMessagingTemplate     messagingTemplate;
    private final boolean                   autoConfirm;

    public OpenInterestRolloverService(ActiveContractRegistry contractRegistry,
                                       IbGatewayContractResolver contractResolver,
                                       OpenInterestProvider openInterestProvider,
                                       IbkrProperties ibkrProperties,
                                       RolloverDetectionService rolloverDetectionService,
                                       SimpMessagingTemplate messagingTemplate,
                                       @Value("${riskdesk.rollover.auto-confirm:false}") boolean autoConfirm) {
        this.contractRegistry       = contractRegistry;
        this.contractResolver       = contractResolver;
        this.openInterestProvider   = openInterestProvider;
        this.ibkrProperties         = ibkrProperties;
        this.rolloverDetectionService = rolloverDetectionService;
        this.messagingTemplate      = messagingTemplate;
        this.autoConfirm            = autoConfirm;
    }

    /** Scheduled check every 6 hours (with 2-minute initial delay). */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 2 * 60 * 1000L)
    public void checkOpenInterestRollovers() {
        if (!ibkrProperties.isEnabled()) return;

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            try {
                checkInstrument(instrument);
            } catch (Exception e) {
                log.debug("OI rollover check failed for {} — {}", instrument, e.getMessage());
            }
        }
    }

    /**
     * On-demand OI check for all instruments. Called by the REST endpoint.
     */
    public Map<String, Object> checkAllNow() {
        Map<String, Object> results = new LinkedHashMap<>();
        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            try {
                RolloverRecommendation rec = checkInstrument(instrument);
                if (rec != null) {
                    results.put(instrument.name(), toMap(rec));
                } else {
                    results.put(instrument.name(), Map.of("status", "UNAVAILABLE"));
                }
            } catch (Exception e) {
                results.put(instrument.name(), Map.of("status", "ERROR", "message", e.getMessage()));
            }
        }
        return results;
    }

    /**
     * Returns the latest OI status for all instruments (without triggering a new fetch).
     * For GET /api/rollover/oi-status, delegates to checkAllNow for a live fetch.
     */
    public Map<String, Object> getOiStatus() {
        return checkAllNow();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private RolloverRecommendation checkInstrument(Instrument instrument) {
        String currentMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        if (currentMonth == null) {
            log.debug("OI check: no active contract for {}", instrument);
            return null;
        }

        // Resolve front + next month contracts from IBKR
        List<IbGatewayResolvedContract> topTwo = contractResolver.resolveTopTwo(instrument);
        if (topTwo.size() < 2) {
            log.debug("OI check: fewer than 2 contract months available for {}", instrument);
            return null;
        }

        String frontMonth = normalizeMonth(topTwo.get(0).contract().lastTradeDateOrContractMonth());
        String nextMonth  = normalizeMonth(topTwo.get(1).contract().lastTradeDateOrContractMonth());

        if (frontMonth == null || nextMonth == null) {
            log.debug("OI check: could not parse contract months for {}", instrument);
            return null;
        }

        // Fetch OI for both months
        OptionalLong currentOI = openInterestProvider.fetchOpenInterest(instrument, frontMonth);
        OptionalLong nextOI    = openInterestProvider.fetchOpenInterest(instrument, nextMonth);

        if (currentOI.isEmpty() || nextOI.isEmpty()) {
            log.debug("OI check: OI data unavailable for {} ({} / {})", instrument, frontMonth, nextMonth);
            return null;
        }

        // Evaluate the domain rule
        OpenInterestSnapshot currentSnap = new OpenInterestSnapshot(frontMonth, currentOI.getAsLong());
        OpenInterestSnapshot nextSnap    = new OpenInterestSnapshot(nextMonth, nextOI.getAsLong());
        RolloverRecommendation recommendation = OpenInterestRolloverRule.evaluate(instrument, currentSnap, nextSnap);

        log.info("OI check: {} — current {} OI={}, next {} OI={} → {}",
            instrument, frontMonth, currentOI.getAsLong(), nextMonth, nextOI.getAsLong(), recommendation.action());

        if (recommendation.action() == RolloverRecommendation.Action.RECOMMEND_ROLL) {
            if (autoConfirm) {
                rolloverDetectionService.confirmRollover(instrument, nextMonth);
                log.info("OI auto-rollover: {} rolled from {} to {}", instrument, frontMonth, nextMonth);
            }
            pushOiRolloverAlert(recommendation);
        }

        return recommendation;
    }

    private void pushOiRolloverAlert(RolloverRecommendation rec) {
        try {
            messagingTemplate.convertAndSend("/topic/rollover", Map.of(
                "type",          "OI_ROLLOVER",
                "instrument",    rec.instrument().name(),
                "currentMonth",  rec.currentMonth(),
                "nextMonth",     rec.nextMonth(),
                "currentOI",     rec.currentOI(),
                "nextOI",        rec.nextOI(),
                "action",        rec.action().name(),
                "autoConfirmed", autoConfirm
            ));
        } catch (Exception e) {
            log.debug("OI rollover WebSocket push failed — {}", e.getMessage());
        }
    }

    private Map<String, Object> toMap(RolloverRecommendation rec) {
        return Map.of(
            "currentMonth", rec.currentMonth(),
            "nextMonth",    rec.nextMonth(),
            "currentOI",    rec.currentOI(),
            "nextOI",       rec.nextOI(),
            "action",       rec.action().name()
        );
    }

    private static String normalizeMonth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
    }
}

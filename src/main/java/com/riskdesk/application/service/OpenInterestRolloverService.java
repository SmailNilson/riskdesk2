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

import com.riskdesk.domain.shared.TradingSessionResolver;

import java.time.Instant;
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
        if (!TradingSessionResolver.isMarketOpen(Instant.now())) return;

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
        // ── Step 1: Fetch up to 3 upcoming contracts from IBKR ──
        List<IbGatewayResolvedContract> upcoming = contractResolver.resolveNextContracts(instrument);
        if (upcoming.size() < 2) {
            log.debug("OI check: fewer than 2 contract months available for {}", instrument);
            return null;
        }

        // Parse all contract months upfront
        List<String> months = upcoming.stream()
                .map(r -> normalizeMonth(r.contract().lastTradeDateOrContractMonth()))
                .toList();
        if (months.stream().anyMatch(m -> m == null)) {
            log.debug("OI check: could not parse all contract months for {} — {}", instrument, months);
            return null;
        }

        String activeMonth = contractRegistry.getContractMonth(instrument).orElse(null);

        // ── Step 2: Cold Start — registry empty, self-initialize ──
        if (activeMonth == null) {
            return handleColdStart(instrument, upcoming, months);
        }

        // ── Step 3: Dynamic Matching — find active month in IBKR list ──
        return handleDynamicMatch(instrument, upcoming, months, activeMonth);
    }

    /**
     * Cold Start: no active contract in registry. Compare OI of the first two IBKR
     * contracts and initialize the registry with the one that has the highest OI
     * (= where liquidity currently sits).
     */
    private RolloverRecommendation handleColdStart(Instrument instrument,
                                                    List<IbGatewayResolvedContract> upcoming,
                                                    List<String> months) {
        String month0 = months.get(0);
        String month1 = months.get(1);

        OptionalLong oi0 = openInterestProvider.fetchOpenInterest(instrument, month0);
        OptionalLong oi1 = openInterestProvider.fetchOpenInterest(instrument, month1);

        if (oi0.isEmpty() || oi1.isEmpty()) {
            log.warn("OI cold start: {} — OI unavailable for {} and/or {}, defaulting to front month {}",
                    instrument, month0, month1, month0);
            contractRegistry.initialize(instrument, month0);
            return null;
        }

        String selected = oi1.getAsLong() > oi0.getAsLong() ? month1 : month0;
        contractRegistry.initialize(instrument, selected);
        log.info("OI cold start: {} — initialized to {} ({}={}, {}={})",
                instrument, selected, month0, oi0.getAsLong(), month1, oi1.getAsLong());
        return null;
    }

    /**
     * Dynamic Matching: find the active contract month in IBKR's sorted list,
     * then compare its OI with the next contract in line.
     * This avoids the hardcoded index[0]/index[1] assumption that causes
     * spurious re-rollovers when the old contract is still listed by IBKR.
     */
    private RolloverRecommendation handleDynamicMatch(Instrument instrument,
                                                       List<IbGatewayResolvedContract> upcoming,
                                                       List<String> months,
                                                       String activeMonth) {
        int currentIndex = months.indexOf(activeMonth);

        if (currentIndex < 0) {
            // Active month not in IBKR list — likely already expired. The oldest
            // available contract becomes the candidate. Compare index 0 vs 1.
            log.warn("OI check: {} active month {} not found in IBKR list {} — comparing first two",
                    instrument, activeMonth, months);
            currentIndex = 0;
        }

        if (currentIndex >= months.size() - 1) {
            log.debug("OI check: {} — active month {} is the last available contract, nothing to compare",
                    instrument, activeMonth);
            return null;
        }

        String currentMonth = months.get(currentIndex);
        String nextMonth    = months.get(currentIndex + 1);

        log.info("OI check: {} — active contract {} found at IBKR index {}, comparing with index {} ({})",
                instrument, currentMonth, currentIndex, currentIndex + 1, nextMonth);

        // Fetch OI for both
        OptionalLong currentOI = openInterestProvider.fetchOpenInterest(instrument, currentMonth);
        OptionalLong nextOI    = openInterestProvider.fetchOpenInterest(instrument, nextMonth);

        if (currentOI.isEmpty() || nextOI.isEmpty()) {
            log.debug("OI check: {} — OI data unavailable ({} / {})", instrument, currentMonth, nextMonth);
            return null;
        }

        // Evaluate the domain rule
        OpenInterestSnapshot currentSnap = new OpenInterestSnapshot(currentMonth, currentOI.getAsLong());
        OpenInterestSnapshot nextSnap    = new OpenInterestSnapshot(nextMonth, nextOI.getAsLong());
        RolloverRecommendation recommendation = OpenInterestRolloverRule.evaluate(instrument, currentSnap, nextSnap);

        log.info("OI check: {} — current {} OI={}, next {} OI={} → {}",
                instrument, currentMonth, currentOI.getAsLong(), nextMonth, nextOI.getAsLong(), recommendation.action());

        if (recommendation.action() == RolloverRecommendation.Action.RECOMMEND_ROLL) {
            if (autoConfirm) {
                rolloverDetectionService.confirmRollover(instrument, nextMonth);
                log.info("OI auto-rollover: {} rolled from {} to {}", instrument, currentMonth, nextMonth);
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

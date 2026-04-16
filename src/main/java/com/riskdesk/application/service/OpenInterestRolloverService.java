package com.riskdesk.application.service;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.OpenInterestRolloverRule;
import com.riskdesk.domain.contract.OpenInterestRolloverRule.OpenInterestSnapshot;
import com.riskdesk.domain.contract.RolloverRecommendation;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.AssetClass;
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
    private final double                    autoConfirmOiRatio;
    private final long                      autoConfirmMinCurrentOi;

    public OpenInterestRolloverService(ActiveContractRegistry contractRegistry,
                                       IbGatewayContractResolver contractResolver,
                                       OpenInterestProvider openInterestProvider,
                                       IbkrProperties ibkrProperties,
                                       RolloverDetectionService rolloverDetectionService,
                                       SimpMessagingTemplate messagingTemplate,
                                       @Value("${riskdesk.rollover.auto-confirm:false}") boolean autoConfirm,
                                       @Value("${riskdesk.rollover.auto-confirm-oi-ratio:2.0}") double autoConfirmOiRatio,
                                       @Value("${riskdesk.rollover.auto-confirm-min-current-oi:100}") long autoConfirmMinCurrentOi) {
        this.contractRegistry       = contractRegistry;
        this.contractResolver       = contractResolver;
        this.openInterestProvider   = openInterestProvider;
        this.ibkrProperties         = ibkrProperties;
        this.rolloverDetectionService = rolloverDetectionService;
        this.messagingTemplate      = messagingTemplate;
        this.autoConfirm            = autoConfirm;
        this.autoConfirmOiRatio     = autoConfirmOiRatio;
        this.autoConfirmMinCurrentOi = autoConfirmMinCurrentOi;
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
        if (activeMonth == null) {
            // Defensive: ActiveContractRegistryInitializer runs at @Order(1) and always sets
            // a value (IBKR / DB / properties cascade). Hitting this branch means that
            // bootstrap silently failed for this instrument — log and skip instead of guessing.
            log.warn("OI check: {} has no active contract in registry — skipping (bootstrap likely failed)",
                instrument);
            return null;
        }

        // ── Step 2: Dynamic Matching — find active month in IBKR list ──
        return handleDynamicMatch(instrument, upcoming, months, activeMonth);
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
            boolean actuallyAutoConfirmed = false;
            if (autoConfirm && shouldAutoConfirm(instrument, currentOI.getAsLong(), nextOI.getAsLong(),
                                                 currentMonth, nextMonth)) {
                rolloverDetectionService.confirmRollover(instrument, nextMonth);
                actuallyAutoConfirmed = true;
                log.info("OI auto-rollover: {} rolled from {} to {} (currentOI={}, nextOI={}, ratio={})",
                    instrument, currentMonth, nextMonth,
                    currentOI.getAsLong(), nextOI.getAsLong(),
                    ratioStr(nextOI.getAsLong(), currentOI.getAsLong()));
            }
            pushOiRolloverAlert(recommendation, actuallyAutoConfirmed);
        }

        return recommendation;
    }

    /**
     * Gates auto-confirmation behind three safeguards, learned from the 2026-04-10
     * MCL incident where a premature OI-based rollover caused intraday candle
     * persistence to stall on a less liquid contract month:
     *
     *   1. ENERGY veto. For MCL (and any future ENERGY product) the relation
     *      between IBKR's {@code lastTradeDateOrContractMonth} and the active
     *      delivery month is offset by one month (see
     *      {@link com.riskdesk.infrastructure.marketdata.ibkr.ActiveContractRegistryInitializer#selectByOi}).
     *      An in-flight {@code nextOI > currentOI} shift therefore doesn't mean
     *      "time to roll"; it's normal as delivery approaches. We still emit the
     *      WebSocket alert so operators can decide via {@code POST /api/rollover/confirm}.
     *
     *   2. Minimum current OI. Sub-threshold values usually indicate a stale or
     *      missing snapshot rather than genuine liquidity migration.
     *
     *   3. Clear dominance ratio. Only auto-confirm when next OI is at least
     *      {@code autoConfirmOiRatio}× current OI. Small oscillations around
     *      parity (~1.1×) happened historically for MNQ and produced subscription
     *      churn without a real liquidity move.
     */
    private boolean shouldAutoConfirm(Instrument instrument,
                                      long currentOi,
                                      long nextOi,
                                      String currentMonth,
                                      String nextMonth) {
        if (instrument.assetClass() == AssetClass.ENERGY) {
            log.info("OI auto-rollover suppressed for {} ({} → {}): ENERGY asset class — "
                + "IBKR expiry-month semantics make OI-shift unreliable for auto-roll; "
                + "operator must confirm manually.",
                instrument, currentMonth, nextMonth);
            return false;
        }
        if (currentOi < autoConfirmMinCurrentOi) {
            log.info("OI auto-rollover suppressed for {} ({} → {}): current OI {} below minimum {} — "
                + "likely stale data, not a true liquidity shift.",
                instrument, currentMonth, nextMonth, currentOi, autoConfirmMinCurrentOi);
            return false;
        }
        double ratio = (double) nextOi / Math.max(currentOi, 1L);
        if (ratio < autoConfirmOiRatio) {
            log.info("OI auto-rollover suppressed for {} ({} → {}): OI ratio {} below threshold {} "
                + "(currentOI={}, nextOI={}).",
                instrument, currentMonth, nextMonth,
                ratioStr(nextOi, currentOi), autoConfirmOiRatio, currentOi, nextOi);
            return false;
        }
        return true;
    }

    private static String ratioStr(long numerator, long denominator) {
        if (denominator <= 0) return "∞";
        return String.format("%.2fx", (double) numerator / denominator);
    }

    /**
     * Publishes the OI rollover event to the frontend.
     *
     * <p>{@code autoConfirmed} reflects the <em>actual</em> outcome of this check —
     * true only when {@code confirmRollover()} was called. It must not track the
     * {@code riskdesk.rollover.auto-confirm} config flag, since the suppression
     * paths (ENERGY veto, below ratio / min-OI) skip the confirmation while
     * auto-confirm is enabled. Reporting the config flag would mislead the UI
     * into hiding the manual-confirm prompt for events that still need operator
     * action.</p>
     */
    private void pushOiRolloverAlert(RolloverRecommendation rec, boolean autoConfirmed) {
        try {
            messagingTemplate.convertAndSend("/topic/rollover", Map.of(
                "type",          "OI_ROLLOVER",
                "instrument",    rec.instrument().name(),
                "currentMonth",  rec.currentMonth(),
                "nextMonth",     rec.nextMonth(),
                "currentOI",     rec.currentOI(),
                "nextOI",        rec.nextOI(),
                "action",        rec.action().name(),
                "autoConfirmed", autoConfirmed
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

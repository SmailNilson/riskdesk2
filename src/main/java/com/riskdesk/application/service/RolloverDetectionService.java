package com.riskdesk.application.service;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.RolloverCandidate;
import com.riskdesk.domain.contract.RolloverDecision;
import com.riskdesk.domain.contract.RolloverStatus;
import com.riskdesk.domain.marketdata.port.ContractCalendar;
import com.riskdesk.domain.marketdata.port.VolumeProvider;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.riskdesk.domain.shared.TradingSessionResolver;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitors contract expiry and liquidity for automatic rollover.
 *
 * <h3>Expiry-based detection</h3>
 * Runs every 6 hours.  Publishes WARNING (≤7 days) or CRITICAL (≤3 days)
 * alerts via WebSocket so the frontend banner lights up.
 *
 * <h3>Volume-based auto-rollover</h3>
 * Runs daily at 15:00 ET (peak CME volume) and 2 minutes after startup.
 * Compares front-month vs next-month volume via IBKR snapshots.
 * If Vol(next) ≥ Vol(front), the registry is updated automatically.
 *
 * <h3>Hard expiry guard</h3>
 * {@link #assertNotNearExpiry(Instrument)} throws when the active contract
 * expires within {@code riskdesk.rollover.hard-expiry-days} (default 2).
 * Called by {@code ExecutionManagerService} before opening any position.
 */
@Service
public class RolloverDetectionService {

    private static final Logger log = LoggerFactory.getLogger(RolloverDetectionService.class);

    private static final int WARNING_DAYS  = 7;
    private static final int CRITICAL_DAYS = 3;

    private final ActiveContractRegistry    contractRegistry;
    private final IbGatewayContractResolver resolver;
    private final ContractCalendar          contractCalendar;
    private final VolumeProvider            volumeProvider;
    private final IbkrProperties            ibkrProperties;
    private final SimpMessagingTemplate     messagingTemplate;

    @Value("${riskdesk.rollover.auto-enabled:true}")
    private boolean autoRolloverEnabled;

    @Value("${riskdesk.rollover.hard-expiry-days:2}")
    private int hardExpiryDays;

    public RolloverDetectionService(ActiveContractRegistry contractRegistry,
                                    IbGatewayContractResolver resolver,
                                    ContractCalendar contractCalendar,
                                    VolumeProvider volumeProvider,
                                    IbkrProperties ibkrProperties,
                                    SimpMessagingTemplate messagingTemplate) {
        this.contractRegistry  = contractRegistry;
        this.resolver          = resolver;
        this.contractCalendar  = contractCalendar;
        this.volumeProvider    = volumeProvider;
        this.ibkrProperties    = ibkrProperties;
        this.messagingTemplate = messagingTemplate;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public Map<String, RolloverInfo> getCurrentStatus() {
        Map<String, RolloverInfo> result = new LinkedHashMap<>();
        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            result.put(instrument.name(), computeInfo(instrument));
        }
        return result;
    }

    public void confirmRollover(Instrument instrument, String contractMonth) {
        contractRegistry.confirmRollover(instrument, contractMonth);
        resolver.clearCache();
    }

    /**
     * Throws {@link IllegalStateException} if the active contract for the
     * given instrument expires within {@code hardExpiryDays}.
     * Called before opening any position.
     */
    public void assertNotNearExpiry(Instrument instrument) {
        RolloverInfo info = computeInfo(instrument);
        if (info.expiryDate() != null && info.daysToExpiry() <= hardExpiryDays) {
            throw new IllegalStateException(
                "Cannot open position on %s: contract %s expires in %d day(s) (hard limit: %d)"
                    .formatted(instrument, info.contractMonth(), info.daysToExpiry(), hardExpiryDays));
        }
    }

    /**
     * Evaluates whether the next-month contract is more liquid than the
     * current front-month.  Pure function — does not mutate state.
     */
    public RolloverDecision evaluateVolumeRollover(Instrument instrument) {
        List<RolloverCandidate> candidates = contractCalendar.nearestContracts(instrument, 2);
        if (candidates.isEmpty()) {
            return RolloverDecision.hold(instrument, null);
        }
        if (candidates.size() < 2) {
            return RolloverDecision.hold(instrument, candidates.get(0));
        }

        RolloverCandidate front = candidates.get(0)
            .withVolume(volumeProvider.volumeFor(instrument, candidates.get(0).contractMonth()));
        RolloverCandidate next = candidates.get(1)
            .withVolume(volumeProvider.volumeFor(instrument, candidates.get(1).contractMonth()));

        boolean shouldRoll = next.volume() > 0 && next.isMoreLiquidThan(front);
        return new RolloverDecision(instrument, front, next, shouldRoll);
    }

    // ── Scheduled: expiry-based alerts (every 6 hours) ───────────────────────

    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void checkRollovers() {
        if (!ibkrProperties.isEnabled()) return;

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            RolloverInfo info = computeInfo(instrument);
            if (info.status() == RolloverStatus.WARNING || info.status() == RolloverStatus.CRITICAL) {
                log.warn("Rollover {} — {} {} ({} days to expiry {})",
                    info.status(), instrument, info.contractMonth(), info.daysToExpiry(), info.expiryDate());
                pushAlert(info);
            }
        }
    }

    // ── Scheduled: volume-based auto-rollover ────────────────────────────────
    // Runs daily at 15:00 ET (peak CME volume) + 2 minutes after startup.

    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "America/New_York")
    @Scheduled(initialDelay = 2 * 60 * 1000L, fixedDelay = Long.MAX_VALUE)
    public void checkVolumeRollover() {
        if (!ibkrProperties.isEnabled() || !autoRolloverEnabled) return;

        log.info("Volume rollover check starting for all instruments");

        Arrays.stream(Instrument.values())
            .map(this::evaluateVolumeRollover)
            .filter(RolloverDecision::shouldRoll)
            .forEach(this::executeAutoRollover);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void executeAutoRollover(RolloverDecision decision) {
        Instrument instrument = decision.instrument();
        String oldMonth = contractRegistry.getContractMonth(instrument).orElse("?");
        String newMonth = decision.next().contractMonth();

        contractRegistry.confirmRollover(instrument, newMonth);
        resolver.clearCache();

        log.warn("AUTO_ROLLOVER {} : {} → {} | front vol={} next vol={}",
            instrument, oldMonth, newMonth,
            decision.front().volume(), decision.next().volume());

        pushAlert(new RolloverInfo(
            instrument.name(), newMonth, decision.next().expiry() != null ? decision.next().expiry().toString() : null,
            decision.next().daysToExpiry(), RolloverStatus.AUTO_ROLLED));
    }

    private RolloverInfo computeInfo(Instrument instrument) {
        String contractMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        if (contractMonth == null) {
            return RolloverInfo.unknown(instrument.name());
        }

        LocalDate expiry = resolveExpiry(instrument);
        if (expiry == null) {
            return new RolloverInfo(instrument.name(), contractMonth, null, -1, RolloverStatus.STABLE);
        }

        long daysToExpiry = LocalDate.now(TradingSessionResolver.CME_ZONE).until(expiry, ChronoUnit.DAYS);
        RolloverStatus status = statusFor(daysToExpiry);
        return new RolloverInfo(instrument.name(), contractMonth, expiry.toString(), daysToExpiry, status);
    }

    private LocalDate resolveExpiry(Instrument instrument) {
        try {
            return resolver.resolve(instrument)
                .map(r -> r.contract().lastTradeDateOrContractMonth())
                .map(this::parseExpiry)
                .orElse(null);
        } catch (Exception e) {
            log.debug("RolloverDetectionService: could not resolve expiry for {} — {}", instrument, e.getMessage());
            return null;
        }
    }

    private LocalDate parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        try {
            if (digits.length() >= 8) {
                return LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
            }
            if (digits.length() == 6) {
                return LocalDate.of(
                    Integer.parseInt(digits.substring(0, 4)),
                    Integer.parseInt(digits.substring(4, 6)),
                    1
                ).withDayOfMonth(1).plusMonths(1).minusDays(1);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private RolloverStatus statusFor(long daysToExpiry) {
        if (daysToExpiry <= CRITICAL_DAYS) return RolloverStatus.CRITICAL;
        if (daysToExpiry <= WARNING_DAYS)  return RolloverStatus.WARNING;
        return RolloverStatus.STABLE;
    }

    private void pushAlert(RolloverInfo info) {
        try {
            messagingTemplate.convertAndSend("/topic/rollover", Map.of(
                "instrument",    info.instrument(),
                "contractMonth", info.contractMonth() != null ? info.contractMonth() : "",
                "expiryDate",    info.expiryDate()    != null ? info.expiryDate()    : "",
                "daysToExpiry",  info.daysToExpiry(),
                "status",        info.status().name()
            ));
        } catch (Exception e) {
            log.debug("RolloverDetectionService: WebSocket push failed — {}", e.getMessage());
        }
    }

    public record RolloverInfo(
        String instrument,
        String contractMonth,
        String expiryDate,
        long   daysToExpiry,
        RolloverStatus status
    ) {
        static RolloverInfo unknown(String instrument) {
            return new RolloverInfo(instrument, null, null, -1, RolloverStatus.STABLE);
        }
    }
}

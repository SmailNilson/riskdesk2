package com.riskdesk.application.service;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.RolloverStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Periodically checks expiry dates for each active contract and publishes
 * a WARNING or CRITICAL status when the trader needs to act.
 *
 * Detection runs every 6 hours. When status is WARNING or CRITICAL,
 * a WebSocket push is sent to /topic/rollover so the frontend banner lights up.
 *
 * The trader MUST confirm the rollover explicitly via POST /api/rollover/confirm.
 * This service NEVER changes the active contract automatically.
 */
@Service
public class RolloverDetectionService {

    private static final Logger log = LoggerFactory.getLogger(RolloverDetectionService.class);

    private static final int WARNING_DAYS  = 7;
    private static final int CRITICAL_DAYS = 3;

    private final ActiveContractRegistry    contractRegistry;
    private final IbGatewayContractResolver resolver;
    private final IbkrProperties            ibkrProperties;
    private final SimpMessagingTemplate     messagingTemplate;

    public RolloverDetectionService(ActiveContractRegistry contractRegistry,
                                    IbGatewayContractResolver resolver,
                                    IbkrProperties ibkrProperties,
                                    SimpMessagingTemplate messagingTemplate) {
        this.contractRegistry  = contractRegistry;
        this.resolver          = resolver;
        this.ibkrProperties    = ibkrProperties;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Returns the current rollover status snapshot for all instruments.
     * Called by RolloverController on GET /api/rollover/status.
     */
    public Map<String, RolloverInfo> getCurrentStatus() {
        Map<String, RolloverInfo> result = new LinkedHashMap<>();
        for (Instrument instrument : Instrument.values()) {
            result.put(instrument.name(), computeInfo(instrument));
        }
        return result;
    }

    /** Scheduled check every 6 hours (with 1-minute initial delay after startup). */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void checkRollovers() {
        if (!ibkrProperties.isEnabled()) return;

        for (Instrument instrument : Instrument.values()) {
            RolloverInfo info = computeInfo(instrument);
            if (info.status() == RolloverStatus.WARNING || info.status() == RolloverStatus.CRITICAL) {
                log.warn("Rollover {} — {} {} ({} days to expiry {})",
                    info.status(), instrument, info.contractMonth(), info.daysToExpiry(), info.expiryDate());
                pushAlert(info);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private RolloverInfo computeInfo(Instrument instrument) {
        String contractMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        if (contractMonth == null) {
            return RolloverInfo.unknown(instrument.name());
        }

        LocalDate expiry = resolveExpiry(instrument);
        if (expiry == null) {
            return new RolloverInfo(instrument.name(), contractMonth, null, -1, RolloverStatus.STABLE);
        }

        long daysToExpiry = LocalDate.now().until(expiry, ChronoUnit.DAYS);
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
                // YYYYMM → last day of month as conservative estimate
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

    // ─────────────────────────────────────────────────────────────────────────

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

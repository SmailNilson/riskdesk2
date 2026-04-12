package com.riskdesk.application.service;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.RolloverStatus;
import com.riskdesk.domain.contract.event.ContractRolloverEvent;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayResolvedContract;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

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
    private final OpenInterestProvider      openInterestProvider;
    private final IbkrProperties            ibkrProperties;
    private final SimpMessagingTemplate     messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final HistoricalDataService     historicalDataService;
    private final int                       calendarDaysThreshold;
    private final boolean                   autoConfirm;

    public RolloverDetectionService(ActiveContractRegistry contractRegistry,
                                    IbGatewayContractResolver resolver,
                                    OpenInterestProvider openInterestProvider,
                                    IbkrProperties ibkrProperties,
                                    SimpMessagingTemplate messagingTemplate,
                                    ApplicationEventPublisher eventPublisher,
                                    HistoricalDataService historicalDataService,
                                    @Value("${riskdesk.rollover.calendar-days-threshold:32}") int calendarDaysThreshold,
                                    @Value("${riskdesk.rollover.auto-confirm:false}") boolean autoConfirm) {
        this.contractRegistry       = contractRegistry;
        this.resolver               = resolver;
        this.openInterestProvider   = openInterestProvider;
        this.ibkrProperties         = ibkrProperties;
        this.messagingTemplate      = messagingTemplate;
        this.eventPublisher         = eventPublisher;
        this.historicalDataService   = historicalDataService;
        this.calendarDaysThreshold  = calendarDaysThreshold;
        this.autoConfirm            = autoConfirm;
    }

    /**
     * Returns the current rollover status snapshot for all instruments.
     * Called by RolloverController on GET /api/rollover/status.
     */
    public Map<String, RolloverInfo> getCurrentStatus() {
        Map<String, RolloverInfo> result = new LinkedHashMap<>();
        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            result.put(instrument.name(), computeInfo(instrument));
        }
        return result;
    }

    public void confirmRollover(Instrument instrument, String contractMonth) {
        String oldMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        contractRegistry.confirmRollover(instrument, contractMonth);
        resolver.refreshToMonth(instrument, contractMonth);

        if (oldMonth != null && !oldMonth.equals(contractMonth)) {
            ContractRolloverEvent event = new ContractRolloverEvent(
                    instrument, oldMonth, contractMonth, Instant.now());
            log.info("Rollover confirmed: {} {} → {} — publishing ContractRolloverEvent",
                    instrument, oldMonth, contractMonth);
            eventPublisher.publishEvent(event);
        }
    }

    /** Scheduled check every 6 hours (with 1-minute initial delay after startup). */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void checkRollovers() {
        if (!ibkrProperties.isEnabled()) return;

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            RolloverInfo info = computeInfo(instrument);
            if (info.status() == RolloverStatus.WARNING || info.status() == RolloverStatus.CRITICAL) {
                log.info("Rollover {} — {} {} ({} days to expiry {})",
                    info.status(), instrument, info.contractMonth(), info.daysToExpiry(), info.expiryDate());
                // Only push alerts to frontend when auto-confirm is off (manual mode)
                if (!autoConfirm) {
                    pushAlert(info);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rollover detection strategy:
     *   1. OI comparison (2 contracts) — if next OI > current OI → RECOMMEND_ROLL
     *   2. OI empty → calendar fallback: WARNING if ≤ 7 days, CRITICAL if ≤ 3 days
     *   3. Otherwise → STABLE
     *
     * Note: the 32-day calendarDaysThreshold is used by ActiveContractRegistryInitializer
     * for pre-rolling at startup. For user-facing alerts, we use WARNING_DAYS / CRITICAL_DAYS
     * to avoid false positives on freshly-rolled contracts.
     */
    private RolloverInfo computeInfo(Instrument instrument) {
        String currentMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        if (currentMonth == null) {
            return RolloverInfo.unknown(instrument.name());
        }

        // Get 2 contracts from IBKR for OI comparison
        List<IbGatewayResolvedContract> contracts = resolver.resolveNextContracts(instrument);
        if (contracts.size() < 2) {
            return new RolloverInfo(instrument.name(), currentMonth, null, -1, RolloverStatus.STABLE);
        }

        String frontMonth = normalizeMonth(contracts.get(0).contract().lastTradeDateOrContractMonth());
        String nextMonth  = normalizeMonth(contracts.get(1).contract().lastTradeDateOrContractMonth());
        if (frontMonth == null || nextMonth == null) {
            return new RolloverInfo(instrument.name(), currentMonth, null, -1, RolloverStatus.STABLE);
        }

        // If we are already on or past the next month (post-rollover), no need to warn.
        if (currentMonth.compareTo(nextMonth) >= 0) {
            return new RolloverInfo(instrument.name(), currentMonth, null, -1, RolloverStatus.STABLE);
        }

        // Strategy 1: OI comparison — next OI > current OI → recommend roll
        OptionalLong currentOI = openInterestProvider.fetchOpenInterest(instrument, frontMonth);
        OptionalLong nextOI    = openInterestProvider.fetchOpenInterest(instrument, nextMonth);

        if (currentOI.isPresent() && nextOI.isPresent()) {
            if (nextOI.getAsLong() > currentOI.getAsLong()) {
                log.info("Rollover OI: {} — next {} OI={} > current {} OI={} → RECOMMEND_ROLL",
                    instrument, nextMonth, nextOI.getAsLong(), frontMonth, currentOI.getAsLong());
                return new RolloverInfo(instrument.name(), currentMonth, null,
                    -1, RolloverStatus.WARNING);
            }
            return new RolloverInfo(instrument.name(), currentMonth, null, -1, RolloverStatus.STABLE);
        }

        // Strategy 2: OI empty → calendar fallback using WARNING_DAYS / CRITICAL_DAYS
        // (not calendarDaysThreshold which is for pre-roll at startup, too aggressive for alerts)
        if (!isSerialMonth(instrument, currentMonth)) {
            long daysToContractMonth = daysToFirstOfMonth(currentMonth);
            if (daysToContractMonth >= 0 && daysToContractMonth <= CRITICAL_DAYS) {
                log.info("Rollover calendar CRITICAL: {} — {} expires within {}d (OI unavailable)",
                    instrument, currentMonth, daysToContractMonth);
                return new RolloverInfo(instrument.name(), currentMonth, null,
                    daysToContractMonth, RolloverStatus.CRITICAL);
            }
            if (daysToContractMonth >= 0 && daysToContractMonth <= WARNING_DAYS) {
                log.info("Rollover calendar WARNING: {} — {} expires within {}d (OI unavailable)",
                    instrument, currentMonth, daysToContractMonth);
                return new RolloverInfo(instrument.name(), currentMonth, null,
                    daysToContractMonth, RolloverStatus.WARNING);
            }
        }

        return new RolloverInfo(instrument.name(), currentMonth, null, -1, RolloverStatus.STABLE);
    }

    /**
     * Returns true if the contract month is a serial (non-quarterly) for E6/MNQ.
     * E6 and MNQ only trade quarterly: Mar(3), Jun(6), Sep(9), Dec(12).
     * Serial months (Jan, Feb, Apr, May, Jul, Aug, Oct, Nov) are ignored.
     */
    private boolean isSerialMonth(Instrument instrument, String contractMonth) {
        if (instrument != Instrument.E6 && instrument != Instrument.MNQ) {
            return false; // MCL/MGC are monthly — no serials
        }
        if (contractMonth == null || contractMonth.length() < 6) return false;
        int m = Integer.parseInt(contractMonth.substring(4, 6));
        return m % 3 != 0;
    }

    private long daysToFirstOfMonth(String contractMonth) {
        if (contractMonth == null || contractMonth.length() < 6) return -1;
        try {
            int year = Integer.parseInt(contractMonth.substring(0, 4));
            int month = Integer.parseInt(contractMonth.substring(4, 6));
            LocalDate firstDay = LocalDate.of(year, month, 1);
            return ChronoUnit.DAYS.between(LocalDate.now(), firstDay);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String normalizeMonth(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
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

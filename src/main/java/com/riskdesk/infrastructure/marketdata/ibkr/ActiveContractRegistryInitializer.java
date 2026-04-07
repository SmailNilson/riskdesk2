package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Initializes the ActiveContractRegistry at startup (Order 1 — before HistoricalDataService).
 *
 * Resolution strategy (in order):
 *   1. Ask IBKR for the front-month contract via IbGatewayContractResolver.refresh().
 *   2. Compare OI between front and next month — pick the one with higher OI.
 *   3. If OI is unavailable, apply calendar rule: if front-month expires within
 *      {@code calendarDaysThreshold} days, auto-roll to next contract.
 *   4. If IBKR is unavailable or disabled, fall back to application properties.
 */
@Component
@Order(1)
public class ActiveContractRegistryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ActiveContractRegistryInitializer.class);

    private final ActiveContractRegistry    registry;
    private final IbGatewayContractResolver resolver;
    private final IbkrProperties            ibkrProperties;
    private final OpenInterestProvider      openInterestProvider;

    @Value("${riskdesk.active-contracts.MCL:202505}")
    private String fallbackMcl;

    @Value("${riskdesk.active-contracts.MGC:202506}")
    private String fallbackMgc;

    @Value("${riskdesk.active-contracts.MNQ:202506}")
    private String fallbackMnq;

    @Value("${riskdesk.active-contracts.E6:202506}")
    private String fallbackE6;

    @Value("${riskdesk.rollover.calendar-days-threshold:32}")
    private int calendarDaysThreshold;

    public ActiveContractRegistryInitializer(ActiveContractRegistry registry,
                                             IbGatewayContractResolver resolver,
                                             IbkrProperties ibkrProperties,
                                             OpenInterestProvider openInterestProvider) {
        this.registry             = registry;
        this.resolver             = resolver;
        this.ibkrProperties       = ibkrProperties;
        this.openInterestProvider = openInterestProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<Instrument, String> fallbacks = Map.of(
            Instrument.MCL, fallbackMcl,
            Instrument.MGC, fallbackMgc,
            Instrument.MNQ, fallbackMnq,
            Instrument.E6,  fallbackE6
        );

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            String resolved = ibkrProperties.isEnabled()
                ? resolveFromIbkr(instrument)
                : null;

            if (resolved != null) {
                registry.initialize(instrument, resolved);
                log.info("ActiveContractRegistry: {} → {} (IBKR)", instrument, resolved);
            } else {
                String fallback = fallbacks.get(instrument);
                // Apply calendar rule to fallback too
                fallback = applyCalendarRollIfNeeded(instrument, fallback);
                registry.initialize(instrument, fallback);
                log.warn("ActiveContractRegistry: {} → {} (fallback — IBKR {} or unavailable)",
                    instrument, fallback,
                    ibkrProperties.isEnabled() ? "returned empty" : "disabled");
            }
        }

        log.info("ActiveContractRegistry ready: {}", registry.snapshot());
    }

    private String resolveFromIbkr(Instrument instrument) {
        try {
            List<IbGatewayResolvedContract> topTwo = resolver.resolveTopTwo(instrument);
            if (topTwo.isEmpty()) return null;

            IbGatewayResolvedContract selected;
            if (topTwo.size() >= 2) {
                String frontMonth = normalizeMonth(topTwo.get(0).contract().lastTradeDateOrContractMonth());
                String nextMonth  = normalizeMonth(topTwo.get(1).contract().lastTradeDateOrContractMonth());

                OptionalLong frontOI = frontMonth != null
                    ? openInterestProvider.fetchOpenInterest(instrument, frontMonth)
                    : OptionalLong.empty();
                OptionalLong nextOI = nextMonth != null
                    ? openInterestProvider.fetchOpenInterest(instrument, nextMonth)
                    : OptionalLong.empty();

                if (nextOI.isPresent() && frontOI.isPresent() && nextOI.getAsLong() > frontOI.getAsLong()) {
                    // OI-based roll: next month has more liquidity
                    log.info("ActiveContractRegistry: {} OI-roll → {} (OI={}) over {} (OI={})",
                        instrument, nextMonth, nextOI.getAsLong(), frontMonth, frontOI.getAsLong());
                    selected = topTwo.get(1);
                } else if (frontOI.isEmpty() && nextOI.isEmpty() && isNearExpiry(frontMonth)) {
                    // Calendar fallback: OI unavailable + front-month near expiry → auto-roll
                    log.info("ActiveContractRegistry: {} calendar-roll → {} (OI unavailable, {} expires within {}d)",
                        instrument, nextMonth, frontMonth, calendarDaysThreshold);
                    selected = topTwo.get(1);
                } else {
                    selected = topTwo.get(0);
                }
            } else {
                selected = topTwo.get(0);
            }

            // Seed resolver cache so downstream resolve() uses the OI-selected contract
            resolver.setResolved(instrument, selected);

            return normalizeMonth(selected.contract().lastTradeDateOrContractMonth());
        } catch (Exception e) {
            log.debug("ActiveContractRegistryInitializer: IBKR resolution failed for {} — {}", instrument, e.getMessage());
            return null;
        }
    }

    /**
     * Returns true if the contract month is within {@code calendarDaysThreshold} days
     * of its first day (approximate expiry).
     * Example: contractMonth="202506", today=2026-05-05, first day=2026-06-01 → 27 days → true if threshold=32.
     */
    private boolean isNearExpiry(String contractMonth) {
        if (contractMonth == null || contractMonth.length() < 6) return false;
        try {
            int year = Integer.parseInt(contractMonth.substring(0, 4));
            int month = Integer.parseInt(contractMonth.substring(4, 6));
            LocalDate firstDayOfContractMonth = LocalDate.of(year, month, 1);
            long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), firstDayOfContractMonth);
            return daysUntil < calendarDaysThreshold;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Applies the calendar roll rule to a fallback contract month.
     * If the fallback is near expiry, computes the next contract month.
     */
    private String applyCalendarRollIfNeeded(Instrument instrument, String contractMonth) {
        if (!isNearExpiry(contractMonth)) return contractMonth;
        String next = nextContractMonth(instrument, contractMonth);
        log.info("ActiveContractRegistry: {} calendar-roll fallback → {} (was {} — expires within {}d)",
            instrument, next, contractMonth, calendarDaysThreshold);
        return next;
    }

    /**
     * Computes the next contract month based on instrument cycle.
     * E6/MNQ: quarterly (Mar, Jun, Sep, Dec)
     * MCL/MGC: monthly
     */
    static String nextContractMonth(Instrument instrument, String contractMonth) {
        if (contractMonth == null || contractMonth.length() < 6) return contractMonth;
        int year = Integer.parseInt(contractMonth.substring(0, 4));
        int month = Integer.parseInt(contractMonth.substring(4, 6));
        YearMonth ym = YearMonth.of(year, month);

        if (instrument == Instrument.E6 || instrument == Instrument.MNQ) {
            // Quarterly: Mar(3), Jun(6), Sep(9), Dec(12)
            do {
                ym = ym.plusMonths(1);
            } while (ym.getMonthValue() % 3 != 0);
        } else {
            // Monthly: MCL, MGC
            ym = ym.plusMonths(1);
        }

        return String.format("%04d%02d", ym.getYear(), ym.getMonthValue());
    }

    private static String normalizeMonth(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
    }
}

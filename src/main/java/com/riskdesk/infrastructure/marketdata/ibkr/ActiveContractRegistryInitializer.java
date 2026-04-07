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
            List<IbGatewayResolvedContract> contracts = resolver.resolveNextContracts(instrument);
            if (contracts.isEmpty()) return null;
            if (contracts.size() == 1) {
                resolver.setResolved(instrument, contracts.get(0));
                return normalizeMonth(contracts.get(0).contract().lastTradeDateOrContractMonth());
            }

            // Strategy 1: OI comparison — pick the contract with highest OI
            IbGatewayResolvedContract oiWinner = selectByOi(instrument, contracts);
            if (oiWinner != null) {
                resolver.setResolved(instrument, oiWinner);
                return normalizeMonth(oiWinner.contract().lastTradeDateOrContractMonth());
            }

            // Strategy 2: Volume comparison — pick the contract with highest volume
            IbGatewayResolvedContract volWinner = selectByVolume(instrument, contracts);
            if (volWinner != null) {
                resolver.setResolved(instrument, volWinner);
                return normalizeMonth(volWinner.contract().lastTradeDateOrContractMonth());
            }

            // Strategy 3: Calendar — if front-month near expiry, pick next
            String frontMonth = normalizeMonth(contracts.get(0).contract().lastTradeDateOrContractMonth());
            if (isNearExpiry(frontMonth) && contracts.size() >= 2) {
                IbGatewayResolvedContract next = contracts.get(1);
                String nextMonth = normalizeMonth(next.contract().lastTradeDateOrContractMonth());
                log.info("ActiveContractRegistry: {} calendar-roll → {} ({} expires within {}d, OI+volume unavailable)",
                    instrument, nextMonth, frontMonth, calendarDaysThreshold);
                resolver.setResolved(instrument, next);
                return nextMonth;
            }

            // Default: front-month
            resolver.setResolved(instrument, contracts.get(0));
            return normalizeMonth(contracts.get(0).contract().lastTradeDateOrContractMonth());
        } catch (Exception e) {
            log.debug("ActiveContractRegistryInitializer: IBKR resolution failed for {} — {}", instrument, e.getMessage());
            return null;
        }
    }

    /**
     * Compares OI across up to 3 contracts. Returns the one with highest OI, or null if all empty.
     */
    private IbGatewayResolvedContract selectByOi(Instrument instrument, List<IbGatewayResolvedContract> contracts) {
        IbGatewayResolvedContract best = null;
        long bestOi = -1;
        boolean anyOiPresent = false;

        for (IbGatewayResolvedContract c : contracts) {
            String month = normalizeMonth(c.contract().lastTradeDateOrContractMonth());
            OptionalLong oi = month != null ? openInterestProvider.fetchOpenInterest(instrument, month) : OptionalLong.empty();
            if (oi.isPresent()) {
                anyOiPresent = true;
                if (oi.getAsLong() > bestOi) {
                    bestOi = oi.getAsLong();
                    best = c;
                }
            }
        }

        if (best != null && anyOiPresent) {
            log.info("ActiveContractRegistry: {} OI-select → {} (OI={})",
                instrument, normalizeMonth(best.contract().lastTradeDateOrContractMonth()), bestOi);
        }
        return anyOiPresent ? best : null;
    }

    /**
     * Compares volume across up to 3 contracts. Returns the one with highest volume, or null if all empty.
     * Fallback when OI is unavailable (e.g. FX maintenance).
     */
    private IbGatewayResolvedContract selectByVolume(Instrument instrument, List<IbGatewayResolvedContract> contracts) {
        IbGatewayResolvedContract best = null;
        long bestVol = -1;
        boolean anyVolPresent = false;

        for (IbGatewayResolvedContract c : contracts) {
            String month = normalizeMonth(c.contract().lastTradeDateOrContractMonth());
            OptionalLong vol = month != null ? openInterestProvider.fetchVolume(instrument, month) : OptionalLong.empty();
            if (vol.isPresent()) {
                anyVolPresent = true;
                if (vol.getAsLong() > bestVol) {
                    bestVol = vol.getAsLong();
                    best = c;
                }
            }
        }

        if (best != null && anyVolPresent) {
            log.info("ActiveContractRegistry: {} volume-select → {} (vol={}, OI was unavailable)",
                instrument, normalizeMonth(best.contract().lastTradeDateOrContractMonth()), bestVol);
        }
        return anyVolPresent ? best : null;
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

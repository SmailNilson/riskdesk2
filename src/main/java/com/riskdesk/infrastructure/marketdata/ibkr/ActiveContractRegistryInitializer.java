package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.AssetClass;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Initializes the ActiveContractRegistry at startup (Order 1 — before HistoricalDataService).
 *
 * Resolution strategy (in order):
 *   1. Ask IBKR for available contracts via IbGatewayContractResolver.
 *   2. Compare OI across all contracts — pick the one with highest OI.
 *   3. If OI unavailable, compare volume — pick highest volume.
 *   4. If neither available, default to front-month (no calendar-roll).
 *   5. If IBKR is unavailable or disabled, fall back to application properties as-is.
 */
@Component
@Order(1)
public class ActiveContractRegistryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ActiveContractRegistryInitializer.class);

    private final ActiveContractRegistry    registry;
    private final IbGatewayContractResolver resolver;
    private final IbkrProperties            ibkrProperties;
    private final OpenInterestProvider      openInterestProvider;

    @Value("${riskdesk.active-contracts.MCL:202605}")
    private String fallbackMcl;

    @Value("${riskdesk.active-contracts.MGC:202506}")
    private String fallbackMgc;

    @Value("${riskdesk.active-contracts.MNQ:202506}")
    private String fallbackMnq;

    @Value("${riskdesk.active-contracts.E6:202506}")
    private String fallbackE6;

    // calendarDaysThreshold removed — caused Frankenstein charts by rolling the
    // fallback contract forward when IBKR was unavailable at startup.

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
                // Never calendar-roll the fallback — it's operator-configured and represents
                // the known active contract. Rolling it forward when IBKR is unavailable
                // caused Frankenstein charts (mixing contract price series).
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

            // Strategy 1: OI comparison — scan ALL available contracts (not just first 2)
            IbGatewayResolvedContract oiWinner = selectByOi(instrument, contracts);
            if (oiWinner != null) {
                resolver.setResolved(instrument, oiWinner);
                return normalizeMonth(oiWinner.contract().lastTradeDateOrContractMonth());
            }

            // Strategy 2: Volume comparison — scan ALL available contracts
            IbGatewayResolvedContract volWinner = selectByVolume(instrument, contracts);
            if (volWinner != null) {
                resolver.setResolved(instrument, volWinner);
                return normalizeMonth(volWinner.contract().lastTradeDateOrContractMonth());
            }

            // Default: use front-month when OI+volume are both unavailable.
            // No calendar-roll — without OI data, we cannot know where liquidity sits.
            String frontMonth = normalizeMonth(contracts.get(0).contract().lastTradeDateOrContractMonth());
            log.info("ActiveContractRegistry: {} defaulting to front-month {} (OI+volume unavailable)", instrument, frontMonth);
            resolver.setResolved(instrument, contracts.get(0));
            return frontMonth;
        } catch (Exception e) {
            log.debug("ActiveContractRegistryInitializer: IBKR resolution failed for {} — {}", instrument, e.getMessage());
            return null;
        }
    }

    /**
     * Compares OI across ALL available contracts.
     * Returns the contract with the highest OI, or null if OI is unavailable for all.
     *
     * Energy contracts (MCL) have a CME convention where the last trade date is one
     * month BEFORE the delivery month. IBKR's normalizeMonth("20260421") = "202604"
     * but that's the MAY contract. So when OI selects "202605" as the winner, it's
     * actually the JUNE contract. For MCL, we take the contract at index-1 (the one
     * that expires one month earlier = the real active delivery month).
     */
    private IbGatewayResolvedContract selectByOi(Instrument instrument, List<IbGatewayResolvedContract> contracts) {
        IbGatewayResolvedContract best = null;
        int bestIndex = -1;
        long bestOi = -1;
        boolean anyOiPresent = false;
        StringBuilder logDetail = new StringBuilder();

        for (int i = 0; i < contracts.size(); i++) {
            IbGatewayResolvedContract c = contracts.get(i);
            String month = normalizeMonth(c.contract().lastTradeDateOrContractMonth());
            OptionalLong oi = month != null ? openInterestProvider.fetchOpenInterest(instrument, month) : OptionalLong.empty();
            if (oi.isPresent()) {
                anyOiPresent = true;
                logDetail.append(month).append("=").append(oi.getAsLong()).append(" ");
                if (oi.getAsLong() > bestOi) {
                    bestOi = oi.getAsLong();
                    best = c;
                    bestIndex = i;
                }
            }
        }

        if (!anyOiPresent || best == null) return null;

        // Energy (MCL): IBKR lastTradeDateOrContractMonth gives the expiry month,
        // which is one month BEFORE the delivery month. The OI winner at index N
        // is actually the NEXT month's contract. The real active contract is at N-1.
        if (instrument.assetClass() == AssetClass.ENERGY && bestIndex > 0) {
            IbGatewayResolvedContract energyAdjusted = contracts.get(bestIndex - 1);
            log.info("ActiveContractRegistry: {} OI-select → index {} (OI={}, all: [{}]) — ENERGY adjust: using index {} (conId={}) instead of index {} (conId={})",
                instrument, bestIndex, bestOi, logDetail.toString().trim(),
                bestIndex - 1, energyAdjusted.contract().conid(),
                bestIndex, best.contract().conid());
            return energyAdjusted;
        }

        log.info("ActiveContractRegistry: {} OI-select → {} (OI={}, all: [{}])",
            instrument, normalizeMonth(best.contract().lastTradeDateOrContractMonth()),
            bestOi, logDetail.toString().trim());
        return best;
    }

    /**
     * Compares volume across ALL available contracts. Returns the one with highest volume,
     * or null if volume is unavailable for all. Fallback when OI is unavailable.
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
     * Computes the previous contract month based on instrument cycle.
     * E6/MNQ: quarterly (Mar, Jun, Sep, Dec)
     * MCL/MGC: monthly
     */
    static String previousContractMonth(Instrument instrument, String contractMonth) {
        if (contractMonth == null || contractMonth.length() < 6) return contractMonth;
        int year = Integer.parseInt(contractMonth.substring(0, 4));
        int month = Integer.parseInt(contractMonth.substring(4, 6));
        YearMonth ym = YearMonth.of(year, month);

        if (instrument == Instrument.E6 || instrument == Instrument.MNQ) {
            // Quarterly: Mar(3), Jun(6), Sep(9), Dec(12)
            do {
                ym = ym.minusMonths(1);
            } while (ym.getMonthValue() % 3 != 0);
        } else {
            // Monthly: MCL, MGC
            ym = ym.minusMonths(1);
        }

        return String.format("%04d%02d", ym.getYear(), ym.getMonthValue());
    }

    private static String normalizeMonth(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
    }
}

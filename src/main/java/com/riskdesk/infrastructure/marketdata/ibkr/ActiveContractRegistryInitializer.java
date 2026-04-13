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

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Initializes the ActiveContractRegistry at startup (Order 1 — before HistoricalDataService).
 *
 * Resolution strategy (in order):
 *   1. Ask IBKR for the front-month contract via IbGatewayContractResolver.refresh().
 *   2. If IBKR is unavailable or disabled, fall back to application properties
 *      (riskdesk.active-contracts.MCL, .MGC, .MNQ, .E6).
 *
 * This guarantees the registry is populated before any service fetches or tags candles.
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
                    log.info("ActiveContractRegistry: {} OI-roll → {} (OI={}) over {} (OI={})",
                        instrument, nextMonth, nextOI.getAsLong(), frontMonth, frontOI.getAsLong());
                    selected = topTwo.get(1);
                } else {
                    selected = topTwo.get(0);
                }
            } else {
                selected = topTwo.get(0);
            }

            String month = normalizeMonth(selected.contract().lastTradeDateOrContractMonth());
            if (month == null) {
                log.warn("ActiveContractRegistry: {} — normalizeMonth returned null for '{}', skipping",
                        instrument, selected.contract().lastTradeDateOrContractMonth());
                return null;
            }

            // Seed resolver cache so downstream resolve() uses the OI-selected contract
            resolver.setResolved(instrument, selected);

            return month;
        } catch (Exception e) {
            log.warn("ActiveContractRegistryInitializer: IBKR resolution failed for {} — {}", instrument, e.getMessage());
            return null;
        }
    }

    private static String normalizeMonth(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
    }
}

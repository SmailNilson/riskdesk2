package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Initializes the ActiveContractRegistry at startup (Order 1 — before HistoricalDataService).
 *
 * Resolution strategy (in order):
 *   1. Ask IBKR for the front-month contract via IbGatewayContractResolver.refresh().
 *   2. If IBKR is unavailable or disabled, fall back to application properties
 *      (riskdesk.active-contracts.MCL, .MGC, .MNQ, .E6, .DXY).
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

    @Value("${riskdesk.active-contracts.MCL:202505}")
    private String fallbackMcl;

    @Value("${riskdesk.active-contracts.MGC:202506}")
    private String fallbackMgc;

    @Value("${riskdesk.active-contracts.MNQ:202506}")
    private String fallbackMnq;

    @Value("${riskdesk.active-contracts.E6:202506}")
    private String fallbackE6;

    @Value("${riskdesk.active-contracts.DXY:202506}")
    private String fallbackDxy;

    public ActiveContractRegistryInitializer(ActiveContractRegistry registry,
                                             IbGatewayContractResolver resolver,
                                             IbkrProperties ibkrProperties) {
        this.registry       = registry;
        this.resolver       = resolver;
        this.ibkrProperties = ibkrProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<Instrument, String> fallbacks = Map.of(
            Instrument.MCL, fallbackMcl,
            Instrument.MGC, fallbackMgc,
            Instrument.MNQ, fallbackMnq,
            Instrument.E6,  fallbackE6,
            Instrument.DXY, fallbackDxy
        );

        for (Instrument instrument : Instrument.values()) {
            if (instrument.isSynthetic()) {
                String fallback = fallbacks.get(instrument);
                if (fallback != null) {
                    registry.initialize(instrument, fallback);
                }
                continue; // DXY is synthetic — no IBKR contract to resolve
            }

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
            return resolver.refresh(instrument)
                .map(resolved -> resolved.contract().lastTradeDateOrContractMonth())
                .map(raw -> {
                    // Normalize: strip non-digits, keep first 6 (YYYYMM)
                    String digits = raw.replaceAll("[^0-9]", "");
                    return digits.length() >= 6 ? digits.substring(0, 6) : null;
                })
                .filter(month -> month != null && month.matches("\\d{6}"))
                .orElse(null);
        } catch (Exception e) {
            log.debug("ActiveContractRegistryInitializer: IBKR resolution failed for {} — {}", instrument, e.getMessage());
            return null;
        }
    }
}

package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.RolloverCandidate;
import com.riskdesk.domain.marketdata.port.VolumeProvider;
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

/**
 * Initializes the ActiveContractRegistry at startup (Order 1 — before HistoricalDataService).
 *
 * Resolution strategy (in order):
 *   1. Ask IBKR for the front-month contract via IbGatewayContractResolver.refresh().
 *   2. Compare front-month vs next-month volume.  If the next-month is at least
 *      as liquid, use the next-month instead (volume-based smart rollover).
 *   3. If IBKR is unavailable or disabled, fall back to application properties.
 *
 * This guarantees the registry is populated with the <em>most liquid</em> contract
 * before any service fetches or tags candles.
 */
@Component
@Order(1)
public class ActiveContractRegistryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ActiveContractRegistryInitializer.class);

    private final ActiveContractRegistry    registry;
    private final IbGatewayContractResolver resolver;
    private final VolumeProvider            volumeProvider;
    private final IbkrProperties            ibkrProperties;

    @Value("${riskdesk.active-contracts.MCL:202605}")
    private String fallbackMcl;

    @Value("${riskdesk.active-contracts.MGC:202606}")
    private String fallbackMgc;

    @Value("${riskdesk.active-contracts.MNQ:202606}")
    private String fallbackMnq;

    @Value("${riskdesk.active-contracts.E6:202606}")
    private String fallbackE6;

    @Value("${riskdesk.rollover.auto-enabled:true}")
    private boolean autoRolloverEnabled;

    public ActiveContractRegistryInitializer(ActiveContractRegistry registry,
                                             IbGatewayContractResolver resolver,
                                             VolumeProvider volumeProvider,
                                             IbkrProperties ibkrProperties) {
        this.registry       = registry;
        this.resolver       = resolver;
        this.volumeProvider = volumeProvider;
        this.ibkrProperties = ibkrProperties;
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
                ? resolveWithVolumeCheck(instrument)
                : null;

            if (resolved != null) {
                registry.initialize(instrument, resolved);
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

    /**
     * Resolves the front-month from IBKR, then checks if the next-month has
     * more volume.  Returns the most liquid contract month.
     */
    private String resolveWithVolumeCheck(Instrument instrument) {
        String frontMonth = resolveFromIbkr(instrument);
        if (frontMonth == null) return null;

        if (!autoRolloverEnabled) {
            log.info("ActiveContractRegistry: {} → {} (IBKR, auto-rollover disabled)", instrument, frontMonth);
            return frontMonth;
        }

        // Check if next-month is more liquid
        List<RolloverCandidate> candidates = resolver.nearestContracts(instrument, 2);
        if (candidates.size() < 2) {
            log.info("ActiveContractRegistry: {} → {} (IBKR, single contract available)", instrument, frontMonth);
            return frontMonth;
        }

        RolloverCandidate front = candidates.get(0);
        RolloverCandidate next = candidates.get(1);

        long frontVol = volumeProvider.volumeFor(instrument, front.contractMonth()).orElse(0L);
        long nextVol = volumeProvider.volumeFor(instrument, next.contractMonth()).orElse(0L);

        if (nextVol > 0 && nextVol >= frontVol) {
            log.info("ActiveContractRegistry: {} → {} (IBKR volume rollover: front {} vol={}, next {} vol={})",
                instrument, next.contractMonth(), front.contractMonth(), frontVol, next.contractMonth(), nextVol);
            return next.contractMonth();
        }

        log.info("ActiveContractRegistry: {} → {} (IBKR, front vol={}, next {} vol={})",
            instrument, frontMonth, frontVol, next.contractMonth(), nextVol);
        return frontMonth;
    }

    private String resolveFromIbkr(Instrument instrument) {
        try {
            return resolver.refresh(instrument)
                .map(resolved -> resolved.contract().lastTradeDateOrContractMonth())
                .map(raw -> {
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

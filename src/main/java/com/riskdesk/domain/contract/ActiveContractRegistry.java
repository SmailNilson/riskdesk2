package com.riskdesk.domain.contract;

import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single Source of Truth for the active futures contract month per instrument.
 *
 * ALL services (indicators, market data, backtest, AI Mentor) MUST read the
 * active contract month exclusively from this registry. No service may decide
 * independently which contract to use — that is the fragmentation we prevent.
 *
 * Lifecycle:
 *   1. Populated at startup by ActiveContractRegistryInitializer (IBKR → fallback properties).
 *   2. Updated ONLY when the trader explicitly confirms a rollover via POST /api/rollover/confirm.
 */
@Component
public class ActiveContractRegistry {

    private final Map<Instrument, String> contractMonths = new ConcurrentHashMap<>();

    /**
     * Returns the active contract month for an instrument (e.g. "202606"),
     * or empty if the registry has not yet been initialized.
     */
    public Optional<String> getContractMonth(Instrument instrument) {
        return Optional.ofNullable(contractMonths.get(instrument));
    }

    /**
     * Called once at startup by ActiveContractRegistryInitializer.
     * Not intended for use elsewhere.
     */
    public void initialize(Instrument instrument, String contractMonth) {
        contractMonths.put(instrument, contractMonth);
    }

    /**
     * Atomically switches the active contract month for an instrument.
     * Called ONLY when the trader explicitly confirms a rollover.
     */
    public void confirmRollover(Instrument instrument, String newContractMonth) {
        contractMonths.put(instrument, newContractMonth);
    }

    /**
     * Read-only snapshot of all active contracts — safe to expose via REST.
     */
    public Map<Instrument, String> snapshot() {
        return Map.copyOf(contractMonths);
    }
}

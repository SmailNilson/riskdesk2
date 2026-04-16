package com.riskdesk.domain.contract;

import com.riskdesk.domain.contract.port.ActiveContractPersistencePort;
import com.riskdesk.domain.model.Instrument;

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
 *   1. Populated at startup by ActiveContractRegistryInitializer (IBKR → DB → fallback properties).
 *   2. Updated ONLY when the trader explicitly confirms a rollover via POST /api/rollover/confirm.
 *
 * Persistence:
 *   When constructed with an {@link ActiveContractPersistencePort}, the registry is
 *   eagerly hydrated from the persistence store on construction, and every mutation
 *   (initialize / confirmRollover) is mirrored to the store. This makes the last
 *   known-good contract available as a second-tier fallback on subsequent boots
 *   when IBKR is unreachable — the system is self-healing and no longer depends
 *   on an operator keeping the property-file defaults up to date.
 *
 *   Persistence failures are logged and swallowed: an unavailable DB at boot or
 *   during a save must not break indicator/market-data flows, which rely on the
 *   in-memory map being authoritative at runtime.
 */
public class ActiveContractRegistry {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ActiveContractRegistry.class);

    private final Map<Instrument, String> contractMonths = new ConcurrentHashMap<>();
    private final ActiveContractPersistencePort persistencePort;

    /**
     * In-memory only constructor — used by unit tests that don't need persistence.
     */
    public ActiveContractRegistry() {
        this.persistencePort = null;
    }

    /**
     * Production constructor — hydrates from the persistence store eagerly so that
     * {@link #snapshot()} reflects the last-known contracts immediately after the
     * Spring bean is wired, before the startup initializer runs.
     */
    public ActiveContractRegistry(ActiveContractPersistencePort port) {
        this.persistencePort = port;
        if (port != null) {
            try {
                Map<Instrument, String> loaded = port.loadAll();
                if (loaded != null && !loaded.isEmpty()) {
                    contractMonths.putAll(loaded);
                    log.info("ActiveContractRegistry hydrated from persistence: {}", loaded);
                }
            } catch (Exception e) {
                log.warn("ActiveContractRegistry: failed to hydrate from persistence at boot — continuing with empty map ({})", e.getMessage());
            }
        }
    }

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
        persist(instrument, contractMonth);
    }

    /**
     * Atomically switches the active contract month for an instrument.
     * Called ONLY when the trader explicitly confirms a rollover.
     */
    public void confirmRollover(Instrument instrument, String newContractMonth) {
        contractMonths.put(instrument, newContractMonth);
        persist(instrument, newContractMonth);
    }

    /**
     * Read-only snapshot of all active contracts — safe to expose via REST.
     */
    public Map<Instrument, String> snapshot() {
        return Map.copyOf(contractMonths);
    }

    private void persist(Instrument instrument, String contractMonth) {
        if (persistencePort == null) return;
        try {
            persistencePort.save(instrument, contractMonth);
        } catch (Exception e) {
            log.warn("ActiveContractRegistry: failed to persist {} → {} ({}) — continuing with in-memory value",
                instrument, contractMonth, e.getMessage());
        }
    }
}

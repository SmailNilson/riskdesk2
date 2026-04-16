package com.riskdesk.domain.contract.port;

import com.riskdesk.domain.model.Instrument;

import java.util.Map;
import java.util.Optional;

/**
 * Port for persisting the active futures contract month per instrument.
 * Allows {@code ActiveContractRegistry} to survive restarts by restoring
 * its last-known state from the database, especially when IBKR is unreachable
 * at boot time.
 *
 * <p>The contract month follows the IBKR convention of "YYYYMM" (e.g. "202506"
 * for the June 2025 contract).
 *
 * <p>Serves as the middle fallback tier in the active-contract resolution chain:
 * <ol>
 *   <li>IBKR live query (authoritative when reachable)</li>
 *   <li>This persistence port (last-known-good across restarts)</li>
 *   <li>{@code application.properties} defaults (last resort)</li>
 * </ol>
 */
public interface ActiveContractPersistencePort {

    /**
     * Loads the last persisted contract month for a single instrument.
     *
     * @param instrument the instrument to look up
     * @return contract month in "YYYYMM" format, or empty if never persisted
     */
    Optional<String> load(Instrument instrument);

    /**
     * Loads all persisted contract months keyed by instrument.
     *
     * @return map of instrument → contract month ("YYYYMM"); entries whose
     *         stored instrument key is unknown to the current {@link Instrument}
     *         enum are silently skipped by the adapter
     */
    Map<Instrument, String> loadAll();

    /**
     * Upserts the active contract month for an instrument. Inserts on first
     * write and updates on subsequent writes (keyed by instrument).
     *
     * @param instrument     the instrument whose active contract is being recorded
     * @param contractMonth  contract month in "YYYYMM" format (e.g. "202506")
     */
    void save(Instrument instrument, String contractMonth);
}

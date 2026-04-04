package com.riskdesk.domain.contract.port;

import com.riskdesk.domain.model.Instrument;

import java.util.OptionalLong;

/**
 * Port for fetching the current Open Interest of a futures contract.
 * Implemented by infrastructure adapters (e.g. IBKR gateway).
 */
public interface OpenInterestProvider {

    /**
     * Fetches the current Open Interest for the given instrument and contract month.
     *
     * @param instrument    the futures instrument (e.g. MCL, MGC)
     * @param contractMonth YYYYMM format (e.g. "202506")
     * @return the OI value, or empty if unavailable
     */
    OptionalLong fetchOpenInterest(Instrument instrument, String contractMonth);
}

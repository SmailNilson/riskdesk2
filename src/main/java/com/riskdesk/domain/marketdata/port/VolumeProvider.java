package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.model.Instrument;

import java.util.OptionalLong;

/**
 * Port for retrieving the current daily trading volume for a specific
 * futures contract month.  Implementations may query IBKR or any other
 * market data source.
 */
@FunctionalInterface
public interface VolumeProvider {
    OptionalLong volumeFor(Instrument instrument, String contractMonth);
}

package com.riskdesk.domain.contract;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.TradingSessionResolver;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;

/**
 * Immutable value object representing a single futures contract that is
 * a candidate for being the "active" contract.  Carries the contract month,
 * its expiry date, and — when available — its daily trading volume.
 */
public record RolloverCandidate(
    Instrument instrument,
    String contractMonth,
    long volume,
    LocalDate expiry
) {

    public boolean isMoreLiquidThan(RolloverCandidate other) {
        return this.volume >= other.volume;
    }

    public boolean expiresWithin(int days) {
        if (expiry == null) return false;
        long remaining = LocalDate.now(TradingSessionResolver.CME_ZONE).until(expiry, ChronoUnit.DAYS);
        return remaining <= days;
    }

    public long daysToExpiry() {
        if (expiry == null) return Long.MAX_VALUE;
        return LocalDate.now(TradingSessionResolver.CME_ZONE).until(expiry, ChronoUnit.DAYS);
    }

    /** Returns a copy enriched with a live volume value. */
    public RolloverCandidate withVolume(OptionalLong vol) {
        return new RolloverCandidate(instrument, contractMonth, vol.orElse(0L), expiry);
    }
}

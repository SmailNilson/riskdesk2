package com.riskdesk.domain.contract;

import com.riskdesk.domain.model.Instrument;

/**
 * Immutable result of comparing the front-month and next-month contract
 * volumes.  {@code shouldRoll} is {@code true} when the next contract is
 * at least as liquid as the current front-month.
 */
public record RolloverDecision(
    Instrument instrument,
    RolloverCandidate front,
    RolloverCandidate next,
    boolean shouldRoll
) {

    public static RolloverDecision hold(Instrument instrument, RolloverCandidate front) {
        return new RolloverDecision(instrument, front, null, false);
    }
}

package com.riskdesk.application.dto;

/**
 * Tri-state result of looking a broker order up by its {@code orderRef}. Crucially separates
 * <b>UNAVAILABLE</b> (the gateway could not be queried — disabled, disconnected, or no resolved
 * account) from <b>NOT_FOUND</b> (the live and completed order sets were both queried and the
 * order is in neither). Callers MUST NOT treat UNAVAILABLE as absence: cancelling a tracking row
 * during an outage could hide a real, filled broker position.
 */
public record BrokerOrderLookup(Outcome outcome, BrokerOrderStatusView order) {

    public enum Outcome { FOUND, NOT_FOUND, UNAVAILABLE }

    public static BrokerOrderLookup found(BrokerOrderStatusView order) {
        return new BrokerOrderLookup(Outcome.FOUND, order);
    }

    public static BrokerOrderLookup notFound() {
        return new BrokerOrderLookup(Outcome.NOT_FOUND, null);
    }

    public static BrokerOrderLookup unavailable() {
        return new BrokerOrderLookup(Outcome.UNAVAILABLE, null);
    }

    public boolean isFound() {
        return outcome == Outcome.FOUND;
    }

    public boolean isNotFound() {
        return outcome == Outcome.NOT_FOUND;
    }

    public boolean isUnavailable() {
        return outcome == Outcome.UNAVAILABLE;
    }
}

package com.riskdesk.domain.orderflow.perfectsetup;

/**
 * Lifecycle of a Perfect Setup, evaluated transition-based (an event is only
 * emitted when the state actually changes — consistent with the project's
 * alert rule).
 *
 * <pre>
 *   IDLE ──(confluence ≥ armThreshold + valid R:R)──▶ LONG_ARMED / SHORT_ARMED
 *   LONG_ARMED / SHORT_ARMED ──(price enters entry zone)──▶ TRIGGERED
 *   LONG_ARMED / SHORT_ARMED ──(stop breached / thesis flips)──▶ INVALIDATED
 *   LONG_ARMED / SHORT_ARMED ──(armTtl elapsed)──▶ EXPIRED
 *   TRIGGERED / INVALIDATED / EXPIRED ──(cooldown elapsed)──▶ IDLE
 * </pre>
 */
public enum PerfectSetupState {
    IDLE,
    LONG_ARMED,
    SHORT_ARMED,
    TRIGGERED,
    INVALIDATED,
    EXPIRED;

    /** Whether this is an armed (actionable, waiting-for-entry) state. */
    public boolean isArmed() {
        return this == LONG_ARMED || this == SHORT_ARMED;
    }

    /** Whether this is a terminal state subject to the cooldown before returning to IDLE. */
    public boolean isTerminal() {
        return this == TRIGGERED || this == INVALIDATED || this == EXPIRED;
    }
}

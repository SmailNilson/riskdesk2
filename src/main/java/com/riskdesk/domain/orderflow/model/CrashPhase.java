package com.riskdesk.domain.orderflow.model;

/**
 * Flash crash FSM states (UC-OF-006).
 */
public enum CrashPhase {
    /** Normal market conditions. */
    NORMAL,
    /** 3/5 crash conditions met — monitoring. */
    INITIATING,
    /** Velocity increasing — crash intensifying. */
    ACCELERATING,
    /** Velocity decreasing — crash losing steam. */
    DECELERATING,
    /** Price reversing direction + absorption detected. */
    REVERSING
}

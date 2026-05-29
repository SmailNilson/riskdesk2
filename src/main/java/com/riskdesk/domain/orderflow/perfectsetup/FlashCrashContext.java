package com.riskdesk.domain.orderflow.perfectsetup;

/**
 * Latest flash-crash FSM phase for the instrument, used by the LIQUIDITY_GRAB
 * axis. A {@code REVERSING} phase with a high reversal score means a stop-run
 * (bid collapse) was flushed and reclaimed — a bullish liquidity grab.
 *
 * @param phase         current FSM phase (NORMAL / INITIATING / ACCELERATING /
 *                      DECELERATING / REVERSING)
 * @param reversalScore reversal strength (0-100)
 */
public record FlashCrashContext(String phase, double reversalScore) {
    public FlashCrashContext {
        phase = phase == null ? "NORMAL" : phase;
    }

    public static FlashCrashContext none() {
        return new FlashCrashContext("NORMAL", 0.0);
    }

    public boolean isReversing() {
        return "REVERSING".equalsIgnoreCase(phase);
    }
}

package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.domain.engine.strategy.wtx.WtxTrailingExitEvaluator.ExitReason;

/**
 * Why an open WTX position was closed — surfaced on close signals so the UI can tell a
 * profit-taking / stop exit apart from a plain reverse.
 *
 * <ul>
 *   <li>{@link #REVERSE} — closed because the opposite WaveTrend signal fired (reverse-on-opp).</li>
 *   <li>{@link #TRAILING_TP} — ATR/point trailing stop hit after arming in profit (take-profit).</li>
 *   <li>{@link #STOP_LOSS} — initial protective stop hit before the trail armed.</li>
 *   <li>{@link #FORCE_CLOSE} — flattened by the NY session-end force-close window.</li>
 *   <li>{@link #MAX_LOSS} — flattened by the daily max-loss latch.</li>
 *   <li>{@link #SWING_BIAS} — closed by the swing-bias filter (position against resolved bias).</li>
 *   <li>{@link #HTF_BIAS} — closed because the HTF (1h) bias no longer supports the position's
 *       direction (turned NEUTRAL or opposite). Opt-in early exit — see
 *       {@code riskdesk.wtx.htf-bias-exit-enabled}.</li>
 * </ul>
 *
 * Null on OPEN / NONE signals — only close rows carry an exit type.
 */
public enum WtxExitType {
    REVERSE,
    TRAILING_TP,
    STOP_LOSS,
    FORCE_CLOSE,
    MAX_LOSS,
    SWING_BIAS,
    HTF_BIAS;

    /** Maps the trailing evaluator's reason to the exit type (TRAILING_STOP = take-profit). */
    public static WtxExitType fromExitReason(ExitReason reason) {
        if (reason == null) return null;
        return switch (reason) {
            case TRAILING_STOP -> TRAILING_TP;
            case INITIAL_STOP -> STOP_LOSS;
            case NONE -> null;
        };
    }
}

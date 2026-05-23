package com.riskdesk.domain.engine.strategy.wtxrsi;

/**
 * Mirrors the semantics of {@code WtxSwingBiasFilter}: when the toggle is on,
 * a fresh entry whose direction contradicts the resolved {@link WtxRsiSwingBias}
 * is suppressed; an existing position pointing the wrong way is downgraded to
 * a CLOSE so the operator can flatten and re-arm in the dominant direction.
 *
 * <p>{@link WtxRsiSwingBias#NEUTRAL} is always a passthrough — the bias detector
 * deliberately returns NEUTRAL during warm-up and on chop, and the filter must
 * not block trading in those windows.
 */
public final class WtxRsiSwingBiasFilter {

    private WtxRsiSwingBiasFilter() {}

    public enum Decision {
        ALLOW_OPEN,
        SUPPRESS,
        FORCE_CLOSE_LONG,
        FORCE_CLOSE_SHORT
    }

    /**
     * @param side            direction of the proposed fresh signal (null when no entry signal exists this bar)
     * @param currentPosition strategy's current position
     * @param bias            resolved bias for the bar being evaluated
     */
    public static Decision evaluate(
            WtxRsiSignal.Side side,
            WtxRsiPosition currentPosition,
            WtxRsiSwingBias bias) {

        if (bias == null || bias == WtxRsiSwingBias.NEUTRAL) {
            return Decision.ALLOW_OPEN;
        }

        // Step 1: an open position pointing against the bias must be cut, regardless
        // of whether there is a fresh signal this bar.
        if (currentPosition == WtxRsiPosition.LONG && bias == WtxRsiSwingBias.BEARISH) {
            return Decision.FORCE_CLOSE_LONG;
        }
        if (currentPosition == WtxRsiPosition.SHORT && bias == WtxRsiSwingBias.BULLISH) {
            return Decision.FORCE_CLOSE_SHORT;
        }

        // Step 2: a fresh signal contradicting the bias is suppressed.
        if (side != null) {
            boolean aligned =
                    (side == WtxRsiSignal.Side.LONG && bias == WtxRsiSwingBias.BULLISH)
                 || (side == WtxRsiSignal.Side.SHORT && bias == WtxRsiSwingBias.BEARISH);
            if (!aligned) return Decision.SUPPRESS;
        }

        return Decision.ALLOW_OPEN;
    }
}

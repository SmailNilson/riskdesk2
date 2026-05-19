package com.riskdesk.domain.engine.strategy.wtx;

/**
 * Opt-in directional filter for WTX signals.
 *
 * <p>When enabled on the strategy state, a signal whose direction contradicts
 * {@code enrichment.smcSwingBias} is downgraded to a CLOSE (if open) or NONE
 * (if flat). A null bias is treated as a passthrough so that the SMC engine's
 * warm-up period does not block trading.
 *
 * <p>Stateless and isolated from infrastructure — the strategy service applies
 * the result via {@link WtxSignal#withAction(WtxAction)}.
 */
public final class WtxSwingBiasFilter {

    private WtxSwingBiasFilter() {}

    /**
     * Decide which action to retain given the current direction, bias and open position.
     *
     * @param direction       signal direction — "LONG" / "SHORT"
     * @param suggestedAction action proposed by {@link WtxBarEvaluator}
     * @param swingBias       {@code "BULLISH"} / {@code "BEARISH"} / null
     * @param currentPosition current strategy position
     * @return the action that should be emitted; identical to {@code suggestedAction}
     *         when the signal aligns with the swing-bias or the bias is unknown.
     */
    public static WtxAction filter(
            String direction,
            WtxAction suggestedAction,
            String swingBias,
            WtxPosition currentPosition
    ) {
        // CLOSE / NONE actions are not entry signals — never filtered.
        if (suggestedAction == WtxAction.NONE
                || suggestedAction == WtxAction.CLOSE_LONG
                || suggestedAction == WtxAction.CLOSE_SHORT
                || suggestedAction == WtxAction.CLOSE_ALL) {
            return suggestedAction;
        }
        // Null bias = SMC warm-up; passthrough by design.
        if (swingBias == null) {
            return suggestedAction;
        }
        boolean aligned =
                ("LONG".equals(direction) && "BULLISH".equals(swingBias))
             || ("SHORT".equals(direction) && "BEARISH".equals(swingBias));
        if (aligned) {
            return suggestedAction;
        }
        // Contra-bias: cut the open leg, otherwise skip.
        return switch (currentPosition) {
            case LONG -> WtxAction.CLOSE_LONG;
            case SHORT -> WtxAction.CLOSE_SHORT;
            case FLAT -> WtxAction.NONE;
        };
    }
}

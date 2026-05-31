package com.riskdesk.domain.quant.automation;

/**
 * Direction of an auto-armed quant trade.
 *
 * <p>Local to the quant automation slice — kept independent from other
 * directional enums so the quant subdomain stays self-contained. Only LONG
 * and SHORT are actionable here; the auto-arm pipeline never produces a NEUTRAL execution
 * (a snapshot that is neither {@code shortAvailable()} nor {@code longAvailable()}
 * is simply skipped).</p>
 */
public enum AutoArmDirection {
    LONG,
    SHORT;

    /**
     * Mapping to the IBKR / order-side {@code action} string used throughout
     * {@link com.riskdesk.domain.model.TradeExecutionRecord#getAction()}.
     */
    public String action() {
        return switch (this) {
            case LONG -> "BUY";
            case SHORT -> "SELL";
        };
    }
}

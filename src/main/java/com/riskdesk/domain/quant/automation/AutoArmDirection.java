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
     * Broker-side {@code action} token persisted on
     * {@link com.riskdesk.domain.model.TradeExecutionRecord#getAction()}. MUST be "LONG"/"SHORT" —
     * the gateway maps {@code "SHORT"||"SELL" → SELL, else BUY}, so the previous "BUY"/"SELL"
     * convention sent every auto-armed SHORT to IBKR as a BUY once submitted.
     */
    public String action() {
        return name();
    }
}

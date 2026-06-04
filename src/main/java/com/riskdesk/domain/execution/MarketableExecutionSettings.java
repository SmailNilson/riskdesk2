package com.riskdesk.domain.execution;

/**
 * Runtime, operator-controllable policy for marketable EXIT / reverse-open pricing in the execution core
 * (see {@code DefaultOrderRouter}). Replaces the static {@code @Value} flags so the operator can flip the
 * behaviour live from the UI (like Auto-IBKR) without a redeploy.
 *
 * <ul>
 *   <li>{@code closeEnabled} — reducing legs (close / flatten / reverse-close) are priced marketable.</li>
 *   <li>{@code reverseOpenEnabled} — the OPEN leg of a reverse that flattened is also priced marketable.</li>
 *   <li>{@code crossTicks} — ticks crossed through the touch (the worst-case slippage cap).</li>
 * </ul>
 *
 * <p>Global (one policy for the whole execution core — all strategies, all instruments). Immutable;
 * {@code crossTicks} is clamped to {@code >= 0} ({@code placeLimitOrder} would reject a negative price).</p>
 */
public record MarketableExecutionSettings(boolean closeEnabled, boolean reverseOpenEnabled, int crossTicks) {

    public MarketableExecutionSettings {
        crossTicks = Math.max(0, crossTicks);
    }
}

package com.riskdesk.domain.quant.setup.port;

import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.quant.setup.SetupStyle;

/**
 * Domain policy interface that maps market conditions to a trading style.
 * Implementations live in the application layer.
 *
 * <p>Both volatility signals are absolute, instrument-relative — no
 * pre-computed percentile is required:</p>
 * <ul>
 *   <li>{@code bbWidthPct}: VWAP band spread relative to price,
 *       {@code (vwapUpper - vwapLower) / vwap * 100}. Lower means tighter
 *       compression / range-friendly conditions.</li>
 *   <li>{@code dayMoveAbsPct}: absolute day move relative to price,
 *       {@code |dayMove| / price * 100}. Lower means quiet session.</li>
 * </ul>
 *
 * <p>Either argument may be {@link Double#NaN} when upstream data is
 * unavailable — implementations must tolerate that and fall back to a safe
 * default (typically DAY).</p>
 */
public interface RegimeSwitchPolicy {

    /**
     * @param regime         current market regime
     * @param bbWidthPct     VWAP band spread as percent of price (or {@code NaN} if unknown)
     * @param dayMoveAbsPct  absolute day move as percent of price (or {@code NaN} if unknown)
     * @return the appropriate trading style for the given conditions
     */
    SetupStyle determineStyle(MarketRegime regime, double bbWidthPct, double dayMoveAbsPct);
}

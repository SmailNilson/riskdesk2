package com.riskdesk.application.quant.setup;

import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.quant.setup.SetupStyle;
import com.riskdesk.domain.quant.setup.port.RegimeSwitchPolicy;
import org.springframework.stereotype.Component;

/**
 * Picks SCALP when the market is range-bound and quiet (tight VWAP bands +
 * small day move), otherwise defaults to DAY trading style.
 *
 * <p>Thresholds are intentionally conservative so SCALP only fires in
 * unambiguously low-volatility conditions:</p>
 * <ul>
 *   <li>{@code bbWidthPct < 0.50%} — VWAP bands within ½% of price</li>
 *   <li>{@code dayMoveAbsPct < 0.30%} — session has moved less than ⅓%</li>
 * </ul>
 *
 * <p>If either signal is {@code NaN} (data unavailable) the policy
 * conservatively falls back to DAY rather than guessing.</p>
 */
@Component
public class DefaultRegimeSwitchPolicy implements RegimeSwitchPolicy {

    static final double SCALP_BB_WIDTH_MAX_PCT  = 0.50;
    static final double SCALP_DAY_MOVE_MAX_PCT  = 0.30;

    @Override
    public SetupStyle determineStyle(MarketRegime regime,
                                     double bbWidthPct,
                                     double dayMoveAbsPct) {
        if (regime != MarketRegime.RANGING) return SetupStyle.DAY;
        if (Double.isNaN(bbWidthPct) || Double.isNaN(dayMoveAbsPct)) return SetupStyle.DAY;
        if (bbWidthPct >= SCALP_BB_WIDTH_MAX_PCT) return SetupStyle.DAY;
        if (dayMoveAbsPct >= SCALP_DAY_MOVE_MAX_PCT) return SetupStyle.DAY;
        return SetupStyle.SCALP;
    }
}

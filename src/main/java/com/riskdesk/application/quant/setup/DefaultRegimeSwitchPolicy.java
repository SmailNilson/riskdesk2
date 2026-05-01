package com.riskdesk.application.quant.setup;

import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.quant.setup.SetupStyle;
import com.riskdesk.domain.quant.setup.port.RegimeSwitchPolicy;
import org.springframework.stereotype.Component;

/**
 * Picks SCALP when the market is range-bound and narrow (low BB width + low
 * ATR), otherwise defaults to DAY trading style.
 *
 * <p>Thresholds are deliberately conservative so the switch to SCALP only
 * happens in clearly range-bound, low-volatility conditions.</p>
 */
@Component
public class DefaultRegimeSwitchPolicy implements RegimeSwitchPolicy {

    private static final double RANGING_BB_MAX   = 35.0;
    private static final double RANGING_ATR_MAX  = 35.0;

    @Override
    public SetupStyle determineStyle(MarketRegime regime,
                                     double bbWidthPercentile,
                                     double atrPercentile) {
        if (regime == MarketRegime.RANGING
            && bbWidthPercentile < RANGING_BB_MAX
            && atrPercentile < RANGING_ATR_MAX) {
            return SetupStyle.SCALP;
        }
        return SetupStyle.DAY;
    }
}

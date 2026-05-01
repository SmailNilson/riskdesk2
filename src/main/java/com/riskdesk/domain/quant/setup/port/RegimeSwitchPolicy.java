package com.riskdesk.domain.quant.setup.port;

import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.quant.setup.SetupStyle;

/**
 * Domain policy interface that maps market conditions to a trading style.
 * Implementations live in the application layer.
 */
public interface RegimeSwitchPolicy {

    /**
     * @param regime             current market regime
     * @param bbWidthPercentile  current BB width relative to the last 20 candles (0–100)
     * @param atrPercentile      current ATR relative to the last 20 candles (0–100)
     * @return the appropriate trading style for the given conditions
     */
    SetupStyle determineStyle(MarketRegime regime, double bbWidthPercentile, double atrPercentile);
}

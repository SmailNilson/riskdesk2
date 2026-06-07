package com.riskdesk.domain.engine.strategy.wtx.port;

import com.riskdesk.domain.engine.strategy.wtx.WtxParamOverride;

/**
 * Persistence port for per-(instrument, timeframe) WTX parameter overrides
 * (WaveTrend periods + initial-stop ATR multiple), editable from the frontend.
 */
public interface WtxParamOverridePort {

    /** Current overrides for this panel; never null — returns {@link WtxParamOverride#NONE} when none stored. */
    WtxParamOverride load(String instrument, String timeframe);

    /** Upsert the overrides for this panel. Null fields clear the corresponding override. */
    void save(String instrument, String timeframe, WtxParamOverride override);
}

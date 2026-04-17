package com.riskdesk.domain.engine.strategy.model;

/**
 * Higher-timeframe bias picture for a single instrument. Injected into
 * {@link MarketContext} as context for CONTEXT-layer agents that reason about
 * multi-timeframe alignment.
 *
 * <p>All fields are {@link MacroBias#NEUTRAL} when the corresponding HTF is
 * unavailable. Agents MUST treat NEUTRAL as "no data" — using the
 * {@link #alignmentWith(MacroBias)} helper preserves this distinction because a
 * NEUTRAL HTF contributes zero to the alignment count.
 */
public record MtfSnapshot(
    MacroBias h1Bias,
    MacroBias h4Bias,
    MacroBias dailyBias
) {
    public MtfSnapshot {
        if (h1Bias == null) h1Bias = MacroBias.NEUTRAL;
        if (h4Bias == null) h4Bias = MacroBias.NEUTRAL;
        if (dailyBias == null) dailyBias = MacroBias.NEUTRAL;
    }

    public static MtfSnapshot neutral() {
        return new MtfSnapshot(MacroBias.NEUTRAL, MacroBias.NEUTRAL, MacroBias.NEUTRAL);
    }

    /** How many of the three HTFs agree with the given direction. Range 0..3. */
    public int alignmentWith(MacroBias direction) {
        if (direction == null || direction == MacroBias.NEUTRAL) return 0;
        int count = 0;
        if (h1Bias == direction) count++;
        if (h4Bias == direction) count++;
        if (dailyBias == direction) count++;
        return count;
    }

    /** True when no HTF bias is available — agents should abstain. */
    public boolean isAllNeutral() {
        return h1Bias == MacroBias.NEUTRAL
            && h4Bias == MacroBias.NEUTRAL
            && dailyBias == MacroBias.NEUTRAL;
    }
}

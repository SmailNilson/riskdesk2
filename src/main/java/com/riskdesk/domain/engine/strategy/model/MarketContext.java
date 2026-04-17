package com.riskdesk.domain.engine.strategy.model;

import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Layer 1 — the "Where" and "Why".
 *
 * <p>Slowly-changing facts about the market environment. The strategy engine reads a
 * snapshot of this object per evaluation; it is safe to cache for the duration of an
 * H1 candle.
 *
 * <p><b>Layer discipline:</b> this record contains NO trigger-level information (no
 * tick delta, no reaction candles, no DOM). Mixing layers is a bug — see
 * {@link StrategyLayer}.
 *
 * <p><b>Null-safety:</b> every field is nullable because data availability varies per
 * instrument/session. Agents MUST use {@link #hasCompleteContext()} / guard individual
 * nulls, and abstain (see {@code AgentVote.abstain}) rather than producing a
 * neutral-but-confident vote.
 */
public record MarketContext(
    Instrument instrument,
    String referenceTimeframe,
    MacroBias macroBias,
    MarketRegime regime,
    PriceLocation priceLocation,
    PdZone pdZone,
    BigDecimal lastPrice,
    BigDecimal atr,
    /** Higher-timeframe bias picture. Never null — neutral when HTF data is absent. */
    MtfSnapshot mtf,
    /** UTC instant this context was built. Used to detect staleness vs trigger stream. */
    Instant asOf
) {
    public MarketContext {
        if (instrument == null) throw new IllegalArgumentException("instrument required");
        if (referenceTimeframe == null) throw new IllegalArgumentException("referenceTimeframe required");
        if (asOf == null) throw new IllegalArgumentException("asOf required");
        if (macroBias == null) macroBias = MacroBias.NEUTRAL;
        if (regime == null) regime = MarketRegime.UNKNOWN;
        if (priceLocation == null) priceLocation = PriceLocation.UNKNOWN;
        if (pdZone == null) pdZone = PdZone.UNKNOWN;
        if (mtf == null) mtf = MtfSnapshot.neutral();
    }

    /**
     * Backwards-compatible constructor omitting {@link MtfSnapshot}. Used by tests
     * and any caller that doesn't care about HTF alignment — the resulting context
     * reports a fully-neutral MTF picture and the {@code HtfAlignmentAgent} will
     * abstain, not pull the score toward zero.
     */
    public MarketContext(Instrument instrument, String referenceTimeframe,
                          MacroBias macroBias, MarketRegime regime,
                          PriceLocation priceLocation, PdZone pdZone,
                          BigDecimal lastPrice, BigDecimal atr, Instant asOf) {
        this(instrument, referenceTimeframe, macroBias, regime, priceLocation, pdZone,
            lastPrice, atr, MtfSnapshot.neutral(), asOf);
    }

    /** True when every context fact is known — tighter setups can require this. */
    public boolean hasCompleteContext() {
        return macroBias != MacroBias.NEUTRAL
            && regime != MarketRegime.UNKNOWN
            && priceLocation != PriceLocation.UNKNOWN
            && pdZone != PdZone.UNKNOWN
            && lastPrice != null
            && atr != null;
    }
}

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
    /** Portfolio risk picture. Never null — unknown sentinel when portfolio data is missing. */
    PortfolioState portfolio,
    /** Session / liquidity picture. Never null — unknown sentinel when session data is missing. */
    SessionInfo session,
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
        if (portfolio == null) portfolio = PortfolioState.unknown();
        if (session == null) session = SessionInfo.unknown();
    }

    /**
     * Backwards-compatible constructor omitting the pre-S3 fields. Used by tests
     * and any caller that doesn't wire HTF / portfolio / session data. The
     * resulting context has neutral MTF, unknown portfolio, unknown session —
     * the dedicated agents all abstain, so they never pull the score to zero.
     */
    public MarketContext(Instrument instrument, String referenceTimeframe,
                          MacroBias macroBias, MarketRegime regime,
                          PriceLocation priceLocation, PdZone pdZone,
                          BigDecimal lastPrice, BigDecimal atr, Instant asOf) {
        this(instrument, referenceTimeframe, macroBias, regime, priceLocation, pdZone,
            lastPrice, atr, MtfSnapshot.neutral(), PortfolioState.unknown(),
            SessionInfo.unknown(), asOf);
    }

    /**
     * S3-era constructor that wires MTF but no portfolio/session. Kept so PR #235
     * call sites ({@code MarketContextBuilder}, existing tests) continue to
     * compile while the S4a builders catch up.
     */
    public MarketContext(Instrument instrument, String referenceTimeframe,
                          MacroBias macroBias, MarketRegime regime,
                          PriceLocation priceLocation, PdZone pdZone,
                          BigDecimal lastPrice, BigDecimal atr,
                          MtfSnapshot mtf, Instant asOf) {
        this(instrument, referenceTimeframe, macroBias, regime, priceLocation, pdZone,
            lastPrice, atr, mtf, PortfolioState.unknown(), SessionInfo.unknown(), asOf);
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

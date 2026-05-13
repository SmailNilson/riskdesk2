package com.riskdesk.domain.engine.strategy.wtx;

import java.math.BigDecimal;

/**
 * Contextual market data collected at the moment a WTX signal fires.
 * All fields are INFORMATIONAL for the UI — but htfBias and structureReason ALSO reflect
 * the gating result when the active profile uses those filters.
 */
public record WtxEnrichmentSnapshot(
        // ── Order Flow (most time-sensitive, shown first) ──────────────
        /** "BUYING" | "SELLING" | "NEUTRAL" | null */
        String deltaDirection,
        BigDecimal deltaValue,
        /** "CLV_ESTIMATED" (default when real ticks unavailable) */
        String orderFlowSource,
        /** "BULLISH_ABSORPTION" | "BEARISH_ABSORPTION" | null */
        String absorptionSignal,
        /** Absorption score from AbsorptionSignal.absorptionScore() — null if no absorption */
        Double absorptionScore,

        // ── Bollinger Bands ───────────────────────────────────────────
        /** %B value: 0.0 (at lower band) → 1.0 (at upper band) */
        BigDecimal bbPct,
        boolean bbExpanding,

        // ── VWAP ─────────────────────────────────────────────────────
        /** "ABOVE" | "BELOW" | "AT" */
        String priceVsVwap,
        /** Absolute distance % between price and VWAP */
        BigDecimal vwapDistancePct,

        // ── SMC Structure ─────────────────────────────────────────────
        /** "BULLISH" | "BEARISH" | null */
        String smcInternalBias,
        /** "BULLISH" | "BEARISH" | null */
        String smcSwingBias,

        // ── Order Block ───────────────────────────────────────────────
        /** "BULLISH" | "BEARISH" | null */
        String nearestObType,
        /** Distance from current price to OB midpoint as a % of price */
        BigDecimal nearestObDistancePct,

        // ── Chaikin Money Flow ────────────────────────────────────────
        /** CMF value in [-1.0, +1.0]. Positive = buying pressure. */
        BigDecimal cmf,

        // ── Session ───────────────────────────────────────────────────
        /** e.g. "NY_AM", "LONDON", "ASIAN", "CLOSE" */
        String sessionPhase,
        boolean inKillZone,

        // ── HTF bias (HTF / STRICT profiles) ──────────────────────────
        /** "BULLISH" | "BEARISH" | "NEUTRAL" | "UNAVAILABLE" | null */
        String htfBias,

        // ── Structure proxy (STRICT profile) ──────────────────────────
        /** True when the structure filter let this signal through. Null when not evaluated. */
        Boolean structurePassed,
        /** Reason label — "LOW_SWEEP_RECLAIM" / "BULLISH_RECLAIM" / "HIGH_SWEEP_REJECT"
         *  / "BEARISH_REJECT" / "BLOCKED" / "UNAVAILABLE" / null */
        String structureReason
) {
    public static WtxEnrichmentSnapshot empty() {
        return new WtxEnrichmentSnapshot(
                null, null, "CLV_ESTIMATED", null, null,
                null, false,
                null, null,
                null, null,
                null, null,
                null,
                "UNKNOWN", false,
                null,
                null, null
        );
    }

    public WtxEnrichmentSnapshot withFilters(String htfBias, Boolean structurePassed, String structureReason) {
        return new WtxEnrichmentSnapshot(
                deltaDirection, deltaValue, orderFlowSource, absorptionSignal, absorptionScore,
                bbPct, bbExpanding,
                priceVsVwap, vwapDistancePct,
                smcInternalBias, smcSwingBias,
                nearestObType, nearestObDistancePct,
                cmf,
                sessionPhase, inKillZone,
                htfBias,
                structurePassed, structureReason
        );
    }
}

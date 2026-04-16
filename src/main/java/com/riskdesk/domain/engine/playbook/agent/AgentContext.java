package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;

/**
 * Rich evaluation context for trading agents.
 *
 * <p>All data is mapped from application services in {@code AgentOrchestratorService} —
 * domain agents never import application-layer classes. Every nested record has an
 * {@code empty()} static factory so agents can degrade gracefully when a data stream
 * is missing.
 *
 * <p>Phase 5 enrichment (order flow, depth, absorption, volume profile, zone quality)
 * exposes recently-added features so AI agents can reason on institutional-grade signals
 * rather than pure momentum indicators.
 */
public record AgentContext(
    Instrument instrument,
    String timeframe,
    PlaybookInput input,
    PortfolioState portfolio,
    MacroSnapshot macro,
    MtfSnapshot mtf,
    MomentumSnapshot momentum,
    SessionInfo session,
    BigDecimal atr,
    // ── Phase 5: order flow enrichment ──────────────────────────────────
    OrderFlowSnapshot orderFlow,
    DepthSnapshot depth,
    AbsorptionSnapshot absorption,
    VolumeProfileSnapshot volumeProfile,
    ZoneQualitySnapshot zoneQuality
) {

    // ── Portfolio (real positions + risk) ────────────────────────────────

    public record PortfolioState(
        double totalUnrealizedPnL,
        double dailyDrawdownPct,
        int openPositionCount,
        boolean hasCorrelatedPosition,
        double marginUsedPct
    ) {
        public static PortfolioState empty() {
            return new PortfolioState(0, 0, 0, false, 0);
        }
    }

    // ── Macro (DXY + intermarket) ───────────────────────────────────────

    public record MacroSnapshot(
        Double dxyPctChange,
        String dxyTrend,
        String correlationAlignment,
        String dataAvailability,
        String sessionPhase,
        boolean isKillZone
    ) {
        public static MacroSnapshot empty() {
            return new MacroSnapshot(null, null, null, null, null, false);
        }
    }

    // ── Multi-Timeframe (H1 / H4 / Daily bias + BOS/CHoCH quality) ─────

    public record MtfSnapshot(
        String h1SwingBias,
        String h1InternalBias,
        String h4SwingBias,
        String dailySwingBias,
        String h1LastBreakType,
        String h4LastBreakType,
        // ── Phase 5: BOS/CHoCH confirmation (OK vs FAKE) ────────────────
        Double h1LastBreakConfidence,   // 0-100, null if unknown
        Boolean h1LastBreakConfirmed,   // true = volume spike + delta aligned
        Double h4LastBreakConfidence,
        Boolean h4LastBreakConfirmed
    ) {
        public static MtfSnapshot empty() {
            return new MtfSnapshot(null, null, null, null, null, null, null, null, null, null);
        }

        /** Count of HTF biases agreeing with the given direction. */
        public int alignmentScore(String direction) {
            int score = 0;
            if (biasMatches(h1SwingBias, direction)) score++;
            if (biasMatches(h4SwingBias, direction)) score++;
            if (biasMatches(dailySwingBias, direction)) score++;
            return score;
        }

        private static boolean biasMatches(String bias, String direction) {
            if (bias == null || direction == null) return false;
            return ("LONG".equalsIgnoreCase(direction) && "BULLISH".equalsIgnoreCase(bias))
                || ("SHORT".equalsIgnoreCase(direction) && "BEARISH".equalsIgnoreCase(bias));
        }
    }

    // ── Momentum (RSI, MACD, WaveTrend, BB) ─────────────────────────────

    public record MomentumSnapshot(
        BigDecimal rsi,
        String rsiSignal,
        BigDecimal macdHistogram,
        String macdCrossover,
        BigDecimal wtWt1,
        BigDecimal wtWt2,
        String wtSignal,
        BigDecimal bbPct,
        boolean bbTrendExpanding,
        String bbTrendSignal,
        boolean supertrendBullish,
        String stochSignal,
        String stochCrossover
    ) {
        public static MomentumSnapshot empty() {
            return new MomentumSnapshot(
                null, null, null, null, null, null, null,
                null, false, null, false, null, null
            );
        }

        /** Momentum contradicts the trade direction. */
        public boolean momentumContradicts(String direction) {
            if ("LONG".equalsIgnoreCase(direction)) {
                return "OVERBOUGHT".equalsIgnoreCase(rsiSignal)
                    || "OVERBOUGHT".equalsIgnoreCase(wtSignal)
                    || (macdHistogram != null && macdHistogram.doubleValue() < 0
                        && "BEARISH".equalsIgnoreCase(macdCrossover));
            } else {
                return "OVERSOLD".equalsIgnoreCase(rsiSignal)
                    || "OVERSOLD".equalsIgnoreCase(wtSignal)
                    || (macdHistogram != null && macdHistogram.doubleValue() > 0
                        && "BULLISH".equalsIgnoreCase(macdCrossover));
            }
        }
    }

    // ── Session & Timing ────────────────────────────────────────────────

    public record SessionInfo(
        String phase,
        boolean isKillZone,
        boolean isMarketOpen,
        boolean isMaintenanceWindow
    ) {
        public static SessionInfo empty() {
            return new SessionInfo(null, false, true, false);
        }

        public boolean isLowLiquidity() {
            return "ASIAN".equalsIgnoreCase(phase)
                || "CLOSE".equalsIgnoreCase(phase)
                || isMaintenanceWindow;
        }

        public boolean isHighLiquidity() {
            return "NY_AM".equalsIgnoreCase(phase)
                || "LONDON".equalsIgnoreCase(phase);
        }
    }

    // ── Order Flow (tick-by-tick aggregation) ─────────────────────────

    /**
     * Rolling-window aggregation of classified trade ticks.
     * Source is {@code REAL_TICKS} when IBKR tick-by-tick is subscribed, otherwise
     * {@code CLV_ESTIMATED} (close-location-value fallback) — AI agents must treat
     * CLV as lower-confidence.
     */
    public record OrderFlowSnapshot(
        String source,              // REAL_TICKS or CLV_ESTIMATED
        long buyVolume,
        long sellVolume,
        long delta,
        long cumulativeDelta,
        double buyRatioPct,
        String deltaTrend,          // RISING, FALLING, FLAT
        boolean divergenceDetected,
        String divergenceType       // BULLISH_DIVERGENCE, BEARISH_DIVERGENCE, null
    ) {
        public static OrderFlowSnapshot empty() {
            return new OrderFlowSnapshot("CLV_ESTIMATED", 0, 0, 0, 0, 50.0, "FLAT", false, null);
        }

        public boolean hasRealTicks() {
            return "REAL_TICKS".equals(source);
        }

        public boolean isBullishPressure() {
            return buyRatioPct > 55.0;
        }

        public boolean isBearishPressure() {
            return buyRatioPct < 45.0;
        }
    }

    // ── Depth (Level-2 order book) ─────────────────────────────────────

    /**
     * Lightweight Level-2 depth metrics derived from the in-memory order book.
     * {@code depthImbalance}: -1.0 (asks dominate) to +1.0 (bids dominate).
     */
    public record DepthSnapshot(
        boolean available,
        long totalBidSize,
        long totalAskSize,
        double depthImbalance,
        double bestBid,
        double bestAsk,
        double spreadTicks,
        boolean bidWallPresent,
        boolean askWallPresent
    ) {
        public static DepthSnapshot empty() {
            return new DepthSnapshot(false, 0, 0, 0.0, 0.0, 0.0, 0.0, false, false);
        }
    }

    // ── Absorption (institutional limit-order signal) ─────────────────

    public record AbsorptionSnapshot(
        boolean detected,
        String side,                // BULLISH_ABSORPTION, BEARISH_ABSORPTION, or null
        double score,               // composite score > 2.0 = strong signal
        double priceMoveTicks,
        long totalVolume
    ) {
        public static AbsorptionSnapshot empty() {
            return new AbsorptionSnapshot(false, null, 0.0, 0.0, 0);
        }

        public boolean isBullish() {
            return detected && "BULLISH_ABSORPTION".equalsIgnoreCase(side);
        }

        public boolean isBearish() {
            return detected && "BEARISH_ABSORPTION".equalsIgnoreCase(side);
        }
    }

    // ── Volume Profile (session POC / Value Area) ─────────────────────

    public record VolumeProfileSnapshot(
        Double pocPrice,
        Double valueAreaHigh,
        Double valueAreaLow,
        boolean priceInValueArea
    ) {
        public static VolumeProfileSnapshot empty() {
            return new VolumeProfileSnapshot(null, null, null, false);
        }
    }

    // ── Zone Quality (best-setup enrichment: OB / FVG / EQH-EQL scores) ─

    /**
     * Order-flow-derived quality metrics for the playbook's best setup.
     * {@code obLiveScore} > 70 and {@code defended=true} indicate a confirmed
     * institutional zone. {@code fvgQualityScore} > 70 indicates a real imbalance.
     */
    public record ZoneQualitySnapshot(
        Double obFormationScore,
        Double obLiveScore,
        Boolean obDefended,
        Double obAbsorptionScore,
        Double fvgQualityScore,
        Double nearestBreakConfidence,
        Boolean nearestBreakConfirmed,
        Double nearestEqualLevelLiquidityScore
    ) {
        public static ZoneQualitySnapshot empty() {
            return new ZoneQualitySnapshot(null, null, null, null, null, null, null, null);
        }

        public boolean isHighQualityOb() {
            return obLiveScore != null && obLiveScore > 70.0
                && Boolean.TRUE.equals(obDefended);
        }

        public boolean isWeakOb() {
            return obLiveScore != null && obLiveScore < 40.0;
        }

        public boolean isFakeBreak() {
            return Boolean.FALSE.equals(nearestBreakConfirmed)
                || (nearestBreakConfidence != null && nearestBreakConfidence < 40.0);
        }
    }
}

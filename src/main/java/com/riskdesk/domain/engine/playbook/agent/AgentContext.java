package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;

/**
 * Rich evaluation context for trading agents.
 * All data is mapped from application services in AgentOrchestratorService —
 * domain agents never import application-layer classes.
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
    BigDecimal atr
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

    // ── Multi-Timeframe (H1 / H4 / Daily bias) ─────────────────────────

    public record MtfSnapshot(
        String h1SwingBias,
        String h1InternalBias,
        String h4SwingBias,
        String dailySwingBias,
        String h1LastBreakType,
        String h4LastBreakType
    ) {
        public static MtfSnapshot empty() {
            return new MtfSnapshot(null, null, null, null, null, null);
        }

        /** All available HTF biases agree with the given direction. */
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

        /** RSI divergence heuristic: price trending one way, RSI says the opposite. */
        public boolean hasRsiBearishDivergence(String swingBias) {
            return "BULLISH".equalsIgnoreCase(swingBias)
                && rsi != null && rsi.doubleValue() < 55;
        }

        public boolean hasRsiBullishDivergence(String swingBias) {
            return "BEARISH".equalsIgnoreCase(swingBias)
                && rsi != null && rsi.doubleValue() > 45;
        }

        /** Momentum contradicts the trade direction. */
        public boolean momentumContradicts(String direction) {
            if ("LONG".equalsIgnoreCase(direction)) {
                // LONG but momentum bearish
                return "OVERBOUGHT".equalsIgnoreCase(rsiSignal)
                    || "OVERBOUGHT".equalsIgnoreCase(wtSignal)
                    || (macdHistogram != null && macdHistogram.doubleValue() < 0
                        && "BEARISH".equalsIgnoreCase(macdCrossover));
            } else {
                // SHORT but momentum bullish
                return "OVERSOLD".equalsIgnoreCase(rsiSignal)
                    || "OVERSOLD".equalsIgnoreCase(wtSignal)
                    || (macdHistogram != null && macdHistogram.doubleValue() > 0
                        && "BULLISH".equalsIgnoreCase(macdCrossover));
            }
        }

        /** Momentum confirms the trade direction. */
        public boolean momentumConfirms(String direction) {
            int confirms = 0;
            if ("LONG".equalsIgnoreCase(direction)) {
                if (macdHistogram != null && macdHistogram.doubleValue() > 0) confirms++;
                if ("BULLISH".equalsIgnoreCase(macdCrossover)) confirms++;
                if (rsi != null && rsi.doubleValue() > 40 && rsi.doubleValue() < 70) confirms++;
                if (supertrendBullish) confirms++;
            } else {
                if (macdHistogram != null && macdHistogram.doubleValue() < 0) confirms++;
                if ("BEARISH".equalsIgnoreCase(macdCrossover)) confirms++;
                if (rsi != null && rsi.doubleValue() < 60 && rsi.doubleValue() > 30) confirms++;
                if (!supertrendBullish) confirms++;
            }
            return confirms >= 2;
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
}

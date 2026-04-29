package com.riskdesk.domain.quant.structure;

import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure structural filter evaluator — no Spring, no I/O. Consumes the
 * indicator + strategy projections (fetched by adapters in the application /
 * infrastructure layers) plus the order-flow {@link PatternAnalysis} already
 * computed by {@code OrderFlowPatternDetector}, and returns the list of
 * structural BLOCKS (kill-switch SHORT) and WARNINGS (score modifiers,
 * displayed but not blocking).
 *
 * <p>Direct port of the Python reference {@code multi_scan_v4.py}
 * {@code evaluate_short_blocks(...)}. The thresholds and override rules below
 * are the ground-truth from that script — keep them in sync.</p>
 *
 * <p><b>Threading.</b> Stateless and immutable — safe to share across
 * instruments.</p>
 */
public final class StructuralFilterEvaluator {

    /** OB BLOCK demoted to WARNING when CHoCH bear is recent OR a high-confidence VRAIE_VENTE pattern is in play. */
    private static final String CHOCH_BEARISH = "CHOCH_BEARISH";
    /** Multi-resolution bias threshold — at least this many BULLISH legs blocks SHORT. */
    private static final int MTF_BULL_BLOCK   = 4;
    /** CMF threshold above which buying flow is considered structurally dominant. */
    private static final double CMF_VERY_BULL = 0.15;
    /** CMF threshold above which buying flow becomes a warning (but not a block). */
    private static final double CMF_POSITIVE  = 0.05;
    /** Bollinger %B below which we flag oversold-bounce risk. */
    private static final double BB_OVERSOLD   = 0.15;
    /** Number of σ below VWAP-lower below which we flag mean-reversion risk. */
    private static final double VWAP_FAR_SIGMAS = 1.0;
    /** Distance in points below which an equal-low pool is considered "near". */
    private static final double EQUAL_LOWS_PROX_PTS = 15.0;
    /** Minimum touch count for an equal-low pool to count as a liquidity grab risk. */
    private static final int EQUAL_LOWS_MIN_TOUCH = 2;

    /**
     * Evaluates the structural filters for a SHORT setup.
     *
     * @param price     current price ({@code null}-tolerant — most rules require a price)
     * @param ind       indicator projection ({@code null} → no indicator-driven block/warning)
     * @param strategy  strategy projection ({@code null} → no strategy-driven block/warning)
     * @param pattern   order-flow pattern already computed by {@code OrderFlowPatternDetector}
     *                  ({@code null} accepted)
     */
    public StructuralFilterResult evaluateForShort(Double price,
                                                   IndicatorsSnapshot ind,
                                                   StrategyVotes strategy,
                                                   PatternAnalysis pattern) {
        List<StructuralBlock> blocks = new ArrayList<>();
        List<StructuralWarning> warnings = new ArrayList<>();
        int scoreMod = 0;

        // ── BLOCKS ────────────────────────────────────────────────────────

        // OB_BULL_FRESH — active bullish OB containing the current price.
        // Override: a recent CHoCH bear or a high-confidence VRAIE_VENTE
        // pattern means the OB is about to be invalidated → demote to WARNING.
        if (ind != null && price != null) {
            String lastBreak = ind.lastInternalBreakType();
            boolean chochBearRecent = lastBreak != null
                && lastBreak.toUpperCase(Locale.ROOT).contains(CHOCH_BEARISH);
            boolean vraieVenteHigh = pattern != null
                && pattern.type() == OrderFlowPattern.VRAIE_VENTE
                && pattern.confidence() == PatternAnalysis.Confidence.HIGH;

            for (IndicatorsSnapshot.OrderBlockView ob : ind.activeOrderBlocks()) {
                if (!"BULLISH".equalsIgnoreCase(ob.type())) continue;
                if (!"ACTIVE".equalsIgnoreCase(ob.status())) continue;
                Double low = ob.low(), high = ob.high();
                if (low == null || high == null) continue;
                if (price < low || price > high) continue;
                String evidence = String.format(Locale.US, "[%.1f-%.1f] price=%.1f", low, high, price);
                if (chochBearRecent || vraieVenteHigh) {
                    String why = chochBearRecent ? "CHoCH bear" : "VRAIE_VENTE high";
                    warnings.add(new StructuralWarning(
                        StructuralWarning.CODE_OB_BULL_OVERRIDDEN,
                        "OB bull " + evidence + " — override: " + why,
                        0));
                } else {
                    blocks.add(new StructuralBlock(
                        StructuralBlock.CODE_OB_BULL_FRESH, "OB bull " + evidence));
                }
                break;  // only the first matching OB matters
            }
        }

        // REGIME_CHOPPY — strategy 'regime-context' agent flagged CHOPPY.
        if (strategy != null) {
            for (StrategyVotes.Vote v : strategy.votes()) {
                if (!"regime-context".equals(v.agentId())) continue;
                String joined = String.join(" ", v.evidence()).toUpperCase(Locale.ROOT);
                if (joined.contains("CHOPPY")) {
                    blocks.add(new StructuralBlock(
                        StructuralBlock.CODE_REGIME_CHOPPY,
                        "regime-context vote: CHOPPY"));
                }
                break;  // single regime vote
            }
        }

        // MTF_BULL — ≥ 4/5 nested timeframes are BULLISH.
        if (ind != null) {
            Map<String, String> mtf = ind.multiResolutionBias();
            int bull = 0;
            for (String v : mtf.values()) {
                if ("BULLISH".equalsIgnoreCase(v)) bull++;
            }
            if (bull >= MTF_BULL_BLOCK) {
                blocks.add(new StructuralBlock(
                    StructuralBlock.CODE_MTF_BULL, bull + "/5 timeframes BULLISH"));
            }
        }

        // JAVA_NO_TRADE_CRITICAL — strategy decision = NO_TRADE with non-maintenance veto.
        if (strategy != null && "NO_TRADE".equalsIgnoreCase(strategy.decision())) {
            List<String> critical = new ArrayList<>();
            List<String> maintenance = new ArrayList<>();
            for (String veto : strategy.vetoReasons()) {
                if (veto == null) continue;
                if (veto.toLowerCase(Locale.ROOT).contains("maintenance")) {
                    maintenance.add(veto);
                } else {
                    critical.add(veto);
                }
            }
            if (!critical.isEmpty()) {
                blocks.add(new StructuralBlock(
                    StructuralBlock.CODE_JAVA_NO_TRADE,
                    "Java veto: " + truncate(critical.get(0), 80)));
            } else if (!maintenance.isEmpty()) {
                warnings.add(new StructuralWarning(
                    StructuralWarning.CODE_JAVA_MAINTENANCE,
                    "Java NO_TRADE: " + truncate(maintenance.get(0), 80),
                    0));
            }
        }

        // ── WARNINGS ──────────────────────────────────────────────────────

        if (ind != null && price != null) {
            // VWAP_FAR — price below lower band by > 1σ.
            Double vwap = ind.vwap();
            Double vwapLo = ind.vwapLowerBand();
            if (vwap != null && vwapLo != null && price < vwapLo) {
                double bandWidth = vwap - vwapLo;
                if (bandWidth > 0) {
                    double distSigma = (vwap - price) / bandWidth;
                    if (distSigma > VWAP_FAR_SIGMAS) {
                        warnings.add(new StructuralWarning(
                            StructuralWarning.CODE_VWAP_FAR,
                            String.format(Locale.US, "%.1fσ below VWAP lower (mean-reversion risk)", distSigma),
                            -2));
                        scoreMod -= 2;
                    }
                }
            }

            // BB_LOWER — Bollinger %B oversold.
            Double bbPct = ind.bbPct();
            if (bbPct != null && bbPct < BB_OVERSOLD) {
                warnings.add(new StructuralWarning(
                    StructuralWarning.CODE_BB_LOWER,
                    String.format(Locale.US, "bbPct=%.2f (oversold bounce risk)", bbPct),
                    -1));
                scoreMod -= 1;
            }

            // CMF — block if very bull, warn if positive.
            Double cmf = ind.cmf();
            if (cmf != null) {
                if (cmf > CMF_VERY_BULL) {
                    blocks.add(new StructuralBlock(
                        StructuralBlock.CODE_CMF_VERY_BULL,
                        String.format(Locale.US, "cmf=%+.3f (very strong buying flow)", cmf)));
                } else if (cmf > CMF_POSITIVE) {
                    warnings.add(new StructuralWarning(
                        StructuralWarning.CODE_CMF_POSITIVE,
                        String.format(Locale.US, "cmf=%+.3f (accumulation)", cmf),
                        -1));
                    scoreMod -= 1;
                }
            }

            // PRICE_IN_DISCOUNT — short into a smart-money buy zone.
            String zone = ind.currentZone();
            if ("DISCOUNT".equalsIgnoreCase(zone)) {
                warnings.add(new StructuralWarning(
                    StructuralWarning.CODE_PRICE_IN_DISCOUNT,
                    "price in DISCOUNT zone (smart-money buy zone)",
                    -2));
                scoreMod -= 2;
            }

            // SWING_BULL — short against the swing.
            if ("BULLISH".equalsIgnoreCase(ind.swingBias())) {
                warnings.add(new StructuralWarning(
                    StructuralWarning.CODE_SWING_BULL,
                    "swingBias=BULLISH (short against swing)",
                    -1));
                scoreMod -= 1;
            }

            // EQUAL_LOWS_NEAR — liquidity pool within proximity threshold.
            for (IndicatorsSnapshot.EqualLowView el : ind.equalLows()) {
                if (el.price() == null) continue;
                if (el.touchCount() < EQUAL_LOWS_MIN_TOUCH) continue;
                if (Math.abs(el.price() - price) >= EQUAL_LOWS_PROX_PTS) continue;
                warnings.add(new StructuralWarning(
                    StructuralWarning.CODE_EQUAL_LOWS_NEAR,
                    String.format(Locale.US, "EQL @ %.1f touch=%d (liquidity grab risk)",
                        el.price(), el.touchCount()),
                    -1));
                scoreMod -= 1;
                break;  // one warning is enough
            }
        }

        return new StructuralFilterResult(blocks, warnings, scoreMod, !blocks.isEmpty());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}

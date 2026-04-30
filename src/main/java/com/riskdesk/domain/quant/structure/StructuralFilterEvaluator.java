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
    /** LONG mirror — OB BEAR block demoted when CHoCH bull is recent OR high-confidence VRAI_ACHAT in play. */
    private static final String CHOCH_BULLISH = "CHOCH_BULLISH";
    /** Multi-resolution bias threshold — at least this many BULLISH legs blocks SHORT. */
    private static final int MTF_BULL_BLOCK   = 4;
    /** LONG mirror — at least this many BEARISH legs blocks LONG. */
    private static final int MTF_BEAR_BLOCK   = 4;
    /** CMF threshold above which buying flow is considered structurally dominant. */
    private static final double CMF_VERY_BULL = 0.15;
    /** CMF threshold above which buying flow becomes a warning (but not a block). */
    private static final double CMF_POSITIVE  = 0.05;
    /** LONG mirror — CMF below which selling flow is structurally dominant (blocks LONG). */
    private static final double CMF_VERY_BEAR = -0.15;
    /** LONG mirror — CMF below which selling flow becomes a warning (blocks not yet). */
    private static final double CMF_NEGATIVE  = -0.05;
    /** Bollinger %B below which we flag oversold-bounce risk. */
    private static final double BB_OVERSOLD   = 0.15;
    /** LONG mirror — Bollinger %B above which we flag overbought-pullback risk. */
    private static final double BB_OVERBOUGHT = 0.85;
    /** Number of σ below VWAP-lower below which we flag mean-reversion risk. */
    private static final double VWAP_FAR_SIGMAS = 1.0;
    /** Distance in points below which an equal-low pool is considered "near". */
    private static final double EQUAL_LOWS_PROX_PTS = 15.0;
    /** Minimum touch count for an equal-low pool to count as a liquidity grab risk. */
    private static final int EQUAL_LOWS_MIN_TOUCH = 2;

    // ── Kill-zone override gate (high-conviction bypass of 5m-outside-kill-zone) ──
    /** Veto-reason prefix that marks the 5m kill-zone gate firing. */
    private static final String VETO_KILL_ZONE_PREFIX = "5m-outside-kill-zone";
    /** Minimum 7-gate raw score required to even consider overriding the kill-zone veto. */
    private static final int OVERRIDE_MIN_SCORE = 7;
    /** Number of nested timeframes (out of 5) that must agree with the trade direction. */
    private static final int OVERRIDE_MTF_FULL = 5;
    /** Minimum CMF magnitude for the flow to count as confirming the trade direction. */
    private static final double OVERRIDE_CMF_MAG = 0.10;

    /** Trade direction used by {@link #qualifiesForKillZoneOverride(int, IndicatorsSnapshot, Direction)}. */
    private enum Direction { LONG, SHORT }

    /**
     * Evaluates the structural filters for a SHORT setup. Backward-compat
     * overload — equivalent to {@link #evaluateForShort(Double, IndicatorsSnapshot,
     * StrategyVotes, PatternAnalysis, int)} with {@code score=0}, which means
     * the kill-zone override (which requires {@code score >= 7}) never fires.
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
        return evaluateForShort(price, ind, strategy, pattern, 0);
    }

    /**
     * Evaluates the structural filters for a SHORT setup, with the explicit
     * 7-gate raw {@code score} so the kill-zone override gate can fire on
     * high-conviction setups.
     *
     * <p>When the strategy decision is {@code NO_TRADE} and the ONLY critical
     * veto is {@code 5m-outside-kill-zone}, the {@code JAVA_NO_TRADE_CRITICAL}
     * block is demoted to a {@code JAVA_NO_TRADE_OVERRIDDEN} warning iff ALL
     * of {@link #qualifiesForKillZoneOverride} hold:</p>
     * <ul>
     *   <li>{@code score >= 7} (perfect quant setup)</li>
     *   <li>5/5 nested timeframes are BEARISH</li>
     *   <li>{@code lastInternalBreakType} is {@code CHOCH_BEARISH} or {@code BOS_BEARISH}</li>
     *   <li>{@code cmf <= -0.10} (selling-flow confirmation)</li>
     * </ul>
     * <p>Any other critical veto, or a missing condition, preserves the
     * existing block behavior.</p>
     */
    public StructuralFilterResult evaluateForShort(Double price,
                                                   IndicatorsSnapshot ind,
                                                   StrategyVotes strategy,
                                                   PatternAnalysis pattern,
                                                   int score) {
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
                if (isOnlyKillZoneVeto(critical)
                    && qualifiesForKillZoneOverride(score, ind, Direction.SHORT)) {
                    warnings.add(new StructuralWarning(
                        StructuralWarning.CODE_JAVA_NO_TRADE_OVERRIDDEN,
                        "5m-outside-kill-zone overridden — score " + score
                            + "/7, MTF 5/5, structure confirmed, CMF aligned",
                        0));
                } else {
                    blocks.add(new StructuralBlock(
                        StructuralBlock.CODE_JAVA_NO_TRADE,
                        "Java veto: " + truncate(critical.get(0), 80)));
                }
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

    /**
     * LONG mirror of {@link #evaluateForShort}. Same indicator/strategy/pattern
     * inputs but evaluates the structural conditions from the LONG side: a
     * fresh BEARISH OB with the price inside it blocks LONG (override on
     * recent CHoCH BULL or HIGH-confidence VRAI_ACHAT), MTF ≥ 4/5 BEAR blocks
     * LONG, very-bear CMF blocks LONG, etc. Symmetric thresholds; the
     * REGIME_CHOPPY and JAVA_NO_TRADE_CRITICAL blocks are direction-agnostic
     * and fire identically here.
     *
     * <p>The {@link StructuralFilterResult#shortBlocked} flag in the returned
     * value reads as "this direction is blocked" — i.e. for the LONG result it
     * means LONG is blocked. The presentation layer renames it to
     * {@code longBlocked} when projecting to the wire DTO.</p>
     */
    public StructuralFilterResult evaluateForLong(Double price,
                                                   IndicatorsSnapshot ind,
                                                   StrategyVotes strategy,
                                                   PatternAnalysis pattern) {
        return evaluateForLong(price, ind, strategy, pattern, 0);
    }

    /**
     * LONG variant of {@link #evaluateForShort(Double, IndicatorsSnapshot,
     * StrategyVotes, PatternAnalysis, int)} that also accepts the LONG raw
     * 7-gate score so the kill-zone override may demote
     * {@code JAVA_NO_TRADE_CRITICAL} to a warning when the LONG setup is
     * structurally high-conviction (5/5 BULLISH MTF, CHoCH/BOS bullish, CMF
     * &gt;= +0.10, score &gt;= 7).
     */
    public StructuralFilterResult evaluateForLong(Double price,
                                                   IndicatorsSnapshot ind,
                                                   StrategyVotes strategy,
                                                   PatternAnalysis pattern,
                                                   int score) {
        List<StructuralBlock> blocks = new ArrayList<>();
        List<StructuralWarning> warnings = new ArrayList<>();
        int scoreMod = 0;

        // ── BLOCKS ────────────────────────────────────────────────────────

        // OB_BEAR_FRESH — active bearish OB containing the current price.
        // Override: a recent CHoCH bull or a high-confidence VRAI_ACHAT
        // pattern means the OB is about to be invalidated → demote to WARNING.
        if (ind != null && price != null) {
            String lastBreak = ind.lastInternalBreakType();
            boolean chochBullRecent = lastBreak != null
                && lastBreak.toUpperCase(Locale.ROOT).contains(CHOCH_BULLISH);
            boolean vraiAchatHigh = pattern != null
                && pattern.type() == OrderFlowPattern.VRAI_ACHAT
                && pattern.confidence() == PatternAnalysis.Confidence.HIGH;

            for (IndicatorsSnapshot.OrderBlockView ob : ind.activeOrderBlocks()) {
                if (!"BEARISH".equalsIgnoreCase(ob.type())) continue;
                if (!"ACTIVE".equalsIgnoreCase(ob.status())) continue;
                Double low = ob.low(), high = ob.high();
                if (low == null || high == null) continue;
                if (price < low || price > high) continue;
                String evidence = String.format(Locale.US, "[%.1f-%.1f] price=%.1f", low, high, price);
                if (chochBullRecent || vraiAchatHigh) {
                    String why = chochBullRecent ? "CHoCH bull" : "VRAI_ACHAT high";
                    warnings.add(new StructuralWarning(
                        StructuralWarning.CODE_OB_BEAR_OVERRIDDEN,
                        "OB bear " + evidence + " — override: " + why,
                        0));
                } else {
                    blocks.add(new StructuralBlock(
                        StructuralBlock.CODE_OB_BEAR_FRESH, "OB bear " + evidence));
                }
                break;
            }
        }

        // REGIME_CHOPPY — direction-agnostic, blocks LONG too.
        if (strategy != null) {
            for (StrategyVotes.Vote v : strategy.votes()) {
                if (!"regime-context".equals(v.agentId())) continue;
                String joined = String.join(" ", v.evidence()).toUpperCase(Locale.ROOT);
                if (joined.contains("CHOPPY")) {
                    blocks.add(new StructuralBlock(
                        StructuralBlock.CODE_REGIME_CHOPPY,
                        "regime-context vote: CHOPPY"));
                }
                break;
            }
        }

        // MTF_BEAR — ≥ 4/5 nested timeframes are BEARISH.
        if (ind != null) {
            Map<String, String> mtf = ind.multiResolutionBias();
            int bear = 0;
            for (String v : mtf.values()) {
                if ("BEARISH".equalsIgnoreCase(v)) bear++;
            }
            if (bear >= MTF_BEAR_BLOCK) {
                blocks.add(new StructuralBlock(
                    StructuralBlock.CODE_MTF_BEAR, bear + "/5 timeframes BEARISH"));
            }
        }

        // JAVA_NO_TRADE_CRITICAL — same critical/maintenance split as SHORT.
        // Mirror of the SHORT kill-zone override: if the only critical veto
        // is "5m-outside-kill-zone" AND the LONG setup is structurally
        // high-conviction (qualifiesForKillZoneOverride), demote to warning.
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
                if (isOnlyKillZoneVeto(critical)
                    && qualifiesForKillZoneOverride(score, ind, Direction.LONG)) {
                    warnings.add(new StructuralWarning(
                        StructuralWarning.CODE_JAVA_NO_TRADE_OVERRIDDEN,
                        "5m-outside-kill-zone overridden — score " + score
                            + "/7, MTF 5/5, structure confirmed, CMF aligned",
                        0));
                } else {
                    blocks.add(new StructuralBlock(
                        StructuralBlock.CODE_JAVA_NO_TRADE,
                        "Java veto: " + truncate(critical.get(0), 80)));
                }
            } else if (!maintenance.isEmpty()) {
                warnings.add(new StructuralWarning(
                    StructuralWarning.CODE_JAVA_MAINTENANCE,
                    "Java NO_TRADE: " + truncate(maintenance.get(0), 80),
                    0));
            }
        }

        // ── WARNINGS ──────────────────────────────────────────────────────

        if (ind != null && price != null) {
            // VWAP_FAR_ABOVE — price above upper band by > 1σ.
            Double vwap = ind.vwap();
            Double vwapHi = ind.vwapUpperBand();
            if (vwap != null && vwapHi != null && price > vwapHi) {
                double bandWidth = vwapHi - vwap;
                if (bandWidth > 0) {
                    double distSigma = (price - vwap) / bandWidth;
                    if (distSigma > VWAP_FAR_SIGMAS) {
                        warnings.add(new StructuralWarning(
                            StructuralWarning.CODE_VWAP_FAR_ABOVE,
                            String.format(Locale.US, "%.1fσ above VWAP upper (mean-reversion risk)", distSigma),
                            -2));
                        scoreMod -= 2;
                    }
                }
            }

            // BB_UPPER — Bollinger %B overbought.
            Double bbPct = ind.bbPct();
            if (bbPct != null && bbPct > BB_OVERBOUGHT) {
                warnings.add(new StructuralWarning(
                    StructuralWarning.CODE_BB_UPPER,
                    String.format(Locale.US, "bbPct=%.2f (overbought pullback risk)", bbPct),
                    -1));
                scoreMod -= 1;
            }

            // CMF — block if very bear, warn if negative.
            Double cmf = ind.cmf();
            if (cmf != null) {
                if (cmf < CMF_VERY_BEAR) {
                    blocks.add(new StructuralBlock(
                        StructuralBlock.CODE_CMF_VERY_BEAR,
                        String.format(Locale.US, "cmf=%+.3f (very strong selling flow)", cmf)));
                } else if (cmf < CMF_NEGATIVE) {
                    warnings.add(new StructuralWarning(
                        StructuralWarning.CODE_CMF_NEGATIVE,
                        String.format(Locale.US, "cmf=%+.3f (distribution)", cmf),
                        -1));
                    scoreMod -= 1;
                }
            }

            // PRICE_IN_PREMIUM — long into a smart-money sell zone.
            String zone = ind.currentZone();
            if ("PREMIUM".equalsIgnoreCase(zone)) {
                warnings.add(new StructuralWarning(
                    StructuralWarning.CODE_PRICE_IN_PREMIUM,
                    "price in PREMIUM zone (smart-money sell zone)",
                    -2));
                scoreMod -= 2;
            }

            // SWING_BEAR — long against the swing.
            if ("BEARISH".equalsIgnoreCase(ind.swingBias())) {
                warnings.add(new StructuralWarning(
                    StructuralWarning.CODE_SWING_BEAR,
                    "swingBias=BEARISH (long against swing)",
                    -1));
                scoreMod -= 1;
            }

            // EQUAL_HIGHS_NEAR — liquidity pool within proximity threshold.
            for (IndicatorsSnapshot.EqualHighView eh : ind.equalHighs()) {
                if (eh.price() == null) continue;
                if (eh.touchCount() < EQUAL_LOWS_MIN_TOUCH) continue;
                if (Math.abs(eh.price() - price) >= EQUAL_LOWS_PROX_PTS) continue;
                warnings.add(new StructuralWarning(
                    StructuralWarning.CODE_EQUAL_HIGHS_NEAR,
                    String.format(Locale.US, "EQH @ %.1f touch=%d (liquidity grab risk)",
                        eh.price(), eh.touchCount()),
                    -1));
                scoreMod -= 1;
                break;
            }
        }

        return new StructuralFilterResult(blocks, warnings, scoreMod, !blocks.isEmpty());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * {@code true} when the entire critical-veto list reduces to the single
     * {@code 5m-outside-kill-zone} reason (case-insensitive prefix match). The
     * caller has already split out maintenance reasons; this asks "is the only
     * thing standing between us and a TRADE the kill-zone gate?". A defensive
     * empty-list check returns false (no veto = no override needed).
     */
    private static boolean isOnlyKillZoneVeto(List<String> criticalVetos) {
        if (criticalVetos == null || criticalVetos.isEmpty()) return false;
        for (String v : criticalVetos) {
            if (v == null) return false;
            String norm = v.toLowerCase(Locale.ROOT).trim();
            if (!norm.startsWith(VETO_KILL_ZONE_PREFIX)) return false;
        }
        return true;
    }

    /**
     * Strict 4-condition gate that authorises demoting the kill-zone block to
     * a warning. Designed to fire only on genuinely high-conviction setups —
     * deliberately conservative.
     *
     * <p>For SHORT all of: {@code score >= 7}, 5/5 BEARISH MTF,
     * {@code lastInternalBreakType} contains {@code BEARISH} (CHoCH or BOS),
     * and {@code cmf <= -0.10}. For LONG the symmetric BULLISH conditions.
     * A null indicator snapshot or any missing field fails the gate.</p>
     */
    private static boolean qualifiesForKillZoneOverride(int score,
                                                         IndicatorsSnapshot ind,
                                                         Direction direction) {
        if (score < OVERRIDE_MIN_SCORE) return false;
        if (ind == null) return false;

        // Condition: full 5/5 multi-resolution alignment with the trade direction.
        Map<String, String> mtf = ind.multiResolutionBias();
        if (mtf == null || mtf.size() < OVERRIDE_MTF_FULL) return false;
        String wantBias = direction == Direction.SHORT ? "BEARISH" : "BULLISH";
        int aligned = 0;
        for (String v : mtf.values()) {
            if (wantBias.equalsIgnoreCase(v)) aligned++;
        }
        if (aligned < OVERRIDE_MTF_FULL) return false;

        // Condition: structure confirmed (CHoCH or BOS in the trade direction).
        String lastBreak = ind.lastInternalBreakType();
        if (lastBreak == null) return false;
        String upper = lastBreak.toUpperCase(Locale.ROOT);
        boolean structureOk = direction == Direction.SHORT
            ? (upper.contains("BEARISH") && (upper.contains("CHOCH") || upper.contains("BOS")))
            : (upper.contains("BULLISH") && (upper.contains("CHOCH") || upper.contains("BOS")));
        if (!structureOk) return false;

        // Condition: CMF magnitude confirms the flow direction.
        Double cmf = ind.cmf();
        if (cmf == null) return false;
        if (direction == Direction.SHORT) {
            return cmf <= -OVERRIDE_CMF_MAG;
        } else {
            return cmf >= OVERRIDE_CMF_MAG;
        }
    }
}

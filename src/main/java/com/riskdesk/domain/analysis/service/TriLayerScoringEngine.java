package com.riskdesk.domain.analysis.service;

import com.riskdesk.domain.analysis.model.Contradiction;
import com.riskdesk.domain.analysis.model.Direction;
import com.riskdesk.domain.analysis.model.DirectionalBias;
import com.riskdesk.domain.analysis.model.Factor;
import com.riskdesk.domain.analysis.model.Factor.Polarity;
import com.riskdesk.domain.analysis.model.IndicatorSnapshot;
import com.riskdesk.domain.analysis.model.LiveAnalysisSnapshot;
import com.riskdesk.domain.analysis.model.MomentumScore;
import com.riskdesk.domain.analysis.model.OrderFlowContext;
import com.riskdesk.domain.analysis.model.OrderFlowEventSummary;
import com.riskdesk.domain.analysis.model.OrderFlowScore;
import com.riskdesk.domain.analysis.model.ScoreComponent;
import com.riskdesk.domain.analysis.model.ScoringWeights;
import com.riskdesk.domain.analysis.model.SmcContext;
import com.riskdesk.domain.analysis.model.StructureScore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-domain tri-layer scoring engine — translates the discretionary trader
 * framework "Structure 50% / Order Flow 30% / Momentum 20%" into a deterministic
 * function {@code (LiveAnalysisSnapshot, ScoringWeights) → DirectionalBias}.
 * <p>
 * No Spring, no I/O, no clock access. Every input comes from the snapshot.
 * Every output is reproducible — same input ⇒ same output.
 */
public final class TriLayerScoringEngine {

    /** Bumped when a rule change breaks replay determinism. */
    public static final int CURRENT_VERSION = 1;

    /** Below this absolute weighted score, we report NEUTRAL (stand-aside). */
    private static final double STAND_ASIDE_BAND = 15.0;

    /** Each contradiction subtracts this many points from confidence. */
    private static final double CONTRADICTION_PENALTY = 8.0;
    private static final double MAX_PENALTY = 30.0;

    /** Number of contradictions above which we force NEUTRAL regardless of score. */
    private static final int FORCE_NEUTRAL_CONTRADICTIONS = 4;

    private final ScoringWeights weights;

    public TriLayerScoringEngine(ScoringWeights weights) {
        this.weights = Objects.requireNonNull(weights);
    }

    public TriLayerScoringEngine() {
        this(ScoringWeights.defaults());
    }

    public DirectionalBias score(LiveAnalysisSnapshot snap) {
        Objects.requireNonNull(snap);

        StructureScore structure = scoreStructure(snap.smc());
        OrderFlowScore orderFlow = scoreOrderFlow(snap.orderFlow(),
            snap.momentumWindow(), snap.absorptionWindow(),
            snap.distributionRecent(), snap.cycleRecent());
        MomentumScore momentum = scoreMomentum(snap.indicators());

        double weighted = weights.structure() * structure.value()
                        + weights.orderFlow() * orderFlow.value()
                        + weights.momentum()  * momentum.value();

        List<Contradiction> contradictions = detectContradictions(structure, orderFlow, momentum, snap);
        double penalty = Math.min(MAX_PENALTY, contradictions.size() * CONTRADICTION_PENALTY);

        // Stand-aside short-circuit
        if (Math.abs(weighted) < STAND_ASIDE_BAND) {
            return DirectionalBias.standAside(snap.instrument(), snap.timeframe(),
                snap.decisionTimestamp(), structure, orderFlow, momentum,
                contradictions,
                String.format("|weighted|=%.1f below stand-aside band of %.1f",
                    Math.abs(weighted), STAND_ASIDE_BAND));
        }
        if (contradictions.size() >= FORCE_NEUTRAL_CONTRADICTIONS) {
            return DirectionalBias.standAside(snap.instrument(), snap.timeframe(),
                snap.decisionTimestamp(), structure, orderFlow, momentum,
                contradictions,
                contradictions.size() + " contradictions exceed tolerance");
        }

        Direction primary = weighted > 0 ? Direction.LONG : Direction.SHORT;
        int confidence = (int) Math.max(0, Math.min(100, Math.abs(weighted) - penalty));

        List<Factor> bullish = collectFactors(structure, orderFlow, momentum, Polarity.BULLISH);
        List<Factor> bearish = collectFactors(structure, orderFlow, momentum, Polarity.BEARISH);

        return new DirectionalBias(snap.instrument(), snap.timeframe(),
            snap.decisionTimestamp(), primary, confidence,
            structure, orderFlow, momentum,
            bullish, bearish, contradictions, null);
    }

    // ---------------------------------------------------------------------
    // Layer 1 — Structure SMC
    // ---------------------------------------------------------------------

    StructureScore scoreStructure(SmcContext smc) {
        if (smc == null) return StructureScore.neutral();

        List<ScoreComponent> components = new ArrayList<>();
        double score = 0.0;

        // Multi-resolution coherence — up to ±30
        Map<String, String> mr = smc.multiResolutionBias();
        if (mr != null && !mr.isEmpty()) {
            int bullish = (int) mr.values().stream().filter("BULLISH"::equals).count();
            int bearish = (int) mr.values().stream().filter("BEARISH"::equals).count();
            int total = mr.size();
            // Normalise to [-1, +1] then scale to [-30, +30]
            double net = ((double) (bullish - bearish)) / Math.max(1, total);
            double contribution = clamp(net * 30.0, -30.0, 30.0);
            score += contribution;
            components.add(new ScoreComponent("multiResolutionBias", contribution,
                bullish + " bullish vs " + bearish + " bearish across " + total + " horizons"));
        }

        // Internal vs swing alignment — up to ±10 for confluence, -10 if conflict
        if (smc.internalBias() != null && smc.swingBias() != null) {
            double contribution;
            String rationale;
            if (smc.internalBias().equals(smc.swingBias())) {
                contribution = "BULLISH".equals(smc.internalBias()) ? 10.0 :
                               "BEARISH".equals(smc.internalBias()) ? -10.0 : 0.0;
                rationale = "Internal and swing aligned " + smc.internalBias();
            } else {
                contribution = 0.0;
                rationale = "Internal " + smc.internalBias() + " vs swing " + smc.swingBias();
            }
            score += contribution;
            if (contribution != 0.0) {
                components.add(new ScoreComponent("internalSwingAlignment", contribution, rationale));
            }
        }

        // Zone — premium ⇒ short bias, discount ⇒ long bias, up to ±15
        if (smc.currentZone() != null) {
            double contribution = switch (smc.currentZone()) {
                case "DISCOUNT" -> 15.0;
                case "PREMIUM" -> -15.0;
                default -> 0.0;
            };
            if (contribution != 0.0) {
                score += contribution;
                components.add(new ScoreComponent("zone", contribution,
                    "Price in " + smc.currentZone() + " zone"));
            }
        }

        // Active OBs — sum signed contributions, capped at ±25
        if (smc.activeOrderBlocks() != null && !smc.activeOrderBlocks().isEmpty()) {
            double obContribution = 0.0;
            for (var ob : smc.activeOrderBlocks()) {
                double sign = "BULLISH".equals(ob.type()) ? 1.0 : -1.0;
                obContribution += sign * (ob.obLiveScore() / 20.0);  // each OB contributes up to ±5
            }
            obContribution = clamp(obContribution, -25.0, 25.0);
            score += obContribution;
            components.add(new ScoreComponent("activeOrderBlocks", obContribution,
                smc.activeOrderBlocks().size() + " active order blocks"));
        }

        // Active FVGs — directional contribution, capped at ±15
        if (smc.activeFvgs() != null && !smc.activeFvgs().isEmpty()) {
            double fvgContribution = 0.0;
            for (var fvg : smc.activeFvgs()) {
                double sign = "BULLISH".equals(fvg.bias()) ? 1.0 : -1.0;
                fvgContribution += sign * (fvg.quality() / 30.0);
            }
            fvgContribution = clamp(fvgContribution, -15.0, 15.0);
            score += fvgContribution;
            components.add(new ScoreComponent("activeFvgs", fvgContribution,
                smc.activeFvgs().size() + " active fair value gaps"));
        }

        // Recent break confirmation — up to ±15 from confirmed breaks only
        if (smc.recentBreaks() != null && !smc.recentBreaks().isEmpty()) {
            double breakContribution = 0.0;
            int considered = 0;
            for (var br : smc.recentBreaks()) {
                if (!br.confirmed()) continue;
                double sign = "BULLISH".equals(br.trend()) ? 1.0 : -1.0;
                breakContribution += sign * (br.confidence() / 25.0);
                if (++considered >= 3) break;
            }
            breakContribution = clamp(breakContribution, -15.0, 15.0);
            if (breakContribution != 0.0) {
                score += breakContribution;
                components.add(new ScoreComponent("recentBreaks", breakContribution,
                    considered + " confirmed structural breaks"));
            }
        }

        return new StructureScore(clamp(score, -100.0, 100.0), components);
    }

    // ---------------------------------------------------------------------
    // Layer 2 — Order Flow
    // ---------------------------------------------------------------------

    OrderFlowScore scoreOrderFlow(OrderFlowContext ctx,
                                   List<OrderFlowEventSummary> momentumWindow,
                                   List<OrderFlowEventSummary> absorptionWindow,
                                   List<OrderFlowEventSummary> distributionRecent,
                                   List<OrderFlowEventSummary> cycleRecent) {

        List<ScoreComponent> components = new ArrayList<>();
        double score = 0.0;

        // Recent momentum sequence — up to ±35 (heaviest weight in this layer)
        if (momentumWindow != null && !momentumWindow.isEmpty()) {
            double momentumContribution = 0.0;
            int n = Math.min(10, momentumWindow.size());
            for (int i = 0; i < n; i++) {
                var ev = momentumWindow.get(i);
                double recencyWeight = 1.0 - ((double) i / n);  // newer events weigh more
                double sign = "BULLISH_MOMENTUM".equals(ev.side()) ? 1.0 : -1.0;
                momentumContribution += sign * Math.min(ev.score(), 100.0) * 0.35 * recencyWeight;
            }
            momentumContribution = clamp(momentumContribution, -35.0, 35.0);
            score += momentumContribution;
            components.add(new ScoreComponent("momentumSequence", momentumContribution,
                n + " recent momentum events"));
        }

        // Recent absorption — up to ±25
        if (absorptionWindow != null && !absorptionWindow.isEmpty()) {
            double absorptionContribution = 0.0;
            int n = Math.min(5, absorptionWindow.size());
            for (int i = 0; i < n; i++) {
                var ev = absorptionWindow.get(i);
                // BULLISH_ABSORPTION = passive buyers absorbing → bullish for next move
                double sign = "BULLISH_ABSORPTION".equals(ev.side()) ? 1.0 : -1.0;
                absorptionContribution += sign * Math.min(ev.score(), 10.0) * 1.0;
            }
            absorptionContribution = clamp(absorptionContribution, -25.0, 25.0);
            score += absorptionContribution;
            components.add(new ScoreComponent("absorption", absorptionContribution,
                n + " recent absorption events"));
        }

        // Distribution / accumulation events — directional, weighted by confidence
        if (distributionRecent != null && !distributionRecent.isEmpty()) {
            double distContribution = 0.0;
            for (var ev : distributionRecent) {
                double sign = "ACCUMULATION".equals(ev.side()) ? 1.0
                            : "DISTRIBUTION".equals(ev.side()) ? -1.0 : 0.0;
                distContribution += sign * (ev.score() / 5.0);  // confidence 0-100 → ±20
            }
            distContribution = clamp(distContribution, -20.0, 20.0);
            score += distContribution;
            components.add(new ScoreComponent("distribution", distContribution,
                distributionRecent.size() + " dist/accum events"));
        }

        // Smart money cycle — high signal value, capped at ±15
        if (cycleRecent != null && !cycleRecent.isEmpty()) {
            double cycleContribution = 0.0;
            for (var ev : cycleRecent) {
                double sign = "BULLISH_CYCLE".equals(ev.side()) ? 1.0
                            : "BEARISH_CYCLE".equals(ev.side()) ? -1.0 : 0.0;
                cycleContribution += sign * (ev.score() / 8.0);
            }
            cycleContribution = clamp(cycleContribution, -15.0, 15.0);
            score += cycleContribution;
            components.add(new ScoreComponent("smartMoneyCycle", cycleContribution,
                cycleRecent.size() + " recent cycles"));
        }

        // Live delta + divergence
        if (ctx != null) {
            double deltaContribution = 0.0;
            if (ctx.deltaTrend() != null) {
                deltaContribution = switch (ctx.deltaTrend()) {
                    case "RISING" -> 5.0;
                    case "FALLING" -> -5.0;
                    default -> 0.0;
                };
            }
            // Divergence is meaningful counter-signal
            if (ctx.divergenceDetected()) {
                if ("BULLISH_DIVERGENCE".equals(ctx.divergenceType())) deltaContribution += 8.0;
                else if ("BEARISH_DIVERGENCE".equals(ctx.divergenceType())) deltaContribution -= 8.0;
            }
            // Depth imbalance bias
            if (ctx.depthImbalance() != null) {
                deltaContribution += clamp(ctx.depthImbalance() * 5.0, -5.0, 5.0);
            }
            if (deltaContribution != 0.0) {
                score += deltaContribution;
                components.add(new ScoreComponent("liveDelta", deltaContribution,
                    "trend=" + ctx.deltaTrend() + " div=" + ctx.divergenceType()));
            }
        }

        return new OrderFlowScore(clamp(score, -100.0, 100.0), components);
    }

    // ---------------------------------------------------------------------
    // Layer 3 — Momentum (indicators)
    // ---------------------------------------------------------------------

    MomentumScore scoreMomentum(IndicatorSnapshot ind) {
        if (ind == null) return MomentumScore.neutral();

        List<ScoreComponent> components = new ArrayList<>();
        double score = 0.0;

        // RSI — pivots at 50, capped at ±25 (extreme overbought/oversold reduces edge)
        if (ind.rsi() != null) {
            double rsi = ind.rsi();
            double contribution;
            if (rsi >= 70) contribution = -10.0;            // overbought, fade longs
            else if (rsi <= 30) contribution = 10.0;        // oversold, fade shorts
            else contribution = (rsi - 50.0) * 0.4;         // up to ±8 in the trending zone
            score += contribution;
            components.add(new ScoreComponent("rsi", contribution, "RSI " + String.format("%.1f", rsi)));
        }

        // MACD histogram — direct sign signal, up to ±15
        if (ind.macdHistogram() != null) {
            double hist = ind.macdHistogram();
            double contribution = clamp(hist * 1.5, -15.0, 15.0);
            score += contribution;
            components.add(new ScoreComponent("macdHistogram", contribution,
                "MACD hist " + String.format("%.2f", hist)));
        }

        // Supertrend — directional anchor, up to ±15
        if (ind.supertrendBullish() != null) {
            double contribution = ind.supertrendBullish() ? 15.0 : -15.0;
            score += contribution;
            components.add(new ScoreComponent("supertrend", contribution,
                "Supertrend " + (ind.supertrendBullish() ? "bullish" : "bearish")));
        }

        // CMF (Chaikin Money Flow) — volume-weighted directional bias, up to ±15
        if (ind.cmf() != null) {
            double contribution = clamp(ind.cmf() * 50.0, -15.0, 15.0);
            score += contribution;
            components.add(new ScoreComponent("cmf", contribution, "CMF " + String.format("%.2f", ind.cmf())));
        }

        // Stochastic — extremes only, up to ±10
        if (ind.stochK() != null) {
            double k = ind.stochK();
            double contribution = 0.0;
            if (k >= 80) contribution = -8.0;
            else if (k <= 20) contribution = 8.0;
            if (contribution != 0.0) {
                score += contribution;
                components.add(new ScoreComponent("stoch", contribution, "Stoch K " + String.format("%.1f", k)));
            }
        }

        // WaveTrend — cross direction, up to ±10
        if (ind.wt1() != null && ind.wt2() != null) {
            double diff = ind.wt1() - ind.wt2();
            double contribution = clamp(diff * 0.3, -10.0, 10.0);
            if (contribution != 0.0) {
                score += contribution;
                components.add(new ScoreComponent("waveTrend", contribution,
                    "WT diff " + String.format("%.1f", diff)));
            }
        }

        return new MomentumScore(clamp(score, -100.0, 100.0), components);
    }

    // ---------------------------------------------------------------------
    // Cross-layer contradictions
    // ---------------------------------------------------------------------

    List<Contradiction> detectContradictions(StructureScore s, OrderFlowScore of, MomentumScore m,
                                              LiveAnalysisSnapshot snap) {
        List<Contradiction> contradictions = new ArrayList<>();

        // Layer signs disagree by >40 in absolute terms
        if (Math.signum(s.value()) != 0 && Math.signum(of.value()) != 0
            && Math.signum(s.value()) != Math.signum(of.value())
            && Math.abs(s.value()) > 20 && Math.abs(of.value()) > 20) {
            contradictions.add(new Contradiction("Structure", "OrderFlow",
                "Structure " + (s.value() > 0 ? "bullish" : "bearish")
                + " vs Order Flow " + (of.value() > 0 ? "bullish" : "bearish")));
        }
        if (Math.signum(of.value()) != 0 && Math.signum(m.value()) != 0
            && Math.signum(of.value()) != Math.signum(m.value())
            && Math.abs(of.value()) > 20 && Math.abs(m.value()) > 20) {
            contradictions.add(new Contradiction("OrderFlow", "Momentum",
                "Order Flow vs Momentum disagreement"));
        }

        // Multi-resolution split — at least one strong horizon contradicts the rest
        if (snap.smc() != null && snap.smc().multiResolutionBias() != null) {
            var mr = snap.smc().multiResolutionBias();
            String swingMacro = mr.getOrDefault("swing50", "NEUTRAL");
            String microIntra = mr.getOrDefault("micro1", "NEUTRAL");
            if (!swingMacro.equals("NEUTRAL") && !microIntra.equals("NEUTRAL")
                && !swingMacro.equals(microIntra)) {
                contradictions.add(new Contradiction("Structure-MTF", "Structure-MTF",
                    "swing50 " + swingMacro + " vs micro1 " + microIntra));
            }
        }

        // Indicator extreme overbought + bullish layers above — overextension warning
        if (snap.indicators() != null && snap.indicators().rsi() != null
            && snap.indicators().rsi() >= 75 && (s.value() + of.value()) > 30) {
            contradictions.add(new Contradiction("Momentum", "OverallBias",
                "RSI overbought (" + String.format("%.0f", snap.indicators().rsi())
                + ") while bias bullish — overextension"));
        }
        if (snap.indicators() != null && snap.indicators().rsi() != null
            && snap.indicators().rsi() <= 25 && (s.value() + of.value()) < -30) {
            contradictions.add(new Contradiction("Momentum", "OverallBias",
                "RSI oversold while bias bearish — capitulation risk"));
        }

        return contradictions;
    }

    // ---------------------------------------------------------------------
    // Factor list — turn ScoreComponents into the BULL/BEAR list shown in UI
    // ---------------------------------------------------------------------

    private List<Factor> collectFactors(StructureScore s, OrderFlowScore of, MomentumScore m,
                                          Polarity wantPolarity) {
        List<Factor> factors = new ArrayList<>();
        appendFactors(factors, s.components(), "Structure", wantPolarity);
        appendFactors(factors, of.components(), "OrderFlow", wantPolarity);
        appendFactors(factors, m.components(), "Momentum", wantPolarity);
        factors.sort(Comparator.comparingDouble(Factor::strength).reversed());
        return factors;
    }

    private void appendFactors(List<Factor> out, List<ScoreComponent> components,
                                String layer, Polarity wantPolarity) {
        for (var c : components) {
            if (c.contribution() == 0.0) continue;
            Polarity p = c.contribution() > 0 ? Polarity.BULLISH : Polarity.BEARISH;
            if (p != wantPolarity) continue;
            double strength = Math.abs(c.contribution());
            out.add(new Factor(p, layer, c.rationale(), strength));
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}

package com.riskdesk.domain.quant.pattern;

import com.riskdesk.domain.quant.model.MarketSnapshot;
import com.riskdesk.domain.quant.model.QuantState;

import java.util.List;

/**
 * Deterministic order-flow pattern classifier.
 * <p>
 * Looks at the last three delta observations versus the last three observed
 * prices. The four meaningful regimes are:
 *
 * <pre>
 *   delta &lt; 0  AND  price stable / up   →  ABSORPTION_HAUSSIERE
 *   delta &gt; 0  AND  price stable / down →  DISTRIBUTION_SILENCIEUSE
 *   delta &lt; 0  AND  price down          →  VRAIE_VENTE
 *   delta &gt; 0  AND  price up            →  VRAI_ACHAT
 * </pre>
 *
 * Confidence scales with the magnitude of the divergence; an action is
 * recommended on top so the UI can render a single cohesive verdict.
 */
public final class OrderFlowPatternDetector {

    /** Price move (points) below which we consider price "stable". */
    public static final double PRICE_STABLE_BAND = 5.0;
    /** |delta| above which a single-scan signal is treated as strong. */
    public static final double DELTA_STRONG = 200.0;

    public PatternAnalysis detect(MarketSnapshot snap, QuantState state, List<Double> recentPrices) {
        if (snap == null || snap.delta() == null) {
            return PatternAnalysis.indeterminate("Pas de delta disponible");
        }
        if (recentPrices == null || recentPrices.size() < 2) {
            return classifyFromCurrentScan(snap);
        }

        double priceMove = recentPrices.get(recentPrices.size() - 1) - recentPrices.get(0);
        double delta = snap.delta();
        double absDelta = Math.abs(delta);

        boolean priceUp     = priceMove >  PRICE_STABLE_BAND;
        boolean priceDown   = priceMove < -PRICE_STABLE_BAND;
        boolean priceStable = !priceUp && !priceDown;

        if (delta < 0 && (priceStable || priceUp)) {
            return new PatternAnalysis(
                OrderFlowPattern.ABSORPTION_HAUSSIERE,
                "Absorption haussière",
                String.format(java.util.Locale.US,"Δ=%+.0f mais prix %+.1fpts → acheteurs absorbent les ventes",
                    delta, priceMove),
                confidenceFor(absDelta, Math.abs(priceMove)),
                PatternAnalysis.Action.AVOID
            );
        }
        if (delta > 0 && (priceStable || priceDown)) {
            return new PatternAnalysis(
                OrderFlowPattern.DISTRIBUTION_SILENCIEUSE,
                "Distribution silencieuse",
                String.format(java.util.Locale.US,"Δ=%+.0f mais prix %+.1fpts → vendeurs distribuent en silence",
                    delta, priceMove),
                confidenceFor(absDelta, Math.abs(priceMove)),
                PatternAnalysis.Action.TRADE
            );
        }
        if (delta < 0 && priceDown) {
            return new PatternAnalysis(
                OrderFlowPattern.VRAIE_VENTE,
                "Vraie vente",
                String.format(java.util.Locale.US,"Δ=%+.0f et prix %+.1fpts → flow baissier confirmé", delta, priceMove),
                confidenceFor(absDelta, Math.abs(priceMove)),
                PatternAnalysis.Action.TRADE
            );
        }
        if (delta > 0 && priceUp) {
            return new PatternAnalysis(
                OrderFlowPattern.VRAI_ACHAT,
                "Vrai achat",
                String.format(java.util.Locale.US,"Δ=%+.0f et prix %+.1fpts → flow haussier confirmé", delta, priceMove),
                confidenceFor(absDelta, Math.abs(priceMove)),
                PatternAnalysis.Action.AVOID
            );
        }
        return PatternAnalysis.indeterminate("Signal mixte (Δ et prix non alignés)");
    }

    /** Single-scan fallback when no rolling price history is available yet. */
    private PatternAnalysis classifyFromCurrentScan(MarketSnapshot snap) {
        double delta = snap.delta();
        if (Math.abs(delta) < DELTA_STRONG) {
            return PatternAnalysis.indeterminate(
                String.format(java.util.Locale.US,"Δ=%+.0f insuffisant pour conclure", delta));
        }
        if (delta < 0) {
            return new PatternAnalysis(
                OrderFlowPattern.VRAIE_VENTE,
                "Vraie vente (1 scan)",
                String.format(java.util.Locale.US,"Δ=%+.0f marqué — pas d'historique pour confirmer", delta),
                PatternAnalysis.Confidence.LOW,
                PatternAnalysis.Action.WAIT
            );
        }
        return new PatternAnalysis(
            OrderFlowPattern.VRAI_ACHAT,
            "Vrai achat (1 scan)",
            String.format(java.util.Locale.US,"Δ=%+.0f marqué — pas d'historique pour confirmer", delta),
            PatternAnalysis.Confidence.LOW,
            PatternAnalysis.Action.WAIT
        );
    }

    private static PatternAnalysis.Confidence confidenceFor(double absDelta, double absMove) {
        if (absDelta >= 400 && absMove >= 10) return PatternAnalysis.Confidence.HIGH;
        if (absDelta >= 200 && absMove >= 5)  return PatternAnalysis.Confidence.MEDIUM;
        return PatternAnalysis.Confidence.LOW;
    }
}

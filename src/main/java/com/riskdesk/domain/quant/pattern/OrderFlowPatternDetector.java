package com.riskdesk.domain.quant.pattern;

import com.riskdesk.domain.model.Instrument;
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

    private static record InstrumentThresholds(
        double priceStableBand,
        double deltaStrong,
        double deltaHigh,
        double moveMedium,
        double moveHigh
    ) {}

    private static InstrumentThresholds thresholdsFor(Instrument instrument) {
        return switch (instrument) {
            case MCL -> new InstrumentThresholds(0.10, 50.0, 100.0, 0.10, 0.20);
            case MGC -> new InstrumentThresholds(1.0, 50.0, 100.0, 1.0, 2.0);
            case E6 -> new InstrumentThresholds(0.00050, 50.0, 100.0, 0.00050, 0.0010);
            case MNQ, DXY -> new InstrumentThresholds(5.0, 200.0, 400.0, 5.0, 10.0);
            default -> new InstrumentThresholds(5.0, 200.0, 400.0, 5.0, 10.0);
        };
    }

    private static String formatPriceMove(Instrument instrument, double priceMove) {
        if (instrument == Instrument.E6) {
            return String.format(java.util.Locale.US, "%+.5f", priceMove);
        }
        if (instrument == Instrument.MCL) {
            return String.format(java.util.Locale.US, "%+.2f", priceMove);
        }
        return String.format(java.util.Locale.US, "%+.1f", priceMove);
    }

    /** Legacy fallback signature for backward compatibility. */
    public PatternAnalysis detect(MarketSnapshot snap, QuantState state, List<Double> recentPrices) {
        return detect(Instrument.MNQ, snap, state, recentPrices);
    }

    /** Instrument-aware pattern detection with adaptive thresholds. */
    public PatternAnalysis detect(Instrument instrument, MarketSnapshot snap, QuantState state, List<Double> recentPrices) {
        if (snap == null || snap.delta() == null) {
            return PatternAnalysis.indeterminate("Pas de delta disponible");
        }

        InstrumentThresholds thresholds = thresholdsFor(instrument == null ? Instrument.MNQ : instrument);

        if (recentPrices == null || recentPrices.size() < 2) {
            return classifyFromCurrentScan(snap, thresholds);
        }

        double priceMove = recentPrices.get(recentPrices.size() - 1) - recentPrices.get(0);
        double delta = snap.delta();
        double absDelta = Math.abs(delta);

        boolean priceUp     = priceMove >  thresholds.priceStableBand();
        boolean priceDown   = priceMove < -thresholds.priceStableBand();
        boolean priceStable = !priceUp && !priceDown;

        if (delta < 0 && (priceStable || priceUp)) {
            boolean hasDeltaConf = delta <= -thresholds.deltaStrong();
            boolean hasAbsConf = snap.absBull8Count() > 0;
            boolean hasDistAccConf = "ACCUMULATION".equals(snap.distType()) && snap.distConf() != null && snap.distConf() >= 50;

            PatternAnalysis.Confidence baseConf = confidenceFor(thresholds, absDelta, Math.abs(priceMove));
            PatternAnalysis.Confidence confidence = upgradeConfidence(baseConf, countConfirmations(hasDeltaConf, hasAbsConf, hasDistAccConf));
            String confirmationsSuffix = formatConfirmations(hasDeltaConf, hasAbsConf, hasDistAccConf, true);

            return new PatternAnalysis(
                OrderFlowPattern.ABSORPTION_HAUSSIERE,
                "Absorption haussière",
                String.format(java.util.Locale.US, "Δ=%+.0f mais prix %spts → acheteurs absorbent les ventes%s",
                    delta, formatPriceMove(instrument, priceMove), confirmationsSuffix),
                confidence,
                PatternAnalysis.Action.AVOID
            );
        }
        if (delta > 0 && (priceStable || priceDown)) {
            boolean hasDeltaConf = delta >= thresholds.deltaStrong();
            boolean hasAbsConf = snap.absBear8Count() > 0;
            boolean hasDistAccConf = "DISTRIBUTION".equals(snap.distType()) && snap.distConf() != null && snap.distConf() >= 50;

            PatternAnalysis.Confidence baseConf = confidenceFor(thresholds, absDelta, Math.abs(priceMove));
            PatternAnalysis.Confidence confidence = upgradeConfidence(baseConf, countConfirmations(hasDeltaConf, hasAbsConf, hasDistAccConf));
            String confirmationsSuffix = formatConfirmations(hasDeltaConf, hasAbsConf, hasDistAccConf, false);

            return new PatternAnalysis(
                OrderFlowPattern.DISTRIBUTION_SILENCIEUSE,
                "Distribution silencieuse",
                String.format(java.util.Locale.US, "Δ=%+.0f mais prix %spts → vendeurs distribuent en silence%s",
                    delta, formatPriceMove(instrument, priceMove), confirmationsSuffix),
                confidence,
                PatternAnalysis.Action.TRADE
            );
        }
        if (delta < 0 && priceDown) {
            boolean hasDeltaConf = delta <= -thresholds.deltaStrong();
            boolean hasAbsConf = snap.absBear8Count() > 0;
            boolean hasDistAccConf = "DISTRIBUTION".equals(snap.distType()) && snap.distConf() != null && snap.distConf() >= 50;

            PatternAnalysis.Confidence baseConf = confidenceFor(thresholds, absDelta, Math.abs(priceMove));
            PatternAnalysis.Confidence confidence = upgradeConfidence(baseConf, countConfirmations(hasDeltaConf, hasAbsConf, hasDistAccConf));
            String confirmationsSuffix = formatConfirmations(hasDeltaConf, hasAbsConf, hasDistAccConf, false);

            return new PatternAnalysis(
                OrderFlowPattern.VRAIE_VENTE,
                "Vraie vente",
                String.format(java.util.Locale.US, "Δ=%+.0f et prix %spts → flow baissier confirmé%s",
                    delta, formatPriceMove(instrument, priceMove), confirmationsSuffix),
                confidence,
                PatternAnalysis.Action.TRADE
            );
        }
        if (delta > 0 && priceUp) {
            boolean hasDeltaConf = delta >= thresholds.deltaStrong();
            boolean hasAbsConf = snap.absBull8Count() > 0;
            boolean hasDistAccConf = "ACCUMULATION".equals(snap.distType()) && snap.distConf() != null && snap.distConf() >= 50;

            PatternAnalysis.Confidence baseConf = confidenceFor(thresholds, absDelta, Math.abs(priceMove));
            PatternAnalysis.Confidence confidence = upgradeConfidence(baseConf, countConfirmations(hasDeltaConf, hasAbsConf, hasDistAccConf));
            String confirmationsSuffix = formatConfirmations(hasDeltaConf, hasAbsConf, hasDistAccConf, true);

            return new PatternAnalysis(
                OrderFlowPattern.VRAI_ACHAT,
                "Vrai achat",
                String.format(java.util.Locale.US, "Δ=%+.0f et prix %spts → flow haussier confirmé%s",
                    delta, formatPriceMove(instrument, priceMove), confirmationsSuffix),
                confidence,
                PatternAnalysis.Action.AVOID
            );
        }
        return PatternAnalysis.indeterminate("Signal mixte (Δ et prix non alignés)");
    }

    /** Single-scan fallback when no rolling price history is available yet. */
    private PatternAnalysis classifyFromCurrentScan(MarketSnapshot snap, InstrumentThresholds thresholds) {
        double delta = snap.delta();
        if (Math.abs(delta) < thresholds.deltaStrong()) {
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

    private static PatternAnalysis.Confidence confidenceFor(InstrumentThresholds thresholds, double absDelta, double absMove) {
        if (absDelta >= thresholds.deltaHigh() && absMove >= thresholds.moveHigh()) return PatternAnalysis.Confidence.HIGH;
        if (absDelta >= thresholds.deltaStrong() && absMove >= thresholds.moveMedium()) return PatternAnalysis.Confidence.MEDIUM;
        return PatternAnalysis.Confidence.LOW;
    }

    private static int countConfirmations(boolean c1, boolean c2, boolean c3) {
        int count = 0;
        if (c1) count++;
        if (c2) count++;
        if (c3) count++;
        return count;
    }

    private static PatternAnalysis.Confidence upgradeConfidence(PatternAnalysis.Confidence base, int confirmationsCount) {
        if (base == PatternAnalysis.Confidence.LOW && confirmationsCount >= 1) {
            return PatternAnalysis.Confidence.MEDIUM;
        }
        if (base == PatternAnalysis.Confidence.MEDIUM && confirmationsCount >= 2) {
            return PatternAnalysis.Confidence.HIGH;
        }
        return base;
    }

    private static String formatConfirmations(boolean hasDeltaConf, boolean hasAbsConf, boolean hasDistAccConf, boolean isBullish) {
        StringBuilder sb = new StringBuilder();
        if (hasDeltaConf) {
            sb.append("[Δ CONFIRMED]");
        }
        if (hasAbsConf) {
            sb.append(isBullish ? "[ABS BULL ACTIVE]" : "[ABS BEAR ACTIVE]");
        }
        if (hasDistAccConf) {
            sb.append(isBullish ? "[ACCU CONFIRMED]" : "[DIST CONFIRMED]");
        }
        if (sb.length() > 0) {
            return " | Confirmations: " + sb.toString();
        }
        return "";
    }
}

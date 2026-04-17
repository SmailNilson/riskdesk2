package com.riskdesk.application.analysis;

import com.riskdesk.application.dto.MentorInverseBiasHint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure domain rule that looks at the {@code errors} list from a non-conforme
 * Gemini verdict and decides whether the opposite direction is worth a
 * second look.
 *
 * <p>Motivation: Opus prod audit #3496 (E6 1h WaveTrend LONG) surfaced a
 * pattern where Gemini correctly rejects a LONG setup with four bearish
 * contradictions ("BEARISH_DIVERGENCE", "CHoCH baissier récent", "buy ratio
 * faible 43.2%", "DXY haussier tiré par EUR weakness") — the LONG is dead,
 * but the SHORT side has clean confluence and the system emits nothing about
 * it. Reviewer has to re-audit manually to catch the trade.
 *
 * <p>The rule is deliberately conservative: we only raise a hint when at
 * least {@value #MIN_CONTRADICTIONS} distinct lexical cues point the same
 * way. Single-cue heuristics would produce noise — this aims for the clean
 * cases only.
 *
 * <p>Stateless + side-effect free. Lives in {@code application.analysis}
 * rather than the domain layer because it returns
 * {@link com.riskdesk.application.dto.MentorInverseBiasHint}, which is a
 * JSON-shaped DTO owned by the application layer.
 */
public final class InverseBiasAnalyzer {

    /** Minimum number of contradictory cues before we emit a hint. */
    public static final int MIN_CONTRADICTIONS = 3;

    // Keywords grouped by the direction they *support*. If the rejected
    // signal was LONG and we find BEARISH cues, we propose a SHORT hint.
    private static final List<String> BEARISH_CUES = List.of(
        "bearish",            // BEARISH_DIVERGENCE, bearish absorption, bearish CHoCH
        "bearish_divergence",
        "baissier",           // CHoCH baissier, divergence baissière
        "baissière",
        "selling",            // selling pressure
        "distribution",       // CMF red, distribution phase
        "dxy haussier",       // bearish for risk assets
        "dxy bullish",
        "overbought",         // exhaustion of the LONG side
        "premium",            // buying the top
        "buy ratio faible",   // low buy ratio = bearish pressure (prod case #3496)
        "pression acheteuse faible",
        "manque de pression acheteuse"
    );

    private static final List<String> BULLISH_CUES = List.of(
        "bullish",
        "bullish_divergence",
        "haussier",
        "haussière",
        "buying",
        "accumulation",
        "dxy baissier",
        "dxy bearish",
        "oversold",
        "discount",           // selling the bottom
        "sell ratio faible",
        "pression vendeuse faible",
        "manque de pression vendeuse"
    );

    private InverseBiasAnalyzer() {}

    /**
     * @param originalAction the action evaluated (must be {@code "LONG"} or
     *                       {@code "SHORT"} — case-insensitive). If null/blank,
     *                       returns {@code null}.
     * @param errors         the Gemini error list; may be null or empty.
     * @return an inverse-direction hint, or {@code null} if the threshold
     *         isn't cleared. Never returns a zero-score hint — absence is
     *         signalled by {@code null}.
     */
    public static MentorInverseBiasHint analyze(String originalAction, List<String> errors) {
        if (originalAction == null || originalAction.isBlank() || errors == null || errors.isEmpty()) {
            return null;
        }
        String normalized = originalAction.trim().toUpperCase(Locale.ROOT);
        if (!"LONG".equals(normalized) && !"SHORT".equals(normalized)) {
            return null;
        }
        // If the rejected direction was LONG, we look for bearish cues (the
        // inverse would be SHORT) and vice versa.
        boolean rejectedLong = "LONG".equals(normalized);
        List<String> cues = rejectedLong ? BEARISH_CUES : BULLISH_CUES;
        String inverseDirection = rejectedLong ? "SHORT" : "LONG";

        List<String> matches = new ArrayList<>();
        for (String err : errors) {
            if (err == null) continue;
            String lower = err.toLowerCase(Locale.ROOT);
            for (String cue : cues) {
                if (lower.contains(cue)) {
                    matches.add(err);
                    break;   // one error contributes once, not once per cue
                }
            }
        }
        if (matches.size() < MIN_CONTRADICTIONS) {
            return null;
        }
        double score = (double) matches.size() / (double) MIN_CONTRADICTIONS;
        String reasoning = String.format(
            "Rejected %s has %d contradictions pointing to %s — worth a second look at the inverse side.",
            normalized, matches.size(), inverseDirection);
        return new MentorInverseBiasHint(inverseDirection, score, matches, reasoning);
    }
}

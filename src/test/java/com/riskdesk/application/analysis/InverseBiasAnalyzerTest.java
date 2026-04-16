package com.riskdesk.application.analysis;

import com.riskdesk.application.dto.MentorInverseBiasHint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-rule tests for {@link InverseBiasAnalyzer}.
 *
 * <p>Anchor scenario: Opus audit #3496 — E6 1h WaveTrend LONG rejected with
 * four bearish contradictions. The analyzer must turn that into a SHORT
 * hint so the reviewer sees the value that was left on the table.
 */
class InverseBiasAnalyzerTest {

    @Test
    void analyze_realProdCase_3496_E6_LongRejectedWithBearishErrors_hintsShort() {
        List<String> errors = List.of(
            "Divergence baissière détectée sur l'Order Flow réel (BEARISH_DIVERGENCE).",
            "Dernier événement structurel est un CHoCH baissier.",
            "Buy ratio faible (43.2%) indiquant un manque de pression acheteuse agressive.",
            "DXY haussier (0.137%) tiré par la faiblesse de l'EUR, ce qui est baissier pour 6E."
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", errors);

        assertThat(hint).isNotNull();
        assertThat(hint.direction()).isEqualTo("SHORT");
        assertThat(hint.supportingErrors()).hasSize(4);
        assertThat(hint.confidenceScore())
            .as("4 contradictions / threshold 3 = 1.33")
            .isGreaterThanOrEqualTo(1.0);
        assertThat(hint.reasoning()).contains("SHORT");
    }

    @Test
    void analyze_shortRejectedWithBullishCues_hintsLong() {
        List<String> errors = List.of(
            "Bullish divergence on order flow",
            "CHoCH haussier confirmé",
            "DXY baissier — risk-on"
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("SHORT", errors);

        assertThat(hint).isNotNull();
        assertThat(hint.direction()).isEqualTo("LONG");
        assertThat(hint.supportingErrors()).hasSize(3);
    }

    @Test
    void analyze_belowThreshold_returnsNull() {
        // Only 2 bearish cues → below the min-3 threshold → no hint at all.
        List<String> errors = List.of(
            "BEARISH_DIVERGENCE on the 15m",
            "RSI overbought — attention"
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", errors);

        assertThat(hint).isNull();
    }

    @Test
    void analyze_mixedDirectionCues_onlyCountsOnesMatchingInverse() {
        // Rejected LONG with 2 bearish + 2 bullish cues → bearish count = 2
        // → still below threshold → no hint (bullish cues don't help here).
        List<String> errors = List.of(
            "BEARISH_DIVERGENCE at the low",
            "CHoCH baissier",
            "Bullish FVG filled earlier today",
            "Accumulation visible on 4h"
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", errors);

        assertThat(hint).isNull();
    }

    @Test
    void analyze_oneErrorMatchesMultipleCues_countsAsOneMatch() {
        // A single error string containing both "bearish" and "baissier"
        // should not count as two matches — that would inflate the score.
        List<String> errors = List.of(
            "Bearish divergence (baissière) confirmée sur le delta",
            "CHoCH baissier récent"
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", errors);

        // Only 2 matching errors → below threshold → null.
        assertThat(hint).isNull();
    }

    @Test
    void analyze_nullAction_returnsNull() {
        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze(null, List.of("bearish divergence"));
        assertThat(hint).isNull();
    }

    @Test
    void analyze_blankAction_returnsNull() {
        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("   ", List.of("bearish divergence"));
        assertThat(hint).isNull();
    }

    @Test
    void analyze_unknownAction_returnsNull() {
        // Guard against upstream passing random strings — only LONG/SHORT are
        // meaningful for the inverse.
        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("FLAT",
            List.of("bearish 1", "bearish 2", "bearish 3"));
        assertThat(hint).isNull();
    }

    @Test
    void analyze_nullErrors_returnsNull() {
        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", null);
        assertThat(hint).isNull();
    }

    @Test
    void analyze_emptyErrors_returnsNull() {
        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", List.of());
        assertThat(hint).isNull();
    }

    @Test
    void analyze_caseInsensitiveAction() {
        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("long",
            List.of(
                "BEARISH divergence",
                "CHoCH baissier",
                "DXY haussier — bearish for risk-on"
            ));
        assertThat(hint).isNotNull();
        assertThat(hint.direction()).isEqualTo("SHORT");
    }

    @Test
    void analyze_threeExactMatches_scoreIsExactlyOne() {
        List<String> errors = List.of(
            "bearish divergence",
            "CHoCH baissier",
            "distribution on 4h"
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", errors);

        assertThat(hint).isNotNull();
        assertThat(hint.confidenceScore())
            .as("Exactly 3 matches / threshold 3 = 1.0")
            .isEqualTo(1.0);
    }

    @Test
    void analyze_nullErrorEntry_isSkipped_notThrown() {
        // Defensive: don't NPE on a null error slot.
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();
        errors.add("bearish divergence");
        errors.add(null);
        errors.add("CHoCH baissier");
        errors.add("distribution visible");

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", errors);

        assertThat(hint).isNotNull();
        assertThat(hint.supportingErrors()).hasSize(3);
    }
}

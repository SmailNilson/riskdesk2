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

    // ── DXY phrase exclusion (see codex review on line 100) ─────────────
    //
    // DXY cues are inherently flipped for risk assets: "DXY bullish" is BEARISH
    // for risk-on instruments (E6, MNQ), "DXY bearish" is BULLISH. Without
    // masking, generic substring cues like "bullish" / "bearish" /
    // "haussier" / "baissier" would false-match inside DXY phrases and count
    // toward the WRONG inverse direction. These tests lock the masking down.

    @Test
    void analyze_dxyBullish_shortRejected_isExcludedFromBullishCueCount() {
        // Rejected SHORT + 4 errors, one of which is "DXY bullish".
        // DXY bullish is bearish for risk, so it SUPPORTS the SHORT decision
        // and must NOT be counted as a bullish cue that would flip the
        // verdict to LONG. Pre-fix: all 4 match (DXY bullish false-matches
        // generic "bullish") → score 4/3. Post-fix: only 3 genuine bullish
        // errors match → score exactly 1.0.
        List<String> errors = List.of(
            "DXY bullish (+0.14%) — risk-off setup",   // must be excluded
            "Bullish divergence on 15m CMF",           // genuine match
            "Accumulation visible on 4h",              // genuine match
            "Buying pressure widening on delta"        // genuine match
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("SHORT", errors);

        assertThat(hint).isNotNull();
        assertThat(hint.direction()).isEqualTo("LONG");
        assertThat(hint.supportingErrors())
            .as("DXY bullish must not count as a bullish cue — it is bearish for risk")
            .hasSize(3)
            .noneMatch(s -> s.toLowerCase().contains("dxy bullish"));
        assertThat(hint.confidenceScore())
            .as("Exactly 3 real matches / threshold 3 = 1.0, not 4/3 like pre-fix")
            .isEqualTo(1.0);
    }

    @Test
    void analyze_dxyBearish_longRejected_isExcludedFromBearishCueCount() {
        // Rejected LONG + 4 errors, one of which is "DXY bearish".
        // DXY bearish is bullish for risk, it must NOT inflate the bearish
        // count that would push the hint toward SHORT.
        List<String> errors = List.of(
            "DXY bearish (-0.21%) — risk-on tailwind",  // must be excluded
            "Bearish divergence on RSI",                // genuine match
            "CHoCH baissier récent",                    // genuine match
            "Distribution phase on CMF 1h"              // genuine match
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", errors);

        assertThat(hint).isNotNull();
        assertThat(hint.direction()).isEqualTo("SHORT");
        assertThat(hint.supportingErrors())
            .as("DXY bearish must not count as a bearish cue — it is bullish for risk")
            .hasSize(3)
            .noneMatch(s -> s.toLowerCase().contains("dxy bearish"));
    }

    @Test
    void analyze_dxyHaussierFrench_shortRejected_isExcludedFromHaussierCueCount() {
        // Same bug on the French side: "DXY haussier" contains "haussier"
        // which is in BULLISH_CUES. Pre-fix this would false-match.
        List<String> errors = List.of(
            "DXY haussier — pression sur E6",         // must be excluded
            "Bullish FVG M15 non clôturé",            // genuine match
            "Accumulation H4 visible",                // genuine match
            "Pression vendeuse faible sur delta"      // genuine match
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("SHORT", errors);

        assertThat(hint).isNotNull();
        assertThat(hint.direction()).isEqualTo("LONG");
        assertThat(hint.supportingErrors())
            .as("DXY haussier must not count as a haussier cue — it is baissier for risk")
            .hasSize(3)
            .noneMatch(s -> s.toLowerCase().contains("dxy haussier"));
    }

    @Test
    void analyze_dxyBaissierFrench_longRejected_isExcludedFromBaissierCueCount() {
        // Rejected LONG + 4 errors, one of which is "DXY baissier".
        // DXY baissier is bullish for risk (haussier pour E6/MNQ), must
        // NOT inflate the bearish count that would suggest SHORT.
        List<String> errors = List.of(
            "DXY baissier — risk-on",                 // must be excluded
            "Divergence baissière sur WT 1h",         // genuine match
            "CHoCH baissier confirmé",                // genuine match
            "Buy ratio faible 41%"                    // genuine match
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", errors);

        assertThat(hint).isNotNull();
        assertThat(hint.direction()).isEqualTo("SHORT");
        assertThat(hint.supportingErrors())
            .as("DXY baissier must not count as a baissier cue — it is haussier for risk")
            .hasSize(3)
            .noneMatch(s -> s.toLowerCase().contains("dxy baissier"));
    }

    @Test
    void analyze_dxyBullishAlone_longRejected_stillContributesToShortInverse_regressionGuard() {
        // Positive control: make sure the mask did NOT break the correct
        // case. Rejected LONG + "DXY bullish" must still match the
        // DXY-specific cue "dxy bullish" in BEARISH_CUES.
        List<String> errors = List.of(
            "DXY bullish — bearish for risk assets",
            "BEARISH_DIVERGENCE on delta",
            "distribution visible on 4h"
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("LONG", errors);

        assertThat(hint).isNotNull();
        assertThat(hint.direction()).isEqualTo("SHORT");
        assertThat(hint.supportingErrors())
            .as("DXY-specific cue must still match DXY phrases (it is the correct signal)")
            .hasSize(3)
            .anyMatch(s -> s.toLowerCase().contains("dxy bullish"));
    }

    @Test
    void analyze_errorContainsBothDxyBullishAndGenericBullish_shortRejected_countsOnce() {
        // An error string that mixes "DXY bullish" (masked out) and
        // "bullish divergence" (genuine) must still count as ONE match —
        // the generic cue should fire through the masked text, not twice.
        List<String> errors = List.of(
            "DXY bullish; also a bullish divergence on 15m",
            "Accumulation on 4h",
            "CHoCH haussier"
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("SHORT", errors);

        assertThat(hint).isNotNull();
        assertThat(hint.direction()).isEqualTo("LONG");
        assertThat(hint.supportingErrors())
            .as("Mixed DXY+generic error must count as exactly one supporting error")
            .hasSize(3);
    }

    @Test
    void analyze_onlyDxyBullish_shortRejected_belowThreshold_returnsNull() {
        // Three "DXY bullish" errors would, pre-fix, false-match the generic
        // "bullish" cue three times and cross the threshold → spurious LONG
        // hint. Post-fix, none of them match BULLISH_CUES (they would match
        // BEARISH_CUES which is the wrong direction here), so the analyzer
        // correctly returns null.
        List<String> errors = List.of(
            "DXY bullish +0.12% intraday",
            "DXY bullish extension on H1",
            "DXY bullish — broad USD strength"
        );

        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze("SHORT", errors);

        assertThat(hint)
            .as("DXY bullish alone must never produce a LONG inverse hint")
            .isNull();
    }
}

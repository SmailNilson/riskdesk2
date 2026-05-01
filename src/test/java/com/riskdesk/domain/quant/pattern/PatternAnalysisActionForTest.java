package com.riskdesk.domain.quant.pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direction-aware action mirror for {@link PatternAnalysis}.
 *
 * <p>The legacy {@code action()} field is encoded for the SHORT trader (the
 * quant module was built short-first). LONG callers must consult
 * {@link PatternAnalysis#actionFor(PatternAnalysis.TradeBias)} so a {@code
 * VRAI_ACHAT} regime is reported as {@code TRADE} on the LONG panel and
 * {@code AVOID} on the SHORT panel — not the other way round.</p>
 */
class PatternAnalysisActionForTest {

    @Test
    @DisplayName("ABSORPTION_HAUSSIERE — bullish setup, SHORT must AVOID, LONG should TRADE")
    void absorptionHaussiere_flipsForLong() {
        PatternAnalysis pa = new PatternAnalysis(
            OrderFlowPattern.ABSORPTION_HAUSSIERE,
            "Absorption haussière", "test",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.AVOID);

        assertThat(pa.actionFor(PatternAnalysis.TradeBias.SHORT))
            .isEqualTo(PatternAnalysis.Action.AVOID);
        assertThat(pa.actionFor(PatternAnalysis.TradeBias.LONG))
            .isEqualTo(PatternAnalysis.Action.TRADE);
    }

    @Test
    @DisplayName("DISTRIBUTION_SILENCIEUSE — bearish setup, SHORT should TRADE, LONG must AVOID")
    void distributionSilencieuse_flipsForLong() {
        PatternAnalysis pa = new PatternAnalysis(
            OrderFlowPattern.DISTRIBUTION_SILENCIEUSE,
            "Distribution silencieuse", "test",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.TRADE);

        assertThat(pa.actionFor(PatternAnalysis.TradeBias.SHORT))
            .isEqualTo(PatternAnalysis.Action.TRADE);
        assertThat(pa.actionFor(PatternAnalysis.TradeBias.LONG))
            .isEqualTo(PatternAnalysis.Action.AVOID);
    }

    @Test
    @DisplayName("VRAIE_VENTE — confirmed bearish, SHORT TRADE / LONG AVOID")
    void vraieVente_flipsForLong() {
        PatternAnalysis pa = new PatternAnalysis(
            OrderFlowPattern.VRAIE_VENTE,
            "Vraie vente", "test",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.TRADE);

        assertThat(pa.actionFor(PatternAnalysis.TradeBias.SHORT))
            .isEqualTo(PatternAnalysis.Action.TRADE);
        assertThat(pa.actionFor(PatternAnalysis.TradeBias.LONG))
            .isEqualTo(PatternAnalysis.Action.AVOID);
    }

    @Test
    @DisplayName("VRAI_ACHAT — confirmed bullish, SHORT AVOID / LONG TRADE")
    void vraiAchat_flipsForLong() {
        PatternAnalysis pa = new PatternAnalysis(
            OrderFlowPattern.VRAI_ACHAT,
            "Vrai achat", "test",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.AVOID);

        assertThat(pa.actionFor(PatternAnalysis.TradeBias.SHORT))
            .isEqualTo(PatternAnalysis.Action.AVOID);
        assertThat(pa.actionFor(PatternAnalysis.TradeBias.LONG))
            .isEqualTo(PatternAnalysis.Action.TRADE);
    }

    @Test
    @DisplayName("WAIT — direction-agnostic (low confidence stays WAIT for both sides)")
    void wait_isDirectionAgnostic() {
        PatternAnalysis pa = new PatternAnalysis(
            OrderFlowPattern.VRAIE_VENTE,
            "Vraie vente (1 scan)", "test",
            PatternAnalysis.Confidence.LOW,
            PatternAnalysis.Action.WAIT);

        assertThat(pa.actionFor(PatternAnalysis.TradeBias.SHORT))
            .isEqualTo(PatternAnalysis.Action.WAIT);
        assertThat(pa.actionFor(PatternAnalysis.TradeBias.LONG))
            .isEqualTo(PatternAnalysis.Action.WAIT);
    }

    @Test
    @DisplayName("INDETERMINE — WAIT for both sides")
    void indetermine_isDirectionAgnostic() {
        PatternAnalysis pa = PatternAnalysis.indeterminate("not enough data");

        assertThat(pa.actionFor(PatternAnalysis.TradeBias.SHORT))
            .isEqualTo(PatternAnalysis.Action.WAIT);
        assertThat(pa.actionFor(PatternAnalysis.TradeBias.LONG))
            .isEqualTo(PatternAnalysis.Action.WAIT);
    }

    @Test
    @DisplayName("Null bias defaults to legacy SHORT view (backwards-compat)")
    void nullBias_returnsLegacyAction() {
        PatternAnalysis pa = new PatternAnalysis(
            OrderFlowPattern.VRAI_ACHAT,
            "Vrai achat", "test",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.AVOID);

        assertThat(pa.actionFor(null)).isEqualTo(PatternAnalysis.Action.AVOID);
    }

    @Test
    @DisplayName("Legacy action() unchanged — no breakage for existing SHORT consumers")
    void legacyAction_unchanged() {
        PatternAnalysis pa = new PatternAnalysis(
            OrderFlowPattern.VRAI_ACHAT,
            "Vrai achat", "test",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.AVOID);

        assertThat(pa.action()).isEqualTo(PatternAnalysis.Action.AVOID);
    }
}

package com.riskdesk.domain.quant.pattern;

import com.riskdesk.domain.quant.model.MarketSnapshot;
import com.riskdesk.domain.quant.model.QuantState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderFlowPatternDetectorTest {

    private static final Instant NOW = Instant.parse("2026-04-29T18:00:00Z");
    private final OrderFlowPatternDetector detector = new OrderFlowPatternDetector();
    private final QuantState state = QuantState.reset(LocalDate.of(2026, 4, 29));

    @Test
    @DisplayName("Negative delta + price stable → bullish absorption (AVOID short)")
    void absorptionBullish_avoid() {
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW).delta(-300.0).price(20_000.0).build();

        PatternAnalysis result = detector.detect(ms, state, List.of(20_000.0, 20_001.0, 20_002.0));

        assertThat(result.type()).isEqualTo(OrderFlowPattern.ABSORPTION_HAUSSIERE);
        assertThat(result.action()).isEqualTo(PatternAnalysis.Action.AVOID);
    }

    @Test
    @DisplayName("Positive delta + price drifts down → silent distribution (TRADE the short)")
    void distributionSilencieuse_trade() {
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW).delta(350.0).price(19_990.0).build();

        PatternAnalysis result = detector.detect(ms, state, List.of(20_000.0, 19_995.0, 19_990.0));

        assertThat(result.type()).isEqualTo(OrderFlowPattern.DISTRIBUTION_SILENCIEUSE);
        assertThat(result.action()).isEqualTo(PatternAnalysis.Action.TRADE);
    }

    @Test
    @DisplayName("Negative delta + price down → real sell (TRADE the short)")
    void vraieVente_trade() {
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW).delta(-400.0).price(19_980.0).build();

        PatternAnalysis result = detector.detect(ms, state, List.of(20_000.0, 19_990.0, 19_980.0));

        assertThat(result.type()).isEqualTo(OrderFlowPattern.VRAIE_VENTE);
        assertThat(result.action()).isEqualTo(PatternAnalysis.Action.TRADE);
        assertThat(result.confidence()).isEqualTo(PatternAnalysis.Confidence.HIGH);
    }

    @Test
    @DisplayName("Positive delta + price up → real buy (AVOID short)")
    void vraiAchat_avoid() {
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW).delta(450.0).price(20_020.0).build();

        PatternAnalysis result = detector.detect(ms, state, List.of(20_000.0, 20_010.0, 20_020.0));

        assertThat(result.type()).isEqualTo(OrderFlowPattern.VRAI_ACHAT);
        assertThat(result.action()).isEqualTo(PatternAnalysis.Action.AVOID);
    }

    @Test
    @DisplayName("No delta → indeterminate")
    void noDelta_indeterminate() {
        MarketSnapshot ms = new MarketSnapshot.Builder().now(NOW).price(20_000.0).build();

        PatternAnalysis result = detector.detect(ms, state, List.of(20_000.0, 20_001.0));

        assertThat(result.type()).isEqualTo(OrderFlowPattern.INDETERMINE);
        assertThat(result.action()).isEqualTo(PatternAnalysis.Action.WAIT);
    }

    @Test
    @DisplayName("Single-scan fallback when no price history yet → low-confidence read on delta sign")
    void singleScanFallback() {
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW).delta(-300.0).price(20_000.0).build();

        PatternAnalysis result = detector.detect(ms, state, List.of()); // no history

        assertThat(result.type()).isEqualTo(OrderFlowPattern.VRAIE_VENTE);
        assertThat(result.confidence()).isEqualTo(PatternAnalysis.Confidence.LOW);
        assertThat(result.action()).isEqualTo(PatternAnalysis.Action.WAIT);
    }
}

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

    @Test
    @DisplayName("MCL custom thresholds — small price band and small delta thresholds")
    void mclCustomThresholds() {
        // Price stable band for MCL is 0.10. Move is 0.08, so it is "stable".
        // Delta is -60 (above MCL strong delta of 50).
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW).delta(-60.0).price(75.08).build();

        PatternAnalysis result = detector.detect(com.riskdesk.domain.model.Instrument.MCL, ms, state, List.of(75.0, 75.05, 75.08));

        assertThat(result.type()).isEqualTo(OrderFlowPattern.ABSORPTION_HAUSSIERE);
        assertThat(result.reason()).contains("Δ=-60");
        assertThat(result.reason()).contains("mais prix +0.08pts");
        // base confidence for delta=60 and move=0.08 is LOW. But hasDeltaConf is active (60 >= 50),
        // which triggers 1 confirmation and upgrades confidence to MEDIUM.
        assertThat(result.confidence()).isEqualTo(PatternAnalysis.Confidence.MEDIUM);
        assertThat(result.reason()).contains("Confirmations: [Δ CONFIRMED]");
    }

    @Test
    @DisplayName("Advanced confirmations and confidence upgrades — LOW to HIGH")
    void confidenceUpgrades() {
        // MNQ: base confidence for delta=250 and move=2.0 is LOW (requires move>=5.0 for MEDIUM).
        // Let's add 2 confirmations: absBull8Count > 0 AND distType=ACCUMULATION with conf=80.
        MarketSnapshot ms = new MarketSnapshot.Builder()
            .now(NOW)
            .delta(-250.0) // >= 200 (1 confirmation)
            .absBull8(1) // (2 confirmations)
            .dist("ACCUMULATION", 80) // (3 confirmations)
            .price(20_002.0)
            .build();

        // 1 confirmation: base LOW -> upgraded to MEDIUM
        MarketSnapshot ms1 = new MarketSnapshot.Builder()
            .now(NOW)
            .delta(-250.0) // >= 200 (1 confirmation)
            .price(20_002.0)
            .build();
        PatternAnalysis r1 = detector.detect(com.riskdesk.domain.model.Instrument.MNQ, ms1, state, List.of(20_000.0, 20_001.0, 20_002.0));
        assertThat(r1.confidence()).isEqualTo(PatternAnalysis.Confidence.MEDIUM);
        assertThat(r1.reason()).contains("Confirmations: [Δ CONFIRMED]");

        // 3 confirmations: base LOW -> upgraded to MEDIUM (since upgrade from LOW with >=1 confirmations goes to MEDIUM)
        PatternAnalysis r2 = detector.detect(com.riskdesk.domain.model.Instrument.MNQ, ms, state, List.of(20_000.0, 20_001.0, 20_002.0));
        assertThat(r2.confidence()).isEqualTo(PatternAnalysis.Confidence.MEDIUM);
        assertThat(r2.reason()).contains("[Δ CONFIRMED][ABS BULL ACTIVE][ACCU CONFIRMED]");

        // If base is MEDIUM (delta=250, move=6.0 -> base MEDIUM), with 2+ confirmations it upgrades to HIGH
        PatternAnalysis r3 = detector.detect(com.riskdesk.domain.model.Instrument.MNQ, ms, state, List.of(20_000.0, 20_003.0, 20_006.0));
        assertThat(r3.confidence()).isEqualTo(PatternAnalysis.Confidence.HIGH);
        assertThat(r3.reason()).contains("[Δ CONFIRMED][ABS BULL ACTIVE][ACCU CONFIRMED]");
    }
}

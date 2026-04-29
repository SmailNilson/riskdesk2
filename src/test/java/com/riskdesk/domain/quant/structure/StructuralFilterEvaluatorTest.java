package com.riskdesk.domain.quant.structure;

import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StructuralFilterEvaluator} — one positive and one
 * negative case per BLOCK / WARNING rule, plus the OB-bull override paths.
 *
 * <p>Fixture conventions: prices live around {@code 20_000} (MNQ-ish) so it
 * is obvious which scenarios push the price into / out of the various
 * structural ranges.</p>
 */
class StructuralFilterEvaluatorTest {

    private final StructuralFilterEvaluator evaluator = new StructuralFilterEvaluator();

    // ── BLOCK: OB_BULL_FRESH ───────────────────────────────────────────────

    @Nested
    @DisplayName("BLOCK: OB_BULL_FRESH")
    class OrderBlockTests {

        @Test
        @DisplayName("Active bullish OB containing the price → block")
        void activeBullishObContainingPrice_blocks() {
            IndicatorsSnapshot ind = indicators().withOrderBlocks(
                new IndicatorsSnapshot.OrderBlockView("BULLISH", "ACTIVE", 19_990.0, 20_010.0)
            ).build();
            StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
            assertThat(r.shortBlocked()).isTrue();
            assertThat(r.blocks()).extracting(StructuralBlock::code)
                .containsExactly(StructuralBlock.CODE_OB_BULL_FRESH);
        }

        @Test
        @DisplayName("Active bearish OB containing the price → no block")
        void activeBearishObContainingPrice_doesNotBlock() {
            IndicatorsSnapshot ind = indicators().withOrderBlocks(
                new IndicatorsSnapshot.OrderBlockView("BEARISH", "ACTIVE", 19_990.0, 20_010.0)
            ).build();
            StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
            assertThat(r.blocks()).isEmpty();
        }

        @Test
        @DisplayName("Active bullish OB but price outside range → no block")
        void priceOutsideOb_doesNotBlock() {
            IndicatorsSnapshot ind = indicators().withOrderBlocks(
                new IndicatorsSnapshot.OrderBlockView("BULLISH", "ACTIVE", 19_500.0, 19_550.0)
            ).build();
            StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
            assertThat(r.blocks()).isEmpty();
        }

        @Test
        @DisplayName("OB bull + recent CHoCH bear → demoted to WARNING")
        void recentChochBear_demotesObToWarning() {
            IndicatorsSnapshot ind = indicators()
                .withLastInternalBreak("CHOCH_BEARISH")
                .withOrderBlocks(new IndicatorsSnapshot.OrderBlockView(
                    "BULLISH", "ACTIVE", 19_990.0, 20_010.0))
                .build();
            StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
            assertThat(r.blocks()).isEmpty();
            assertThat(r.warnings()).extracting(StructuralWarning::code)
                .contains(StructuralWarning.CODE_OB_BULL_OVERRIDDEN);
        }

        @Test
        @DisplayName("OB bull + high-confidence VRAIE_VENTE pattern → demoted to WARNING")
        void vraieVenteHigh_demotesObToWarning() {
            IndicatorsSnapshot ind = indicators().withOrderBlocks(
                new IndicatorsSnapshot.OrderBlockView("BULLISH", "ACTIVE", 19_990.0, 20_010.0)
            ).build();
            PatternAnalysis pattern = new PatternAnalysis(
                OrderFlowPattern.VRAIE_VENTE, "Vraie vente", "delta down + price down",
                PatternAnalysis.Confidence.HIGH, PatternAnalysis.Action.TRADE);
            StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, pattern);
            assertThat(r.blocks()).isEmpty();
            assertThat(r.warnings()).extracting(StructuralWarning::code)
                .contains(StructuralWarning.CODE_OB_BULL_OVERRIDDEN);
        }
    }

    // ── BLOCK: REGIME_CHOPPY ───────────────────────────────────────────────

    @Test
    @DisplayName("BLOCK regime-context vote contains CHOPPY → block")
    void regimeChoppy_blocks() {
        StrategyVotes strat = new StrategyVotes("TRADE", List.of(
            new StrategyVotes.Vote("regime-context", List.of("regime=CHOPPY", "bbWidth low"))
        ), List.of());
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, null, strat, null);
        assertThat(r.blocks()).extracting(StructuralBlock::code)
            .containsExactly(StructuralBlock.CODE_REGIME_CHOPPY);
    }

    @Test
    @DisplayName("regime-context vote evidence has no CHOPPY token → no block")
    void regimeNotChoppy_doesNotBlock() {
        StrategyVotes strat = new StrategyVotes("TRADE", List.of(
            new StrategyVotes.Vote("regime-context", List.of("regime=TRENDING"))
        ), List.of());
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, null, strat, null);
        assertThat(r.blocks()).isEmpty();
    }

    // ── BLOCK: MTF_BULL ────────────────────────────────────────────────────

    @Test
    @DisplayName("4/5 nested timeframes BULLISH → block MTF_BULL")
    void mtfFourBullish_blocks() {
        Map<String, String> mtf = new LinkedHashMap<>();
        mtf.put("swing50", "BULLISH"); mtf.put("swing25", "BULLISH");
        mtf.put("swing9",  "BULLISH"); mtf.put("internal5", "BULLISH");
        mtf.put("micro1",  "BEARISH");
        IndicatorsSnapshot ind = indicators().withMtf(mtf).build();
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
        assertThat(r.blocks()).extracting(StructuralBlock::code)
            .contains(StructuralBlock.CODE_MTF_BULL);
    }

    @Test
    @DisplayName("3/5 nested timeframes BULLISH → no MTF_BULL block")
    void mtfThreeBullish_doesNotBlock() {
        Map<String, String> mtf = new LinkedHashMap<>();
        mtf.put("swing50", "BULLISH"); mtf.put("swing25", "BULLISH");
        mtf.put("swing9",  "BULLISH"); mtf.put("internal5", "BEARISH");
        mtf.put("micro1",  "BEARISH");
        IndicatorsSnapshot ind = indicators().withMtf(mtf).build();
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
        assertThat(r.blocks()).extracting(StructuralBlock::code)
            .doesNotContain(StructuralBlock.CODE_MTF_BULL);
    }

    // ── BLOCK: JAVA_NO_TRADE_CRITICAL / WARNING: JAVA_MAINTENANCE ──────────

    @Test
    @DisplayName("decision=NO_TRADE with critical veto → block JAVA_NO_TRADE_CRITICAL")
    void javaNoTradeCritical_blocks() {
        StrategyVotes strat = new StrategyVotes("NO_TRADE", List.of(),
            List.of("drawdown breach: -3R today"));
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, null, strat, null);
        assertThat(r.blocks()).extracting(StructuralBlock::code)
            .containsExactly(StructuralBlock.CODE_JAVA_NO_TRADE);
    }

    @Test
    @DisplayName("decision=NO_TRADE only because of maintenance window → WARNING, no block")
    void javaNoTradeMaintenanceOnly_warns() {
        StrategyVotes strat = new StrategyVotes("NO_TRADE", List.of(),
            List.of("CME maintenance window 17:00-18:00 ET"));
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, null, strat, null);
        assertThat(r.blocks()).isEmpty();
        assertThat(r.warnings()).extracting(StructuralWarning::code)
            .contains(StructuralWarning.CODE_JAVA_MAINTENANCE);
        assertThat(r.scoreModifier()).isZero(); // maintenance is informational
    }

    // ── BLOCK / WARNING: CMF ───────────────────────────────────────────────

    @Test
    @DisplayName("cmf > 0.15 → block CMF_VERY_BULL")
    void cmfVeryBull_blocks() {
        IndicatorsSnapshot ind = indicators().withCmf(0.20).build();
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
        assertThat(r.blocks()).extracting(StructuralBlock::code)
            .contains(StructuralBlock.CODE_CMF_VERY_BULL);
    }

    @Test
    @DisplayName("0.05 < cmf <= 0.15 → CMF_POSITIVE warning, no block")
    void cmfPositive_warns() {
        IndicatorsSnapshot ind = indicators().withCmf(0.10).build();
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
        assertThat(r.blocks()).isEmpty();
        assertThat(r.warnings()).extracting(StructuralWarning::code)
            .contains(StructuralWarning.CODE_CMF_POSITIVE);
        assertThat(r.scoreModifier()).isLessThanOrEqualTo(-1);
    }

    // ── WARNINGS: VWAP / BB / ZONE / SWING / EQL ──────────────────────────

    @Test
    @DisplayName("price > 1σ below VWAP lower → VWAP_FAR warning (-2)")
    void vwapFar_warns() {
        // vwap=20_000, lower=19_990, σ=10. price=19_975 → distance=25 → 2.5σ
        IndicatorsSnapshot ind = indicators().withVwap(20_000.0, 19_990.0).build();
        StructuralFilterResult r = evaluator.evaluateForShort(19_975.0, ind, null, null);
        assertThat(r.warnings()).extracting(StructuralWarning::code).contains(StructuralWarning.CODE_VWAP_FAR);
        assertThat(r.scoreModifier()).isLessThanOrEqualTo(-2);
    }

    @Test
    @DisplayName("bbPct < 0.15 → BB_LOWER warning (-1)")
    void bbLower_warns() {
        IndicatorsSnapshot ind = indicators().withBbPct(0.10).build();
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
        assertThat(r.warnings()).extracting(StructuralWarning::code).contains(StructuralWarning.CODE_BB_LOWER);
    }

    @Test
    @DisplayName("currentZone=DISCOUNT → PRICE_IN_DISCOUNT warning (-2)")
    void priceInDiscount_warns() {
        IndicatorsSnapshot ind = indicators().withZone("DISCOUNT").build();
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
        assertThat(r.warnings()).extracting(StructuralWarning::code).contains(StructuralWarning.CODE_PRICE_IN_DISCOUNT);
    }

    @Test
    @DisplayName("swingBias=BULLISH → SWING_BULL warning (-1)")
    void swingBullish_warns() {
        IndicatorsSnapshot ind = indicators().withSwingBias("BULLISH").build();
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
        assertThat(r.warnings()).extracting(StructuralWarning::code).contains(StructuralWarning.CODE_SWING_BULL);
    }

    @Test
    @DisplayName("equal-low within 15pts and touchCount >= 2 → EQUAL_LOWS_NEAR warning")
    void equalLowsNear_warns() {
        IndicatorsSnapshot ind = indicators().withEqualLows(
            new IndicatorsSnapshot.EqualLowView(19_990.0, 3)).build();
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
        assertThat(r.warnings()).extracting(StructuralWarning::code).contains(StructuralWarning.CODE_EQUAL_LOWS_NEAR);
    }

    @Test
    @DisplayName("equal-low farther than 15pts away → no warning")
    void equalLowsFar_doesNotWarn() {
        IndicatorsSnapshot ind = indicators().withEqualLows(
            new IndicatorsSnapshot.EqualLowView(19_900.0, 3)).build();
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
        assertThat(r.warnings()).extracting(StructuralWarning::code).doesNotContain(StructuralWarning.CODE_EQUAL_LOWS_NEAR);
    }

    @Test
    @DisplayName("equal-low close but touchCount=1 → no warning")
    void equalLowsLowTouchCount_doesNotWarn() {
        IndicatorsSnapshot ind = indicators().withEqualLows(
            new IndicatorsSnapshot.EqualLowView(19_995.0, 1)).build();
        StructuralFilterResult r = evaluator.evaluateForShort(20_000.0, ind, null, null);
        assertThat(r.warnings()).extracting(StructuralWarning::code).doesNotContain(StructuralWarning.CODE_EQUAL_LOWS_NEAR);
    }

    // ── Empty / null safety ───────────────────────────────────────────────

    @Test
    @DisplayName("All inputs null → empty result, not blocked")
    void allNull_emptyResult() {
        StructuralFilterResult r = evaluator.evaluateForShort(null, null, null, null);
        assertThat(r.blocks()).isEmpty();
        assertThat(r.warnings()).isEmpty();
        assertThat(r.scoreModifier()).isZero();
        assertThat(r.shortBlocked()).isFalse();
    }

    // ── Test fixture builder ──────────────────────────────────────────────

    private static IndicatorsBuilder indicators() { return new IndicatorsBuilder(); }

    private static final class IndicatorsBuilder {
        private Double vwap, vwapLo, bbPct, cmf;
        private String zone, swingBias, lastInternalBreak;
        private Map<String, String> mtf = Map.of();
        private List<IndicatorsSnapshot.OrderBlockView> obs = List.of();
        private List<IndicatorsSnapshot.EqualLowView> els = List.of();

        IndicatorsBuilder withVwap(double vwap, double lower) { this.vwap = vwap; this.vwapLo = lower; return this; }
        IndicatorsBuilder withBbPct(double v)                  { this.bbPct = v; return this; }
        IndicatorsBuilder withCmf(double v)                    { this.cmf = v; return this; }
        IndicatorsBuilder withZone(String v)                   { this.zone = v; return this; }
        IndicatorsBuilder withSwingBias(String v)              { this.swingBias = v; return this; }
        IndicatorsBuilder withLastInternalBreak(String v)      { this.lastInternalBreak = v; return this; }
        IndicatorsBuilder withMtf(Map<String, String> v)       { this.mtf = v; return this; }
        IndicatorsBuilder withOrderBlocks(IndicatorsSnapshot.OrderBlockView... v) { this.obs = List.of(v); return this; }
        IndicatorsBuilder withEqualLows(IndicatorsSnapshot.EqualLowView... v)     { this.els = List.of(v); return this; }

        IndicatorsSnapshot build() {
            return new IndicatorsSnapshot(vwap, vwapLo, bbPct, cmf,
                zone, swingBias, lastInternalBreak, mtf, obs, els);
        }
    }
}

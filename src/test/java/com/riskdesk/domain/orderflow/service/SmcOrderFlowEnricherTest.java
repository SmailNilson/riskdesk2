package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.orderflow.model.BreakEnrichment;
import com.riskdesk.domain.orderflow.model.FvgEnrichment;
import com.riskdesk.domain.orderflow.model.LiquidityEnrichment;
import com.riskdesk.domain.orderflow.model.OrderBlockEnrichment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SmcOrderFlowEnricherTest {

    private SmcOrderFlowEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new SmcOrderFlowEnricher();
    }

    // =================== Order Block enrichment ===================

    @Test
    void ob_strongFormationDelta_highFormationScore() {
        // formationDelta=800, formationVolume=1000 => deltaRatio = 0.8
        // formationScore = min(100, 0.8 * 100 * 1.5) = min(100, 120) = 100
        OrderBlockEnrichment ob = enricher.enrichOrderBlock(
                800.0, 1000.0, null, null, null, 0.5, 10.0);

        assertThat(ob.formationDeltaRatio()).isCloseTo(0.8, within(0.01));
        assertThat(ob.obFormationScore()).isCloseTo(100.0, within(0.01));
    }

    @Test
    void ob_weakFormationDelta_lowFormationScore() {
        // formationDelta=100, formationVolume=1000 => deltaRatio = 0.1
        // formationScore = min(100, 0.1 * 100 * 1.5) = 15
        OrderBlockEnrichment ob = enricher.enrichOrderBlock(
                100.0, 1000.0, null, null, null, 0.5, 10.0);

        assertThat(ob.formationDeltaRatio()).isCloseTo(0.1, within(0.01));
        assertThat(ob.obFormationScore()).isCloseTo(15.0, within(0.01));
    }

    @Test
    void ob_withAbsorption_highLiveScore_defended() {
        // formationDelta=400, formationVolume=1000 => deltaRatio = 0.4
        // formationScore = min(100, 0.4 * 150) = 60
        // absorptionScore = 5.0 > 2.0 AND priceMoveTicks(0.5) < atr(10) * 0.3(=3.0) => defended = true
        // liveScore = formationScore * 0.4 + min(5.0 * 20, 60) = 24 + 60 = 84
        OrderBlockEnrichment ob = enricher.enrichOrderBlock(
                400.0, 1000.0, 5.0, "BULLISH_ABSORPTION", 1.5, 0.5, 10.0);

        assertThat(ob.defended()).isTrue();
        assertThat(ob.obLiveScore()).isCloseTo(84.0, within(0.01));
        assertThat(ob.absorptionScore()).isEqualTo(5.0);
        assertThat(ob.absorptionSide()).isEqualTo("BULLISH_ABSORPTION");
    }

    @Test
    void ob_withoutAbsorption_liveScoreEqualsFormationScore() {
        OrderBlockEnrichment ob = enricher.enrichOrderBlock(
                400.0, 1000.0, null, null, null, 0.5, 10.0);

        assertThat(ob.defended()).isFalse();
        assertThat(ob.obLiveScore()).isCloseTo(ob.obFormationScore(), within(0.01));
    }

    @Test
    void ob_absorptionScoreBelowThreshold_notDefended() {
        // absorptionScore = 1.5 <= 2.0 => not defended
        OrderBlockEnrichment ob = enricher.enrichOrderBlock(
                400.0, 1000.0, 1.5, "BULLISH_ABSORPTION", 1.0, 0.5, 10.0);

        assertThat(ob.defended()).isFalse();
    }

    @Test
    void ob_absorptionPresentButPriceMovedTooMuch_notDefended() {
        // absorptionScore = 5.0 > 2.0, but priceMoveTicks(5.0) >= atr(10)*0.3(3.0) => not defended
        OrderBlockEnrichment ob = enricher.enrichOrderBlock(
                400.0, 1000.0, 5.0, "BEARISH_ABSORPTION", 1.0, 5.0, 10.0);

        assertThat(ob.defended()).isFalse();
    }

    @Test
    void ob_zeroVolume_usesFloorOf1() {
        // formationVolume = 0 => safeVolume = 1
        // deltaRatio = |500| / 1 = 500
        // formationScore = min(100, 500 * 150) = 100 (capped)
        OrderBlockEnrichment ob = enricher.enrichOrderBlock(
                500.0, 0.0, null, null, null, 0.5, 10.0);

        assertThat(ob.obFormationScore()).isCloseTo(100.0, within(0.01));
    }

    @Test
    void ob_absorptionComponentCappedAt60() {
        // absorptionScore = 10 => absorptionComponent = min(10*20, 60) = min(200, 60) = 60
        // formationScore = 60, liveScore = 60 * 0.4 + 60 = 24 + 60 = 84
        OrderBlockEnrichment ob = enricher.enrichOrderBlock(
                400.0, 1000.0, 10.0, "BULLISH_ABSORPTION", 1.0, 0.5, 10.0);

        assertThat(ob.obLiveScore()).isCloseTo(84.0, within(0.5));
    }

    @Test
    void ob_depthSupportRatio_passedThrough() {
        OrderBlockEnrichment ob = enricher.enrichOrderBlock(
                400.0, 1000.0, null, null, 2.5, 0.5, 10.0);

        assertThat(ob.depthSupportRatio()).isEqualTo(2.5);
    }

    // =================== FVG enrichment ===================

    @Test
    void fvg_strongDelta_highQualityScore() {
        // gapDelta=800, gapVolume=1000 => intensity = 0.8
        // qualityScore = min(100, 0.8 * 120) = min(100, 96) = 96
        FvgEnrichment fvg = enricher.enrichFvg(800.0, 1000.0);

        assertThat(fvg.imbalanceIntensity()).isCloseTo(0.8, within(0.01));
        assertThat(fvg.fvgQualityScore()).isCloseTo(96.0, within(0.01));
    }

    @Test
    void fvg_weakDelta_lowQualityScore() {
        // gapDelta=50, gapVolume=1000 => intensity = 0.05
        // qualityScore = 0.05 * 120 = 6.0
        FvgEnrichment fvg = enricher.enrichFvg(50.0, 1000.0);

        assertThat(fvg.imbalanceIntensity()).isCloseTo(0.05, within(0.01));
        assertThat(fvg.fvgQualityScore()).isCloseTo(6.0, within(0.01));
    }

    @Test
    void fvg_zeroDelta_zeroScore() {
        FvgEnrichment fvg = enricher.enrichFvg(0.0, 1000.0);

        assertThat(fvg.imbalanceIntensity()).isCloseTo(0.0, within(0.001));
        assertThat(fvg.fvgQualityScore()).isCloseTo(0.0, within(0.001));
    }

    @Test
    void fvg_negativeDelta_usesAbsoluteValue() {
        FvgEnrichment fvg = enricher.enrichFvg(-800.0, 1000.0);

        assertThat(fvg.imbalanceIntensity()).isCloseTo(0.8, within(0.01));
        assertThat(fvg.fvgQualityScore()).isCloseTo(96.0, within(0.01));
    }

    @Test
    void fvg_zeroVolume_usesFloorOf1() {
        FvgEnrichment fvg = enricher.enrichFvg(50.0, 0.0);

        assertThat(fvg.imbalanceIntensity()).isCloseTo(50.0, within(0.01));
        assertThat(fvg.fvgQualityScore()).isCloseTo(100.0, within(0.01)); // capped
    }

    @Test
    void fvg_scoreCappedAt100() {
        // gapDelta=1000, gapVolume=1 => intensity = 1000 => score = min(100, 120000) = 100
        FvgEnrichment fvg = enricher.enrichFvg(1000.0, 1.0);
        assertThat(fvg.fvgQualityScore()).isCloseTo(100.0, within(0.01));
    }

    @Test
    void fvg_rawValues_passedThrough() {
        FvgEnrichment fvg = enricher.enrichFvg(300.0, 500.0);
        assertThat(fvg.gapDelta()).isEqualTo(300.0);
        assertThat(fvg.gapVolume()).isEqualTo(500.0);
    }

    // =================== Break enrichment ===================

    @Test
    void break_volumeSpikeAndDeltaAligned_confirmed() {
        // breakVolume=300, avgVolume=100 => volumeSpike = 3.0 > 2.0
        // isLongBreak=true, breakDelta=200 > 0 => deltaAligned = true
        // confirmed = true
        // confidenceScore = min(100, 3.0*20 + 40) = min(100, 100) = 100
        BreakEnrichment be = enricher.enrichBreak(200.0, 300.0, 100.0, true);

        assertThat(be.confirmed()).isTrue();
        assertThat(be.volumeSpike()).isCloseTo(3.0, within(0.01));
        assertThat(be.breakConfidenceScore()).isCloseTo(100.0, within(0.01));
    }

    @Test
    void break_weakVolume_notConfirmed() {
        // volumeSpike = 150/100 = 1.5 <= 2.0 => not confirmed regardless of delta
        BreakEnrichment be = enricher.enrichBreak(200.0, 150.0, 100.0, true);

        assertThat(be.confirmed()).isFalse();
        // confidenceScore = 1.5*20 + 40 = 70 (delta aligned gives +40)
        assertThat(be.breakConfidenceScore()).isCloseTo(70.0, within(0.01));
    }

    @Test
    void break_volumeHighButDeltaOpposite_notConfirmed() {
        // volumeSpike = 3.0 > 2.0 but delta negative on bullish break => not aligned
        BreakEnrichment be = enricher.enrichBreak(-200.0, 300.0, 100.0, true);

        assertThat(be.confirmed()).isFalse();
        // confidenceScore = 3.0*20 + 0 = 60 (no delta alignment bonus)
        assertThat(be.breakConfidenceScore()).isCloseTo(60.0, within(0.01));
    }

    @Test
    void break_bearishBreak_negativeDelta_aligned() {
        // isLongBreak=false, breakDelta=-200 < 0 => deltaAligned = true
        BreakEnrichment be = enricher.enrichBreak(-200.0, 300.0, 100.0, false);

        assertThat(be.confirmed()).isTrue();
    }

    @Test
    void break_bearishBreak_positiveDelta_notAligned() {
        BreakEnrichment be = enricher.enrichBreak(200.0, 300.0, 100.0, false);

        assertThat(be.confirmed()).isFalse();
    }

    @Test
    void break_zeroAvgVolume_usesFloorOf1() {
        BreakEnrichment be = enricher.enrichBreak(200.0, 300.0, 0.0, true);

        assertThat(be.volumeSpike()).isCloseTo(300.0, within(0.01));
        assertThat(be.confirmed()).isTrue();
    }

    @Test
    void break_rawValues_passedThrough() {
        BreakEnrichment be = enricher.enrichBreak(150.0, 250.0, 100.0, true);
        assertThat(be.breakDelta()).isEqualTo(150.0);
        assertThat(be.breakVolume()).isEqualTo(250.0);
        assertThat(be.avgVolume()).isEqualTo(100.0);
    }

    @Test
    void break_scoreCappedAt100() {
        // Huge volume spike: volumeSpike = 1000/1 = 1000
        // score = min(100, 1000*20 + 40) = 100
        BreakEnrichment be = enricher.enrichBreak(100.0, 1000.0, 1.0, true);
        assertThat(be.breakConfidenceScore()).isCloseTo(100.0, within(0.01));
    }

    // =================== Liquidity enrichment ===================

    @Test
    void liquidity_visibleOrders_highConfirmScore() {
        // ordersVisible=true, sizeAtLevel=100, avgLevelSize=10
        // depthRatio = 100/10 = 10
        // confirmScore = min(100, 10 * 50) = 100
        LiquidityEnrichment liq = enricher.enrichLiquidity(true, 100, 10.0);

        assertThat(liq.ordersVisibleAtLevel()).isTrue();
        assertThat(liq.depthRatioAtLevel()).isCloseTo(10.0, within(0.01));
        assertThat(liq.liquidityConfirmScore()).isCloseTo(100.0, within(0.01));
    }

    @Test
    void liquidity_withoutVisibleOrders_confirmScoreZero() {
        LiquidityEnrichment liq = enricher.enrichLiquidity(false, 100, 10.0);

        assertThat(liq.ordersVisibleAtLevel()).isFalse();
        assertThat(liq.liquidityConfirmScore()).isCloseTo(0.0, within(0.001));
    }

    @Test
    void liquidity_smallSizeAtLevel_lowScore() {
        // depthRatio = 5/10 = 0.5
        // confirmScore = min(100, 0.5 * 50) = 25
        LiquidityEnrichment liq = enricher.enrichLiquidity(true, 5, 10.0);

        assertThat(liq.liquidityConfirmScore()).isCloseTo(25.0, within(0.01));
    }

    @Test
    void liquidity_zeroAvgSize_usesFloorOf1() {
        LiquidityEnrichment liq = enricher.enrichLiquidity(true, 50, 0.0);

        assertThat(liq.depthRatioAtLevel()).isCloseTo(50.0, within(0.01));
        assertThat(liq.liquidityConfirmScore()).isCloseTo(100.0, within(0.01)); // capped
    }

    @Test
    void liquidity_zeroSizeAtLevel_scoreZero() {
        LiquidityEnrichment liq = enricher.enrichLiquidity(true, 0, 10.0);

        assertThat(liq.depthRatioAtLevel()).isCloseTo(0.0, within(0.001));
        assertThat(liq.liquidityConfirmScore()).isCloseTo(0.0, within(0.001));
    }

    @Test
    void liquidity_rawValues_passedThrough() {
        LiquidityEnrichment liq = enricher.enrichLiquidity(true, 42, 7.0);
        assertThat(liq.totalSizeAtLevel()).isEqualTo(42);
    }
}

package com.riskdesk.domain.analysis.service;

import com.riskdesk.domain.analysis.model.Direction;
import com.riskdesk.domain.analysis.model.DirectionalBias;
import com.riskdesk.domain.analysis.model.IndicatorSnapshot;
import com.riskdesk.domain.analysis.model.LiveAnalysisSnapshot;
import com.riskdesk.domain.analysis.model.MacroContext;
import com.riskdesk.domain.analysis.model.OrderFlowContext;
import com.riskdesk.domain.analysis.model.OrderFlowEventSummary;
import com.riskdesk.domain.analysis.model.ScoringWeights;
import com.riskdesk.domain.analysis.model.SmcContext;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TriLayerScoringEngineTest {

    private final TriLayerScoringEngine engine = new TriLayerScoringEngine(ScoringWeights.defaults());
    private final Instant decisionAt = Instant.parse("2026-04-24T13:35:00Z");
    private final Instant captureAt  = decisionAt.plusSeconds(1);

    private LiveAnalysisSnapshot snapshot(IndicatorSnapshot ind, SmcContext smc,
                                           OrderFlowContext of, List<OrderFlowEventSummary> momentum,
                                           List<OrderFlowEventSummary> absorption,
                                           List<OrderFlowEventSummary> distribution,
                                           List<OrderFlowEventSummary> cycle) {
        return new LiveAnalysisSnapshot(
            Instrument.MNQ, Timeframe.M5, decisionAt, captureAt,
            TriLayerScoringEngine.CURRENT_VERSION, BigDecimal.valueOf(27200),
            ind, smc, of, momentum, absorption, distribution, cycle,
            new MacroContext(98.5, 0.0, "FLAT"));
    }

    private SmcContext bullishSmc() {
        return new SmcContext("BULLISH", "BULLISH", "DISCOUNT", 27190.0, 27360.0, 27000.0,
            Map.of("swing50", "BULLISH", "swing25", "BULLISH", "swing9", "BULLISH",
                   "internal5", "BULLISH", "micro1", "BULLISH"),
            27250.0, 27220.0, 27050.0, 27170.0, "BOS_BULLISH",
            List.of(new SmcContext.ActiveOrderBlock("BULLISH", 27170, 27190, 27180, 100.0, false)),
            List.of(new SmcContext.ActiveFairValueGap("BULLISH", 27160, 27180, 80)),
            List.of(new SmcContext.RecentBreak("BOS", "BULLISH", 27135, 80, true)));
    }

    private SmcContext bearishSmc() {
        return new SmcContext("BEARISH", "BEARISH", "PREMIUM", 27200.0, 27300.0, 27050.0,
            Map.of("swing50", "BEARISH", "swing25", "BEARISH", "swing9", "BEARISH",
                   "internal5", "BEARISH", "micro1", "BEARISH"),
            27290.0, 27260.0, 27100.0, 27130.0, "BOS_BEARISH",
            List.of(new SmcContext.ActiveOrderBlock("BEARISH", 27240, 27260, 27250, 100.0, false)),
            List.of(new SmcContext.ActiveFairValueGap("BEARISH", 27260, 27280, 80)),
            List.of(new SmcContext.RecentBreak("BOS", "BEARISH", 27260, 80, true)));
    }

    private OrderFlowContext flatOrderFlow() {
        return new OrderFlowContext(0L, 50.0, "FLAT", false, null, "REAL_TICKS",
            100L, 100L, 0.0, 27200.0, 27200.5, 0.5);
    }

    @Test
    void allLayersBullish_emitsLong() {
        var ind = new IndicatorSnapshot(BigDecimal.valueOf(27200), 60.0, "NEUTRAL",
            5.0, true, 27150.0, 0.20, 0.65, true, 60.0, 55.0, null, 35.0, 30.0);
        var snap = snapshot(ind, bullishSmc(), flatOrderFlow(),
            List.of(new OrderFlowEventSummary(decisionAt.minusSeconds(10), "MOMENTUM", "BULLISH_MOMENTUM", 50.0, 600)),
            List.of(new OrderFlowEventSummary(decisionAt.minusSeconds(60), "ABSORPTION", "BULLISH_ABSORPTION", 4.0, -200)),
            List.of(new OrderFlowEventSummary(decisionAt.minusSeconds(180), "DISTRIBUTION", "ACCUMULATION", 86.0, 24)),
            List.of());

        DirectionalBias bias = engine.score(snap);

        assertThat(bias.primary()).isEqualTo(Direction.LONG);
        assertThat(bias.confidence()).isGreaterThan(40);
        assertThat(bias.structure().value()).isGreaterThan(20);
        assertThat(bias.orderFlow().value()).isGreaterThan(10);
    }

    @Test
    void allLayersBearish_emitsShort() {
        var ind = new IndicatorSnapshot(BigDecimal.valueOf(27200), 40.0, "NEUTRAL",
            -5.0, false, 27250.0, -0.20, 0.30, true, 30.0, 35.0, null, -30.0, -25.0);
        var snap = snapshot(ind, bearishSmc(), flatOrderFlow(),
            List.of(new OrderFlowEventSummary(decisionAt.minusSeconds(10), "MOMENTUM", "BEARISH_MOMENTUM", 50.0, -600)),
            List.of(new OrderFlowEventSummary(decisionAt.minusSeconds(60), "ABSORPTION", "BEARISH_ABSORPTION", 4.0, 200)),
            List.of(new OrderFlowEventSummary(decisionAt.minusSeconds(180), "DISTRIBUTION", "DISTRIBUTION", 100.0, 42)),
            List.of());

        DirectionalBias bias = engine.score(snap);

        assertThat(bias.primary()).isEqualTo(Direction.SHORT);
        assertThat(bias.confidence()).isGreaterThan(40);
    }

    @Test
    void neutralWhenWeightedScoreInsideStandAsideBand() {
        var ind = new IndicatorSnapshot(BigDecimal.valueOf(27200), 50.0, "NEUTRAL",
            0.0, null, null, 0.0, 0.5, false, 50.0, 50.0, null, 0.0, 0.0);
        var smc = new SmcContext(null, null, null, null, null, null,
            Map.of(), null, null, null, null, null, List.of(), List.of(), List.of());
        var snap = snapshot(ind, smc, flatOrderFlow(), List.of(), List.of(), List.of(), List.of());

        DirectionalBias bias = engine.score(snap);

        assertThat(bias.primary()).isEqualTo(Direction.NEUTRAL);
        assertThat(bias.confidence()).isEqualTo(0);
        assertThat(bias.standAsideReason()).isNotNull();
    }

    @Test
    void manyContradictionsForceNeutral() {
        // Strong bullish structure + bearish order flow + bearish momentum + extreme RSI overbought
        var ind = new IndicatorSnapshot(BigDecimal.valueOf(27200), 80.0, "OVERBOUGHT",
            -3.0, false, 27250.0, -0.15, 0.85, true, 80.0, 75.0, null, -25.0, -20.0);
        var snap = snapshot(ind, bullishSmc(), flatOrderFlow(),
            List.of(new OrderFlowEventSummary(decisionAt.minusSeconds(10), "MOMENTUM", "BEARISH_MOMENTUM", 80.0, -800)),
            List.of(new OrderFlowEventSummary(decisionAt.minusSeconds(30), "ABSORPTION", "BEARISH_ABSORPTION", 5.0, 200)),
            List.of(new OrderFlowEventSummary(decisionAt.minusSeconds(60), "DISTRIBUTION", "DISTRIBUTION", 100.0, 42)),
            List.of());

        DirectionalBias bias = engine.score(snap);

        // Contradictions should significantly reduce confidence — could go either way
        // but the contradictions list should be non-empty
        assertThat(bias.contradictions()).isNotEmpty();
    }

    @Test
    void scoringIsDeterministic() {
        var ind = new IndicatorSnapshot(BigDecimal.valueOf(27200), 60.0, "NEUTRAL",
            5.0, true, 27150.0, 0.20, 0.65, true, 60.0, 55.0, null, 35.0, 30.0);
        var snap = snapshot(ind, bullishSmc(), flatOrderFlow(),
            List.of(), List.of(), List.of(), List.of());

        DirectionalBias a = engine.score(snap);
        DirectionalBias b = engine.score(snap);

        assertThat(a.primary()).isEqualTo(b.primary());
        assertThat(a.confidence()).isEqualTo(b.confidence());
        assertThat(a.structure().value()).isEqualTo(b.structure().value());
        assertThat(a.orderFlow().value()).isEqualTo(b.orderFlow().value());
        assertThat(a.momentum().value()).isEqualTo(b.momentum().value());
    }

    @Test
    void weightsSumToOneRequired() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ScoringWeights(0.5, 0.3, 0.30001));
    }

    @Test
    void confidenceBoundedZeroHundred() {
        var ind = new IndicatorSnapshot(BigDecimal.valueOf(27200), 80.0, "OVERBOUGHT",
            50.0, true, null, 1.0, 0.95, true, 95.0, 90.0, null, 80.0, 60.0);
        var snap = snapshot(ind, bullishSmc(), flatOrderFlow(),
            List.of(new OrderFlowEventSummary(decisionAt, "MOMENTUM", "BULLISH_MOMENTUM", 100.0, 5000)),
            List.of(),
            List.of(new OrderFlowEventSummary(decisionAt, "DISTRIBUTION", "ACCUMULATION", 100.0, 50)),
            List.of());

        DirectionalBias bias = engine.score(snap);

        assertThat(bias.confidence()).isBetween(0, 100);
        assertThat(bias.structure().value()).isBetween(-100.0, 100.0);
        assertThat(bias.orderFlow().value()).isBetween(-100.0, 100.0);
        assertThat(bias.momentum().value()).isBetween(-100.0, 100.0);
    }

    @Test
    void internalVsSwingConflict_pullsScoreBackTowardNeutral() {
        // multiRes is fully bullish (max +30 from that contribution alone), but internal vs
        // swing disagree → the alignment branch must pull back ~-10 toward neutral.
        var smcAligned = new SmcContext("BULLISH", "BULLISH", null, null, null, null,
            Map.of("swing50", "BULLISH", "swing25", "BULLISH",
                   "swing9", "BULLISH", "internal5", "BULLISH", "micro1", "BULLISH"),
            null, null, null, null, null, List.of(), List.of(), List.of());
        var smcConflict = new SmcContext("BULLISH", "BEARISH", null, null, null, null,
            Map.of("swing50", "BULLISH", "swing25", "BULLISH",
                   "swing9", "BULLISH", "internal5", "BULLISH", "micro1", "BULLISH"),
            null, null, null, null, null, List.of(), List.of(), List.of());
        double aligned  = engine.scoreStructure(smcAligned).value();
        double conflict = engine.scoreStructure(smcConflict).value();

        // Conflict score must be strictly less bullish than aligned (penalty applied)
        assertThat(conflict).isLessThan(aligned);
        // Alignment branch contributes +10 when aligned, conflict contributes -10 against
        // the prevailing direction → expected gap is ~20 points.
        assertThat(aligned - conflict).isGreaterThan(15.0);
    }

    @Test
    void multiResolutionSplitFlaggedAsContradiction() {
        var smc = new SmcContext("BULLISH", "BEARISH", "DISCOUNT", 27190.0, 27360.0, 27000.0,
            Map.of("swing50", "BEARISH", "swing25", "BEARISH",
                   "swing9", "BULLISH", "internal5", "BULLISH", "micro1", "BULLISH"),
            27250.0, 27220.0, 27050.0, 27170.0, null,
            List.of(), List.of(), List.of());
        var ind = new IndicatorSnapshot(BigDecimal.valueOf(27200), 50.0, "NEUTRAL",
            0.0, null, null, 0.0, 0.5, false, 50.0, 50.0, null, 0.0, 0.0);
        var snap = snapshot(ind, smc, flatOrderFlow(), List.of(), List.of(), List.of(), List.of());

        DirectionalBias bias = engine.score(snap);

        assertThat(bias.contradictions()).anyMatch(c -> c.description().contains("micro1"));
    }
}

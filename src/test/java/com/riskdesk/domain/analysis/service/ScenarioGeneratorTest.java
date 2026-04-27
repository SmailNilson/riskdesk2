package com.riskdesk.domain.analysis.service;

import com.riskdesk.domain.analysis.model.Direction;
import com.riskdesk.domain.analysis.model.DirectionalBias;
import com.riskdesk.domain.analysis.model.IndicatorSnapshot;
import com.riskdesk.domain.analysis.model.LiveAnalysisSnapshot;
import com.riskdesk.domain.analysis.model.MacroContext;
import com.riskdesk.domain.analysis.model.MomentumScore;
import com.riskdesk.domain.analysis.model.OrderFlowContext;
import com.riskdesk.domain.analysis.model.OrderFlowScore;
import com.riskdesk.domain.analysis.model.SmcContext;
import com.riskdesk.domain.analysis.model.StructureScore;
import com.riskdesk.domain.analysis.model.TradeScenario;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScenarioGeneratorTest {

    private final ScenarioGenerator generator = new ScenarioGenerator();
    private final Instant t = Instant.parse("2026-04-24T13:35:00Z");

    @Test
    void shortBias_buildsContinuationAtBearishOb() {
        // Layout: price 27100, bearish OB above (entry zone), strongHigh tight (close to OB top),
        // equilibrium below price (TP1) → R:R well above 1.0.
        var smc = new SmcContext("BEARISH", "BEARISH", "PREMIUM", 27050.0, 27260.0, 26940.0,
            Map.of(), 27160.0, 27130.0, 26950.0, 26980.0, null,
            List.of(new SmcContext.ActiveOrderBlock("BEARISH", 27140, 27155, 27148, 100.0, false)),
            List.of(), List.of());
        var snap = simpleSnapshot(smc, BigDecimal.valueOf(27100));
        var bias = bias(snap, Direction.SHORT, 70);

        var scenarios = generator.generate(snap, bias);

        // Sum of probabilities ≈ 1.0
        double sum = scenarios.stream().mapToDouble(TradeScenario::probability).sum();
        assertThat(sum).isCloseTo(1.0, within(0.05));

        var continuation = scenarios.stream()
            .filter(s -> "Continuation".equals(s.name()))
            .findFirst().orElseThrow();
        assertThat(continuation.direction()).isEqualTo(Direction.SHORT);
        assertThat(continuation.entry().doubleValue()).isEqualTo(27140.0);
    }

    @Test
    void longBias_buildsContinuationAtBullishOb() {
        // Price 27210 above bullish OB top 27190 (entry), tight strongLow gives small risk,
        // equilibrium 27260 above price → comfortable RR.
        var smc = new SmcContext("BULLISH", "BULLISH", "DISCOUNT", 27260.0, 27400.0, 27050.0,
            Map.of(), 27360.0, 27310.0, 27170.0, 27200.0, null,
            List.of(new SmcContext.ActiveOrderBlock("BULLISH", 27185, 27195, 27190, 100.0, false)),
            List.of(), List.of());
        var snap = simpleSnapshot(smc, BigDecimal.valueOf(27210));
        var bias = bias(snap, Direction.LONG, 70);

        var scenarios = generator.generate(snap, bias);
        var continuation = scenarios.stream()
            .filter(s -> "Continuation".equals(s.name()))
            .findFirst().orElseThrow();

        assertThat(continuation.direction()).isEqualTo(Direction.LONG);
        assertThat(continuation.entry().doubleValue()).isEqualTo(27195.0);
        assertThat(continuation.stopLoss().doubleValue()).isLessThan(27195.0);
    }

    @Test
    void neutralBias_returnsRangeScenarioOnly() {
        var smc = new SmcContext(null, null, null, null, null, null,
            Map.of(), null, null, null, null, null, List.of(), List.of(), List.of());
        var snap = simpleSnapshot(smc, BigDecimal.valueOf(27200));
        var bias = DirectionalBias.standAside(snap.instrument(), snap.timeframe(),
            snap.decisionTimestamp(),
            StructureScore.neutral(), OrderFlowScore.neutral(), MomentumScore.neutral(),
            List.of(), "Below stand-aside band");

        var scenarios = generator.generate(snap, bias);

        // Range is always present; reversal can be present too with NEUTRAL inverse heuristic.
        // Continuation must NOT be present when bias is stand-aside.
        assertThat(scenarios).noneMatch(s -> "Continuation".equals(s.name()));
        double sum = scenarios.stream().mapToDouble(TradeScenario::probability).sum();
        assertThat(sum).isCloseTo(1.0, within(0.05));
    }

    @Test
    void scenariosNeverHaveNegativeProbability() {
        var smc = new SmcContext("BULLISH", "BULLISH", "DISCOUNT", 27190.0, 27360.0, 27040.0,
            Map.of(), 27250.0, 27220.0, 27050.0, 27170.0, null,
            List.of(new SmcContext.ActiveOrderBlock("BULLISH", 27170, 27190, 27180, 100.0, false)),
            List.of(), List.of());
        var snap = simpleSnapshot(smc, BigDecimal.valueOf(27200));
        var bias = bias(snap, Direction.LONG, 90);

        var scenarios = generator.generate(snap, bias);

        for (var s : scenarios) {
            assertThat(s.probability()).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(1.0);
        }
    }

    private LiveAnalysisSnapshot simpleSnapshot(SmcContext smc, BigDecimal price) {
        var ind = new IndicatorSnapshot(price, 50.0, "NEUTRAL",
            0.0, null, null, 0.0, 0.5, false, 50.0, 50.0, null, 0.0, 0.0);
        var of = new OrderFlowContext(0L, 50.0, "FLAT", false, null, "REAL_TICKS",
            100L, 100L, 0.0, price.doubleValue(), price.doubleValue() + 0.5, 0.5);
        return new LiveAnalysisSnapshot(
            Instrument.MNQ, Timeframe.M5, t, t.plusSeconds(1),
            TriLayerScoringEngine.CURRENT_VERSION, price, ind, smc, of,
            List.of(), List.of(), List.of(), List.of(),
            new MacroContext(98.5, 0.0, "FLAT"));
    }

    private DirectionalBias bias(LiveAnalysisSnapshot snap, Direction dir, int confidence) {
        return new DirectionalBias(snap.instrument(), snap.timeframe(),
            snap.decisionTimestamp(), dir, confidence,
            new StructureScore(40.0, List.of()),
            new OrderFlowScore(20.0, List.of()),
            new MomentumScore(10.0, List.of()),
            List.of(), List.of(), List.of(), null);
    }
}

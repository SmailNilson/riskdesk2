package com.riskdesk.domain.engine.strategy.agent.trigger;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;

import java.util.List;

/**
 * TRIGGER agent — votes from the precomputed {@link OrderFlowPattern}.
 *
 * <p>Replaces {@code DeltaFlowTriggerAgent}, which read the raw
 * {@code TickAggregation.divergenceDetected} flag and the instantaneous
 * {@code cumulativeDelta.signum()}. That approach had two stability problems:
 * <ol>
 *   <li>{@code divergenceDetected} flipped on/off every scan as the latest 1m
 *       bar's price-vs-delta direction wobbled — there was no rolling
 *       confirmation.</li>
 *   <li>The {@code signum()} sign check on cumulative delta could swing the
 *       vote 140 points (from -70 to +70) on a single tick crossing zero,
 *       which moved the {@code finalScore} by ±28 points scan-to-scan via the
 *       0.20 TRIGGER weight.</li>
 * </ol>
 *
 * <p>The Quant {@code OrderFlowPatternDetector} that builds the
 * {@link PatternAnalysis} on the {@link TriggerContext} already has built-in
 * hysteresis ({@code PRICE_STABLE_BAND}, last-3-prices window,
 * {@code DELTA_STRONG} threshold, {@code INDETERMINE} fallback for
 * single-scan / low-confidence reads). This agent simply maps the stable
 * 4-quadrant verdict to a signed LSAR vote.
 *
 * <h2>Mapping table</h2>
 * <table>
 *   <tr><th>OrderFlowPattern</th><th>Confidence</th><th>Vote</th><th>Conf out</th></tr>
 *   <tr><td>VRAI_ACHAT</td>             <td>HIGH</td>  <td>+90</td> <td>0.85</td></tr>
 *   <tr><td>VRAI_ACHAT</td>             <td>MEDIUM</td><td>+60</td> <td>0.65</td></tr>
 *   <tr><td>ABSORPTION_HAUSSIERE</td>   <td>HIGH</td>  <td>+70</td> <td>0.75</td></tr>
 *   <tr><td>ABSORPTION_HAUSSIERE</td>   <td>MEDIUM</td><td>+50</td> <td>0.55</td></tr>
 *   <tr><td>VRAIE_VENTE</td>            <td>HIGH</td>  <td>-90</td> <td>0.85</td></tr>
 *   <tr><td>VRAIE_VENTE</td>            <td>MEDIUM</td><td>-60</td> <td>0.65</td></tr>
 *   <tr><td>DISTRIBUTION_SILENCIEUSE</td><td>HIGH</td> <td>-70</td> <td>0.75</td></tr>
 *   <tr><td>DISTRIBUTION_SILENCIEUSE</td><td>MEDIUM</td><td>-50</td><td>0.55</td></tr>
 *   <tr><td>any</td>                    <td>LOW</td>   <td colspan="2">abstain</td></tr>
 *   <tr><td>INDETERMINE</td>            <td>—</td>     <td colspan="2">abstain</td></tr>
 * </table>
 *
 * <p>Continuation patterns ({@code VRAI_ACHAT}, {@code VRAIE_VENTE}) get a
 * larger magnitude than contrarian ones ({@code ABSORPTION_HAUSSIERE},
 * {@code DISTRIBUTION_SILENCIEUSE}) because contrarian setups need more
 * conviction before swinging the strategy decision.
 *
 * <p>The output confidence is finally multiplied by
 * {@link TriggerContext#qualityMultiplier()} so CLV-estimated data still
 * downweights to ~50% and unavailable data to ~20%.
 */
public final class QuantFlowPatternAgent implements StrategyAgent {

    public static final String ID = "quant-flow-pattern";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyLayer layer() {
        return StrategyLayer.TRIGGER;
    }

    @Override
    public AgentVote evaluate(StrategyInput input) {
        TriggerContext trig = input.trigger();
        PatternAnalysis pattern = trig.orderFlowPattern();

        if (pattern == null) {
            return AgentVote.abstain(ID, StrategyLayer.TRIGGER,
                "Order-flow pattern not yet computed (warming up)");
        }
        if (pattern.type() == OrderFlowPattern.INDETERMINE
            || pattern.confidence() == PatternAnalysis.Confidence.LOW) {
            return AgentVote.abstain(ID, StrategyLayer.TRIGGER,
                "Pattern " + pattern.type() + " (" + pattern.confidence() + ") — too weak to vote");
        }

        Mapping m = mappingFor(pattern.type(), pattern.confidence());
        if (m == null) {
            // Defensive: enum was exhaustive at write time, but a future enum
            // value would land here without crashing the strategy evaluation.
            return AgentVote.abstain(ID, StrategyLayer.TRIGGER,
                "Unhandled pattern " + pattern.type());
        }

        double confidence = m.confidence * trig.qualityMultiplier();
        List<String> evidence = List.of(
            pattern.label() + " (" + pattern.confidence() + ")",
            pattern.reason(),
            "quality=" + trig.quality()
        );
        return AgentVote.of(ID, StrategyLayer.TRIGGER, m.vote, confidence, evidence);
    }

    /** Lookup of the (vote, confidence) pair for a (pattern, confidence) pair. */
    private static Mapping mappingFor(OrderFlowPattern type, PatternAnalysis.Confidence conf) {
        boolean high = conf == PatternAnalysis.Confidence.HIGH;
        return switch (type) {
            case VRAI_ACHAT               -> high ? new Mapping(+90, 0.85) : new Mapping(+60, 0.65);
            case ABSORPTION_HAUSSIERE     -> high ? new Mapping(+70, 0.75) : new Mapping(+50, 0.55);
            case VRAIE_VENTE              -> high ? new Mapping(-90, 0.85) : new Mapping(-60, 0.65);
            case DISTRIBUTION_SILENCIEUSE -> high ? new Mapping(-70, 0.75) : new Mapping(-50, 0.55);
            case INDETERMINE              -> null; // handled earlier
        };
    }

    private record Mapping(int vote, double confidence) {}
}

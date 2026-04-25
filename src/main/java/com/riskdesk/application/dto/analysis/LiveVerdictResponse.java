package com.riskdesk.application.dto.analysis;

import com.riskdesk.domain.analysis.model.DirectionalBias;
import com.riskdesk.domain.analysis.model.LiveVerdict;
import com.riskdesk.domain.analysis.model.TradeScenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LiveVerdictResponse(
    String instrument,
    String timeframe,
    Instant decisionTimestamp,
    int scoringEngineVersion,
    BigDecimal currentPrice,
    BiasView bias,
    List<TradeScenario> scenarios,
    Instant validUntil
) {

    public static LiveVerdictResponse from(LiveVerdict v) {
        DirectionalBias b = v.bias();
        return new LiveVerdictResponse(
            v.instrument().name(),
            v.timeframe().label(),
            v.decisionTimestamp(),
            v.scoringEngineVersion(),
            v.currentPrice(),
            new BiasView(
                b.primary().name(),
                b.confidence(),
                b.structure().value(),
                b.orderFlow().value(),
                b.momentum().value(),
                b.bullishFactors(),
                b.bearishFactors(),
                b.contradictions(),
                b.standAsideReason()
            ),
            v.scenarios(),
            v.validUntil()
        );
    }

    public record BiasView(
        String primary,
        int confidence,
        double structureScore,
        double orderFlowScore,
        double momentumScore,
        List<?> bullishFactors,
        List<?> bearishFactors,
        List<?> contradictions,
        String standAsideReason
    ) {}
}

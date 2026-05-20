package com.riskdesk.presentation.quant.dto;

import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupRecommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JSON-serialisable view of a {@link SetupRecommendation}.
 * Sent both over REST and WebSocket (STOMP topic).
 */
public record SetupView(
    UUID id,
    String instrument,
    String template,
    String style,
    String phase,
    String regime,
    String direction,
    double finalScore,
    BigDecimal entryPrice,
    BigDecimal slPrice,
    BigDecimal tp1Price,
    BigDecimal tp2Price,
    double rrRatio,
    String playbookId,
    List<GateResultView> gateResults,
    Instant detectedAt,
    Instant updatedAt
) {
    public record GateResultView(String gateName, boolean passed, String reason) {}

    public static SetupView from(SetupRecommendation r) {
        List<GateResultView> gates = r.gateResults().stream()
            .map(g -> new GateResultView(g.gateName(), g.passed(), g.reason()))
            .toList();
        return new SetupView(
            r.id(),
            r.instrument().name(),
            r.template().name(),
            r.style().name(),
            r.phase().name(),
            r.regime().name(),
            r.direction().name(),
            r.finalScore(),
            r.entryPrice(),
            r.slPrice(),
            r.tp1Price(),
            r.tp2Price(),
            r.rrRatio(),
            r.playbookId(),
            gates,
            r.detectedAt(),
            r.updatedAt()
        );
    }
}

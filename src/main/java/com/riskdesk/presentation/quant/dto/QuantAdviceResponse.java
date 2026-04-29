package com.riskdesk.presentation.quant.dto;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.advisor.AiAdvice;

/**
 * Wire shape of an AI advisor verdict returned by {@code POST /api/quant/ai-advice/{instr}}.
 */
public record QuantAdviceResponse(
    String instrument,
    String verdict,
    String reasoning,
    String risk,
    double confidence,
    String model,
    String generatedAt
) {
    public static QuantAdviceResponse from(Instrument instrument, AiAdvice advice) {
        return new QuantAdviceResponse(
            instrument.name(),
            advice.verdict() == null ? "UNAVAILABLE" : advice.verdict().name(),
            advice.reasoning(),
            advice.risk(),
            advice.confidence(),
            advice.model(),
            advice.generatedAt() != null ? advice.generatedAt().toString() : null
        );
    }
}

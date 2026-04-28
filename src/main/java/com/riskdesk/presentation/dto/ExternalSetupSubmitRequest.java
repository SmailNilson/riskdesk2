package com.riskdesk.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalSetupSubmitRequest(
    @NotBlank String instrument,
    @NotBlank String direction,
    @NotNull BigDecimal entry,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    String confidence,
    String triggerLabel,
    String payloadJson,
    String source,
    String sourceRef
) {
}

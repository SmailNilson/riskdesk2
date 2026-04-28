package com.riskdesk.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalSetupValidateRequest(
    Integer quantity,
    String brokerAccountId,
    BigDecimal overrideEntryPrice,
    String validatedBy
) {
}

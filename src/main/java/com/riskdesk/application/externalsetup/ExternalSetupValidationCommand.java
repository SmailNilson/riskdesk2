package com.riskdesk.application.externalsetup;

import java.math.BigDecimal;

/**
 * Validation request for an existing pending setup.
 * <p>{@code overrideEntryPrice} lets the caller pass the live price observed at click-time —
 * if absent, the setup's stored entry is used (slippage risk).
 */
public record ExternalSetupValidationCommand(
    Long setupId,
    String validatedBy,
    Integer quantity,
    String brokerAccountId,
    BigDecimal overrideEntryPrice
) {
}

package com.riskdesk.presentation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Arms a trade directly from an order-flow signal, bypassing the Mentor review
 * path. The service creates a synthetic mentor review with the supplied trade
 * plan so the existing execution pipeline (size validation, normalization,
 * idempotence, IBKR submission) can be reused unchanged.
 * <p>
 * Protected by feature flag {@code riskdesk.orderflow.quick-execution.enabled}
 * (disabled by default). The underlying IBKR order is still only sent when the
 * user clicks Submit Entry (or calls {@code POST /api/mentor/executions/{id}/submit-entry}).
 */
public record OrderFlowQuickExecutionRequest(
    @NotBlank String instrument,
    @NotBlank String timeframe,
    @NotBlank @Pattern(regexp = "LONG|SHORT") String action,
    @NotNull @DecimalMin("0.0001") Double entryPrice,
    @NotNull @DecimalMin("0.0001") Double stopLoss,
    @NotNull @DecimalMin("0.0001") Double takeProfit,
    @NotNull @Positive Integer quantity,
    @NotBlank String brokerAccountId,
    @NotBlank String reason
) {
}

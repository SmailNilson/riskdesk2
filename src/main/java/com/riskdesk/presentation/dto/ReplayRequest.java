package com.riskdesk.presentation.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record ReplayRequest(
    @NotBlank String instrument,
    @NotBlank String timeframe,
    @NotNull Instant from,
    @NotNull Instant to,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double structure,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double orderFlow,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double momentum
) {
}

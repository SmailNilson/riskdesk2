package com.riskdesk.presentation.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ClosePositionRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal exitPrice
) {}

package com.riskdesk.presentation.dto;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreatePositionRequest(
        @NotNull Instrument instrument,
        @NotNull Side side,
        @Min(1) int quantity,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        String notes
) {}

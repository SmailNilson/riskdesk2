package com.riskdesk.application.dto;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;

import java.math.BigDecimal;

public record CreatePositionCommand(
    Instrument instrument,
    Side side,
    int quantity,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    String notes
) {
}

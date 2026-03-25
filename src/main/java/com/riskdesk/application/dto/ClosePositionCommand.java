package com.riskdesk.application.dto;

import java.math.BigDecimal;

public record ClosePositionCommand(BigDecimal exitPrice) {
}

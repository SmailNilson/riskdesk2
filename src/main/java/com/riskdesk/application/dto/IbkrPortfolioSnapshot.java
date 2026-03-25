package com.riskdesk.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record IbkrPortfolioSnapshot(
    boolean connected,
    String selectedAccountId,
    List<IbkrAccountView> accounts,
    BigDecimal netLiquidation,
    BigDecimal initMarginReq,
    BigDecimal availableFunds,
    BigDecimal buyingPower,
    BigDecimal grossPositionValue,
    BigDecimal totalUnrealizedPnl,
    BigDecimal totalRealizedPnl,
    String currency,
    List<IbkrPositionView> positions,
    String message
) {
}

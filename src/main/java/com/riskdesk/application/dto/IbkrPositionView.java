package com.riskdesk.application.dto;

import java.math.BigDecimal;

public record IbkrPositionView(
    String accountId,
    long conid,
    String contractDesc,
    String assetClass,
    BigDecimal position,
    BigDecimal marketPrice,
    BigDecimal marketValue,
    BigDecimal averageCost,
    BigDecimal averagePrice,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    String currency
) {
}

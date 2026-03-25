package com.riskdesk.application.dto;

import com.riskdesk.domain.model.Position;
import com.riskdesk.domain.model.Side;

import java.math.BigDecimal;
import java.time.Instant;

public record PositionView(
    Long id,
    String instrument,
    String instrumentName,
    Side side,
    BigDecimal quantity,
    BigDecimal entryPrice,
    BigDecimal currentPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    BigDecimal unrealizedPnL,
    BigDecimal riskAmount,
    BigDecimal riskRewardRatio,
    boolean open,
    Instant openedAt,
    String notes,
    String source,
    String accountId,
    String assetClass,
    boolean closable
) {
    public static PositionView from(Position p) {
        return new PositionView(
            p.getId(),
            p.getInstrument().name(),
            p.getInstrument().getDisplayName(),
            p.getSide(),
            BigDecimal.valueOf(p.getQuantity()),
            p.getEntryPrice(),
            p.getCurrentPrice(),
            p.getStopLoss(),
            p.getTakeProfit(),
            p.getUnrealizedPnL(),
            p.getRiskAmount(),
            p.getRiskRewardRatio(),
            p.isOpen(),
            p.getOpenedAt(),
            p.getNotes(),
            "LOCAL",
            null,
            "FUT",
            true
        );
    }

    public static PositionView fromIbkr(IbkrPositionView p) {
        return new PositionView(
            null,
            symbolFromContract(p.contractDesc()),
            p.contractDesc(),
            p.position().compareTo(BigDecimal.ZERO) >= 0 ? Side.LONG : Side.SHORT,
            p.position().abs(),
            p.averagePrice(),
            p.marketPrice(),
            null,
            null,
            p.unrealizedPnl(),
            null,
            null,
            true,
            null,
            null,
            "IBKR",
            p.accountId(),
            p.assetClass(),
            false
        );
    }

    private static String symbolFromContract(String contractDesc) {
        if (contractDesc == null || contractDesc.isBlank()) {
            return "IBKR";
        }
        String trimmed = contractDesc.trim();
        int idx = trimmed.indexOf(' ');
        return idx > 0 ? trimmed.substring(0, idx) : trimmed;
    }
}

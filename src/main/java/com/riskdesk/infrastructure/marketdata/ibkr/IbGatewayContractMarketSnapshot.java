package com.riskdesk.infrastructure.marketdata.ibkr;

import java.math.BigDecimal;

public record IbGatewayContractMarketSnapshot(
    BigDecimal bestPrice,
    BigDecimal bid,
    BigDecimal ask,
    Long volume
) {

    public boolean hasPrice() {
        return bestPrice != null && bestPrice.signum() > 0;
    }

    public boolean hasBidAsk() {
        return bid != null && bid.signum() > 0 && ask != null && ask.signum() > 0 && ask.compareTo(bid) >= 0;
    }

    public boolean hasVolume() {
        return volume != null && volume > 0;
    }

    public BigDecimal spread() {
        if (!hasBidAsk()) {
            return null;
        }
        return ask.subtract(bid);
    }
}

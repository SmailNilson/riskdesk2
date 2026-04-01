package com.riskdesk.domain.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;

public record DxySnapshot(
    Instant timestamp,
    BigDecimal eurusd,
    BigDecimal usdjpy,
    BigDecimal gbpusd,
    BigDecimal usdcad,
    BigDecimal usdsek,
    BigDecimal usdchf,
    BigDecimal dxyValue,
    String source,
    boolean complete
) {

    public BigDecimal component(FxPair pair) {
        return switch (pair) {
            case EURUSD -> eurusd;
            case USDJPY -> usdjpy;
            case GBPUSD -> gbpusd;
            case USDCAD -> usdcad;
            case USDSEK -> usdsek;
            case USDCHF -> usdchf;
        };
    }
}

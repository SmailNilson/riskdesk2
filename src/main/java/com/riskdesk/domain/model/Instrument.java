package com.riskdesk.domain.model;

import com.riskdesk.domain.shared.vo.ContractSpec;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public enum Instrument {

    MCL("Micro WTI Crude Oil", new BigDecimal("100"), new BigDecimal("0.01"), new BigDecimal("1.00")),
    MGC("Micro Gold", new BigDecimal("10"), new BigDecimal("0.10"), new BigDecimal("1.00")),
    E6("Euro FX Futures (6E)", new BigDecimal("125000"), new BigDecimal("0.00005"), new BigDecimal("6.25")),
    MNQ("Micro E-mini Nasdaq-100", new BigDecimal("2"), new BigDecimal("0.25"), new BigDecimal("0.50")),
    DXY("Synthetic US Dollar Index", new BigDecimal("1000"), new BigDecimal("0.005"), new BigDecimal("5.00"));

    private final String displayName;
    private final BigDecimal contractMultiplier;
    private final BigDecimal tickSize;
    private final BigDecimal tickValue;

    Instrument(String displayName, BigDecimal contractMultiplier, BigDecimal tickSize, BigDecimal tickValue) {
        this.displayName = displayName;
        this.contractMultiplier = contractMultiplier;
        this.tickSize = tickSize;
        this.tickValue = tickValue;
    }

    public String getDisplayName() { return displayName; }
    public BigDecimal getContractMultiplier() { return contractMultiplier; }
    public BigDecimal getTickSize() { return tickSize; }
    public BigDecimal getTickValue() { return tickValue; }

    public ContractSpec contractSpec() {
        return new ContractSpec(contractMultiplier, tickSize, tickValue);
    }

    public boolean isDollarSensitive() {
        return this == MCL || this == MGC || this == E6 || this == MNQ;
    }

    public boolean isExchangeTradedFuture() {
        return this != DXY;
    }

    private static final List<Instrument> EXCHANGE_TRADED_FUTURES =
        Arrays.stream(values()).filter(Instrument::isExchangeTradedFuture).toList();

    public static List<Instrument> exchangeTradedFutures() {
        return EXCHANGE_TRADED_FUTURES;
    }

    /**
     * Calculate P&L for a given price move in ticks.
     */
    public BigDecimal calculatePnL(BigDecimal entryPrice, BigDecimal currentPrice, int quantity, Side side) {
        BigDecimal priceDiff = currentPrice.subtract(entryPrice);
        if (side == Side.SHORT) {
            priceDiff = priceDiff.negate();
        }
        BigDecimal ticks = priceDiff.divide(tickSize, 0, java.math.RoundingMode.HALF_UP);
        return ticks.multiply(tickValue).multiply(BigDecimal.valueOf(quantity));
    }
}

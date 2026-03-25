package com.riskdesk.domain.shared.vo;

import java.math.BigDecimal;

/**
 * Immutable value object representing the specification of a futures contract.
 * Contains the multiplier, tick size, and tick value.
 */
public record ContractSpec(BigDecimal multiplier, BigDecimal tickSize, BigDecimal tickValue) {

    public ContractSpec {
        if (multiplier == null) {
            throw new IllegalArgumentException("Multiplier must not be null");
        }
        if (tickSize == null) {
            throw new IllegalArgumentException("Tick size must not be null");
        }
        if (tickValue == null) {
            throw new IllegalArgumentException("Tick value must not be null");
        }
        if (multiplier.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Multiplier must be positive, got: " + multiplier);
        }
        if (tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Tick size must be positive, got: " + tickSize);
        }
        if (tickValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Tick value must be positive, got: " + tickValue);
        }
    }
}

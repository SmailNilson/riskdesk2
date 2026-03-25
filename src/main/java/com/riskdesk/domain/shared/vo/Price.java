package com.riskdesk.domain.shared.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable value object representing a price with scale 5 for maximum precision.
 * Invariant: value must be strictly positive and non-null.
 */
public final class Price implements Comparable<Price> {

    private final BigDecimal value;

    private Price(BigDecimal value) {
        this.value = value;
    }

    public static Price of(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Price value must not be null");
        }
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be positive, got: " + value);
        }
        return new Price(value.setScale(5, RoundingMode.HALF_UP));
    }

    public static Price of(double value) {
        return of(BigDecimal.valueOf(value));
    }

    public static Price of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Price value must not be null");
        }
        return of(new BigDecimal(value));
    }

    public BigDecimal value() {
        return value;
    }

    public boolean isAbove(Price other) {
        return this.value.compareTo(other.value) > 0;
    }

    public boolean isBelow(Price other) {
        return this.value.compareTo(other.value) < 0;
    }

    /**
     * Returns the difference (this - other) as a BigDecimal.
     */
    public BigDecimal diff(Price other) {
        return this.value.subtract(other.value);
    }

    @Override
    public int compareTo(Price other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Price price = (Price) o;
        return value.compareTo(price.value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}

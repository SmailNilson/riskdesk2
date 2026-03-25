package com.riskdesk.domain.shared.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable value object representing a monetary amount with scale 2.
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    public static Money of(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }
        return new Money(value.setScale(2, RoundingMode.HALF_UP));
    }

    public static Money of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }
        return of(new BigDecimal(value));
    }

    public static Money of(double value) {
        return of(BigDecimal.valueOf(value));
    }

    public BigDecimal amount() {
        return amount;
    }

    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(this.amount.subtract(other.amount));
    }

    public Money multiply(int quantity) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(quantity)));
    }

    public Money negate() {
        return new Money(this.amount.negate());
    }

    public Money abs() {
        return new Money(this.amount.abs());
    }

    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNegative() {
        return amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    @Override
    public int compareTo(Money other) {
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return "-$" + amount.abs().toPlainString();
        }
        return "$" + amount.toPlainString();
    }
}

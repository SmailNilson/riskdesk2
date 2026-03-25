package com.riskdesk.domain.shared.vo;

import java.util.Objects;

/**
 * Immutable value object representing a positive quantity (>= 1).
 */
public final class Quantity implements Comparable<Quantity> {

    private final int value;

    private Quantity(int value) {
        this.value = value;
    }

    public static Quantity of(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("Quantity must be >= 1, got: " + value);
        }
        return new Quantity(value);
    }

    public int value() {
        return value;
    }

    @Override
    public int compareTo(Quantity other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Quantity quantity = (Quantity) o;
        return value == quantity.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}

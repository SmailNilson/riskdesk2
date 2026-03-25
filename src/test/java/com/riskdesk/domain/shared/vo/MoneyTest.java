package com.riskdesk.domain.shared.vo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    void ofBigDecimal_createsCorrectValue() {
        Money money = Money.of(new BigDecimal("123.45"));
        assertEquals(new BigDecimal("123.45"), money.amount());
    }

    @Test
    void ofString_parsesCorrectly() {
        Money money = Money.of("123.45");
        assertEquals(new BigDecimal("123.45"), money.amount());
    }

    @Test
    void ofDouble_createsCorrectValue() {
        Money money = Money.of(99.99);
        assertEquals(new BigDecimal("99.99"), money.amount());
    }

    @Test
    void zero_hasAmountZero() {
        assertEquals(new BigDecimal("0.00"), Money.ZERO.amount());
    }

    @Test
    void add_twoPositive_returnsCorrectSum() {
        Money a = Money.of("10.50");
        Money b = Money.of("20.30");
        Money result = a.add(b);
        assertEquals(new BigDecimal("30.80"), result.amount());
    }

    @Test
    void subtract_returnsCorrectDifference() {
        Money a = Money.of("50.00");
        Money b = Money.of("20.50");
        Money result = a.subtract(b);
        assertEquals(new BigDecimal("29.50"), result.amount());
    }

    @Test
    void multiply_byQuantity_returnsCorrectProduct() {
        Money price = Money.of("25.00");
        Money result = price.multiply(4);
        assertEquals(new BigDecimal("100.00"), result.amount());
    }

    @Test
    void negate_flipsSign() {
        Money positive = Money.of("42.00");
        Money negated = positive.negate();
        assertEquals(new BigDecimal("-42.00"), negated.amount());
    }

    @Test
    void abs_ofNegative_returnsPositive() {
        Money negative = Money.of(new BigDecimal("-15.75"));
        Money result = negative.abs();
        assertEquals(new BigDecimal("15.75"), result.amount());
    }

    @Test
    void isPositive_trueForPositive() {
        assertTrue(Money.of("1.00").isPositive());
    }

    @Test
    void isPositive_falseForZero() {
        assertFalse(Money.ZERO.isPositive());
    }

    @Test
    void isNegative_trueForNegative() {
        assertTrue(Money.of(new BigDecimal("-5.00")).isNegative());
    }

    @Test
    void isNegative_falseForPositive() {
        assertFalse(Money.of("10.00").isNegative());
    }

    @Test
    void isZero_trueForZero() {
        assertTrue(Money.ZERO.isZero());
    }

    @Test
    void isZero_falseForNonZero() {
        assertFalse(Money.of("0.01").isZero());
    }

    @Test
    void equals_sameAmount_areEqual() {
        Money a = Money.of("100.00");
        Money b = Money.of(new BigDecimal("100.00"));
        assertEquals(a, b);
    }

    @Test
    void equals_differentAmount_areNotEqual() {
        Money a = Money.of("100.00");
        Money b = Money.of("100.01");
        assertNotEquals(a, b);
    }

    @Test
    void hashCode_sameAmount_sameHash() {
        Money a = Money.of("55.55");
        Money b = Money.of("55.55");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void compareTo_ordering() {
        Money small = Money.of("10.00");
        Money large = Money.of("20.00");
        assertTrue(small.compareTo(large) < 0);
        assertTrue(large.compareTo(small) > 0);
        assertEquals(0, small.compareTo(Money.of("10.00")));
    }

    @Test
    void toString_positive_formatsWithDollarSign() {
        assertEquals("$123.45", Money.of("123.45").toString());
    }

    @Test
    void toString_negative_formatsWithNegativeDollarSign() {
        assertEquals("-$42.00", Money.of(new BigDecimal("-42.00")).toString());
    }

    @Test
    void toString_zero_formatsDollarZero() {
        assertEquals("$0.00", Money.ZERO.toString());
    }

    @Test
    void ofNull_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Money.of((BigDecimal) null));
    }

    @Test
    void ofNullString_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Money.of((String) null));
    }

    @Test
    void scaleIsAlwaysTwo() {
        Money money = Money.of(new BigDecimal("10.5"));
        assertEquals(2, money.amount().scale());
    }
}

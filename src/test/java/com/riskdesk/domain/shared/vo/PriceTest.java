package com.riskdesk.domain.shared.vo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PriceTest {

    @Test
    void ofBigDecimal_createsCorrectValue() {
        Price price = Price.of(new BigDecimal("100.12345"));
        assertEquals(new BigDecimal("100.12345"), price.value());
    }

    @Test
    void ofDouble_createsCorrectValue() {
        Price price = Price.of(99.5);
        assertEquals(0, price.value().compareTo(new BigDecimal("99.50000")));
    }

    @Test
    void ofString_createsCorrectValue() {
        Price price = Price.of("55.123");
        assertEquals(0, price.value().compareTo(new BigDecimal("55.12300")));
    }

    @Test
    void ofZero_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Price.of(BigDecimal.ZERO));
    }

    @Test
    void ofNegative_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Price.of(new BigDecimal("-1.00")));
    }

    @Test
    void ofNull_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Price.of((BigDecimal) null));
    }

    @Test
    void ofNullString_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Price.of((String) null));
    }

    @Test
    void isAbove_returnsTrue_whenHigher() {
        Price high = Price.of("100.00");
        Price low = Price.of("50.00");
        assertTrue(high.isAbove(low));
    }

    @Test
    void isAbove_returnsFalse_whenLower() {
        Price high = Price.of("100.00");
        Price low = Price.of("50.00");
        assertFalse(low.isAbove(high));
    }

    @Test
    void isBelow_returnsTrue_whenLower() {
        Price high = Price.of("100.00");
        Price low = Price.of("50.00");
        assertTrue(low.isBelow(high));
    }

    @Test
    void isBelow_returnsFalse_whenHigher() {
        Price high = Price.of("100.00");
        Price low = Price.of("50.00");
        assertFalse(high.isBelow(low));
    }

    @Test
    void diff_computesCorrectDifference() {
        Price a = Price.of("100.50");
        Price b = Price.of("90.25");
        BigDecimal diff = a.diff(b);
        assertEquals(0, diff.compareTo(new BigDecimal("10.25000")));
    }

    @Test
    void diff_canBeNegative() {
        Price a = Price.of("50.00");
        Price b = Price.of("75.00");
        BigDecimal diff = a.diff(b);
        assertTrue(diff.compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void equals_sameValue_areEqual() {
        Price a = Price.of("100.00");
        Price b = Price.of("100.00");
        assertEquals(a, b);
    }

    @Test
    void equals_differentValue_areNotEqual() {
        Price a = Price.of("100.00");
        Price b = Price.of("100.01");
        assertNotEquals(a, b);
    }

    @Test
    void hashCode_sameValue_sameHash() {
        Price a = Price.of("77.77");
        Price b = Price.of("77.77");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void compareTo_ordering() {
        Price low = Price.of("10.00");
        Price high = Price.of("20.00");
        assertTrue(low.compareTo(high) < 0);
        assertTrue(high.compareTo(low) > 0);
        assertEquals(0, low.compareTo(Price.of("10.00")));
    }

    @Test
    void value_hasScaleFive() {
        Price price = Price.of("100.1");
        assertEquals(5, price.value().scale());
    }
}

package com.riskdesk.domain.shared.vo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuantityTest {

    @Test
    void of_one_createsQuantity() {
        Quantity qty = Quantity.of(1);
        assertEquals(1, qty.value());
    }

    @Test
    void of_five_createsQuantity() {
        Quantity qty = Quantity.of(5);
        assertEquals(5, qty.value());
    }

    @Test
    void of_zero_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Quantity.of(0));
    }

    @Test
    void of_negative_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Quantity.of(-1));
    }

    @Test
    void equals_sameValue_areEqual() {
        Quantity a = Quantity.of(3);
        Quantity b = Quantity.of(3);
        assertEquals(a, b);
    }

    @Test
    void equals_differentValue_areNotEqual() {
        Quantity a = Quantity.of(3);
        Quantity b = Quantity.of(4);
        assertNotEquals(a, b);
    }

    @Test
    void hashCode_sameValue_sameHash() {
        Quantity a = Quantity.of(7);
        Quantity b = Quantity.of(7);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void compareTo_ordering() {
        Quantity small = Quantity.of(2);
        Quantity large = Quantity.of(10);
        assertTrue(small.compareTo(large) < 0);
        assertTrue(large.compareTo(small) > 0);
        assertEquals(0, small.compareTo(Quantity.of(2)));
    }
}

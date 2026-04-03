package com.riskdesk.domain.engine.smc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class SessionPdArrayCalculatorTest {

    private final SessionPdArrayCalculator calculator = new SessionPdArrayCalculator();

    @ParameterizedTest
    @CsvSource({
        "100.00, 90.00, 99.00, PREMIUM",
        "100.00, 90.00, 91.00, DISCOUNT",
        "100.00, 90.00, 95.00, EQUILIBRIUM",
        "100.00, 90.00, 95.50, EQUILIBRIUM",
        "100.00, 90.00, 94.50, EQUILIBRIUM",
    })
    void computeZone(String high, String low, String price, String expectedZone) {
        var result = calculator.compute(
            new BigDecimal(high), new BigDecimal(low), new BigDecimal(price));
        assertNotNull(result);
        assertEquals(expectedZone, result.zone());
    }

    @Test
    void flatRangeReturnsNull() {
        assertNull(calculator.compute(
            new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100")));
    }

    @Test
    void invertedRangeReturnsNull() {
        assertNull(calculator.compute(
            new BigDecimal("90"), new BigDecimal("100"), new BigDecimal("95")));
    }

    @Test
    void nullInputsReturnNull() {
        assertNull(calculator.compute(null, new BigDecimal("90"), new BigDecimal("95")));
        assertNull(calculator.compute(new BigDecimal("100"), null, new BigDecimal("95")));
        assertNull(calculator.compute(new BigDecimal("100"), new BigDecimal("90"), null));
    }

    @Test
    void equilibriumBoundary() {
        // With 5% band: equilibrium band is 94.5 to 95.5 on a 90-100 range
        var result = calculator.compute(
            new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("95.50"));
        assertNotNull(result);
        assertEquals("EQUILIBRIUM", result.zone());

        // Just above equilibrium band = PREMIUM
        var premium = calculator.compute(
            new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("95.51"));
        assertNotNull(premium);
        assertEquals("PREMIUM", premium.zone());
    }

    @Test
    void resultFieldsPopulated() {
        var result = calculator.compute(
            new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("95.00"));
        assertNotNull(result);
        assertEquals(0, new BigDecimal("100.00").compareTo(result.rangeHigh()));
        assertEquals(0, new BigDecimal("90.00").compareTo(result.rangeLow()));
        assertEquals(0, new BigDecimal("95.00").compareTo(result.equilibrium().setScale(2)));
    }

    @Test
    void invalidBandThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new SessionPdArrayCalculator(new BigDecimal("-0.1")));
        assertThrows(IllegalArgumentException.class,
            () -> new SessionPdArrayCalculator(new BigDecimal("0.6")));
    }
}

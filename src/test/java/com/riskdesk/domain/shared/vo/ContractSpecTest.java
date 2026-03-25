package com.riskdesk.domain.shared.vo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ContractSpecTest {

    @Test
    void construction_withValidValues_succeeds() {
        ContractSpec spec = new ContractSpec(
                new BigDecimal("50"),
                new BigDecimal("0.25"),
                new BigDecimal("12.50")
        );
        assertNotNull(spec);
    }

    @Test
    void accessors_returnCorrectValues() {
        BigDecimal multiplier = new BigDecimal("50");
        BigDecimal tickSize = new BigDecimal("0.25");
        BigDecimal tickValue = new BigDecimal("12.50");

        ContractSpec spec = new ContractSpec(multiplier, tickSize, tickValue);

        assertEquals(multiplier, spec.multiplier());
        assertEquals(tickSize, spec.tickSize());
        assertEquals(tickValue, spec.tickValue());
    }

    @Test
    void nullMultiplier_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new ContractSpec(null, new BigDecimal("0.25"), new BigDecimal("12.50"))
        );
    }

    @Test
    void nullTickSize_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new ContractSpec(new BigDecimal("50"), null, new BigDecimal("12.50"))
        );
    }

    @Test
    void nullTickValue_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new ContractSpec(new BigDecimal("50"), new BigDecimal("0.25"), null)
        );
    }

    @Test
    void zeroTickSize_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new ContractSpec(new BigDecimal("50"), BigDecimal.ZERO, new BigDecimal("12.50"))
        );
    }

    @Test
    void zeroMultiplier_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new ContractSpec(BigDecimal.ZERO, new BigDecimal("0.25"), new BigDecimal("12.50"))
        );
    }

    @Test
    void zeroTickValue_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new ContractSpec(new BigDecimal("50"), new BigDecimal("0.25"), BigDecimal.ZERO)
        );
    }

    @Test
    void negativeMultiplier_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new ContractSpec(new BigDecimal("-50"), new BigDecimal("0.25"), new BigDecimal("12.50"))
        );
    }

    @Test
    void equals_sameValues_areEqual() {
        ContractSpec a = new ContractSpec(
                new BigDecimal("50"), new BigDecimal("0.25"), new BigDecimal("12.50"));
        ContractSpec b = new ContractSpec(
                new BigDecimal("50"), new BigDecimal("0.25"), new BigDecimal("12.50"));
        assertEquals(a, b);
    }
}

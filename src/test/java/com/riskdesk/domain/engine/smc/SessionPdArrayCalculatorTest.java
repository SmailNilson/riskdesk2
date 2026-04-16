package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.engine.smc.SessionPdArrayCalculator.PdArrayResult;
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
        PdArrayResult result = calculator.compute(
            new BigDecimal(high), new BigDecimal(low), new BigDecimal(price));
        assertNotNull(result);
        assertTrue(result.isDefined(), "well-formed range must yield a defined zone");
        assertNull(result.reason(), "reason is only populated for UNDEFINED");
        assertEquals(expectedZone, result.zone());
    }

    // ── PR-12 · No silent null ─────────────────────────────────────────────

    @Test
    void flatRange_returnsUndefinedResult_notNull() {
        PdArrayResult result = calculator.compute(
            new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"));

        assertNotNull(result, "flat range must NOT silently return null");
        assertFalse(result.isDefined());
        assertEquals(SessionPdArrayCalculator.UNDEFINED_ZONE, result.zone());
        assertNotNull(result.reason(), "UNDEFINED result must explain why");
        assertTrue(result.reason().toLowerCase().contains("flat"),
            "reason should mention flat range: " + result.reason());
        // Inputs preserved on the sentinel for audit
        assertEquals(0, new BigDecimal("100").compareTo(result.rangeHigh()));
        assertEquals(0, new BigDecimal("100").compareTo(result.rangeLow()));
        // Derived fields are null when zone is UNDEFINED
        assertNull(result.equilibrium());
        assertNull(result.premiumStart());
        assertNull(result.discountEnd());
    }

    @Test
    void invertedRange_returnsUndefinedResult_notNull() {
        PdArrayResult result = calculator.compute(
            new BigDecimal("90"), new BigDecimal("100"), new BigDecimal("95"));

        assertNotNull(result, "inverted range must NOT silently return null");
        assertFalse(result.isDefined());
        assertEquals(SessionPdArrayCalculator.UNDEFINED_ZONE, result.zone());
        assertTrue(result.reason().toLowerCase().contains("inverted"),
            "reason should mention inverted range: " + result.reason());
    }

    @Test
    void nullRangeHigh_throws_insteadOfSilentNull() {
        NullPointerException npe = assertThrows(NullPointerException.class,
            () -> calculator.compute(null, new BigDecimal("90"), new BigDecimal("95")));
        assertTrue(npe.getMessage() != null && npe.getMessage().contains("rangeHigh"),
            "NPE message must name the offending argument for fast debugging: " + npe.getMessage());
    }

    @Test
    void nullRangeLow_throws_insteadOfSilentNull() {
        NullPointerException npe = assertThrows(NullPointerException.class,
            () -> calculator.compute(new BigDecimal("100"), null, new BigDecimal("95")));
        assertTrue(npe.getMessage() != null && npe.getMessage().contains("rangeLow"),
            "NPE message must name the offending argument: " + npe.getMessage());
    }

    @Test
    void nullCurrentPrice_throws_insteadOfSilentNull() {
        NullPointerException npe = assertThrows(NullPointerException.class,
            () -> calculator.compute(new BigDecimal("100"), new BigDecimal("90"), null));
        assertTrue(npe.getMessage() != null && npe.getMessage().contains("currentPrice"),
            "NPE message must name the offending argument: " + npe.getMessage());
    }

    // ── Band arithmetic unchanged ─────────────────────────────────────────

    @Test
    void equilibriumBoundary() {
        // With 5% band: equilibrium band is 94.5 to 95.5 on a 90-100 range
        PdArrayResult result = calculator.compute(
            new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("95.50"));
        assertNotNull(result);
        assertEquals("EQUILIBRIUM", result.zone());

        // Just above equilibrium band = PREMIUM
        PdArrayResult premium = calculator.compute(
            new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("95.51"));
        assertNotNull(premium);
        assertEquals("PREMIUM", premium.zone());
    }

    @Test
    void resultFieldsPopulated() {
        PdArrayResult result = calculator.compute(
            new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("95.00"));
        assertNotNull(result);
        assertEquals(0, new BigDecimal("100.00").compareTo(result.rangeHigh()));
        assertEquals(0, new BigDecimal("90.00").compareTo(result.rangeLow()));
        assertEquals(0, new BigDecimal("95.00").compareTo(result.equilibrium().setScale(2)));
        assertNotNull(result.premiumStart());
        assertNotNull(result.discountEnd());
        assertNull(result.reason());
    }

    @Test
    void invalidBandThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new SessionPdArrayCalculator(new BigDecimal("-0.1")));
        assertThrows(IllegalArgumentException.class,
            () -> new SessionPdArrayCalculator(new BigDecimal("0.6")));
    }

    @Test
    void isDefined_reportsCorrectly() {
        PdArrayResult defined = calculator.compute(
            new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("95"));
        PdArrayResult undefined = calculator.compute(
            new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"));

        assertTrue(defined.isDefined());
        assertFalse(undefined.isDefined());
    }
}

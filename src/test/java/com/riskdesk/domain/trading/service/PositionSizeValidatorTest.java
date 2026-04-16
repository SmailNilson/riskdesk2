package com.riskdesk.domain.trading.service;

import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionSizeValidatorTest {

    private static final double MAX_RISK_USD = 500.0;
    private static final int MAX_QTY = 20;

    private final PositionSizeValidator validator = new PositionSizeValidator(MAX_RISK_USD, MAX_QTY);

    // ── computeRiskUsd (tick-value math) ─────────────────────────────────────

    @Test
    void mnq_1_contract_10_point_stop_equals_20usd() {
        // MNQ tick=0.25, tickValue=$0.50. 10 pts = 40 ticks × $0.50 = $20
        BigDecimal risk = PositionSizeValidator.computeRiskUsd(
            Instrument.MNQ, 1, new BigDecimal("18000.00"), new BigDecimal("17990.00"));
        assertThat(risk).isEqualByComparingTo("20.00");
    }

    @Test
    void mgc_1_contract_5_dollar_stop_equals_50usd() {
        // MGC tick=0.10, tickValue=$1.00. 5.00 move = 50 ticks × $1 = $50
        BigDecimal risk = PositionSizeValidator.computeRiskUsd(
            Instrument.MGC, 1, new BigDecimal("2350.00"), new BigDecimal("2345.00"));
        assertThat(risk).isEqualByComparingTo("50.00");
    }

    @Test
    void mcl_2_contracts_050_stop_equals_100usd() {
        // MCL tick=0.01, tickValue=$1.00. 0.50 move = 50 ticks × $1 × 2 qty = $100
        BigDecimal risk = PositionSizeValidator.computeRiskUsd(
            Instrument.MCL, 2, new BigDecimal("78.50"), new BigDecimal("78.00"));
        assertThat(risk).isEqualByComparingTo("100.00");
    }

    // ── Accept: within limits ────────────────────────────────────────────────

    @Test
    void accepts_mnq_10_contracts_10_points_equals_200usd() {
        // 10 qty × 10 pts × 40 ticks/pt would be wrong — real math: 10pts=40ticks × $0.50 × 10qty = $200
        validator.validate(Instrument.MNQ, 10,
            new BigDecimal("18000.00"), new BigDecimal("17990.00"));
        // No exception expected
    }

    @Test
    void accepts_mcl_5_contracts_080_stop_equals_400usd() {
        // MCL: 0.80 * 100 ticks × $1 × 5 qty = $400 (under $500 cap)
        validator.validate(Instrument.MCL, 5,
            new BigDecimal("78.50"), new BigDecimal("77.70"));
    }

    // ── Reject: exceeds $-risk cap ───────────────────────────────────────────

    @Test
    void rejects_mnq_20_contracts_20_points_equals_800usd_over_cap() {
        // 20 qty × 20 pts × 4 ticks/pt × $0.50 = $800 > $500 cap
        assertThatThrownBy(() -> validator.validate(Instrument.MNQ, 20,
                new BigDecimal("18000.00"), new BigDecimal("17980.00")))
            .isInstanceOf(PositionSizeExceededException.class)
            .hasMessageContaining("exceeds max-risk-per-trade");
    }

    @Test
    void rejects_mgc_10_contracts_1000_move_equals_1000usd_over_cap() {
        assertThatThrownBy(() -> validator.validate(Instrument.MGC, 10,
                new BigDecimal("2350.00"), new BigDecimal("2340.00")))
            .isInstanceOf(PositionSizeExceededException.class)
            .hasMessageContaining("exceeds max-risk-per-trade");
    }

    // ── Reject: exceeds quantity cap ─────────────────────────────────────────

    @Test
    void rejects_quantity_above_cap_even_with_tiny_stop() {
        // qty=25 > cap=20, triggers quantity rule BEFORE $-risk math
        assertThatThrownBy(() -> validator.validate(Instrument.MNQ, 25,
                new BigDecimal("18000.00"), new BigDecimal("17999.75")))
            .isInstanceOf(PositionSizeExceededException.class)
            .hasMessageContaining("exceeds max-quantity-per-order");
    }

    @Test
    void rejects_zero_or_negative_quantity() {
        assertThatThrownBy(() -> validator.validate(Instrument.MNQ, 0,
                new BigDecimal("18000.00"), new BigDecimal("17990.00")))
            .isInstanceOf(PositionSizeExceededException.class);
    }

    // ── Reject: undefined risk (entry == sl) ─────────────────────────────────

    @Test
    void rejects_entry_equals_stop_loss() {
        assertThatThrownBy(() -> validator.validate(Instrument.MNQ, 1,
                new BigDecimal("18000.00"), new BigDecimal("18000.00")))
            .isInstanceOf(PositionSizeExceededException.class)
            .hasMessageContaining("undefined risk");
    }

    // ── Constructor validation ────────────────────────────────────────────────

    @Test
    void rejects_non_positive_max_risk_in_constructor() {
        assertThatThrownBy(() -> new PositionSizeValidator(0.0, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PositionSizeValidator(-1.0, 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_zero_max_quantity_in_constructor() {
        assertThatThrownBy(() -> new PositionSizeValidator(500.0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Exception carries structured diagnostic data ─────────────────────────

    @Test
    void exception_exposes_computed_risk_and_caps() {
        try {
            validator.validate(Instrument.MNQ, 20,
                new BigDecimal("18000.00"), new BigDecimal("17980.00"));
        } catch (PositionSizeExceededException e) {
            assertThat(e.quantity()).isEqualTo(20);
            assertThat(e.riskUsd()).isEqualByComparingTo("800.00");
            assertThat(e.maxRiskUsd()).isEqualByComparingTo("500.00");
            assertThat(e.maxQuantity()).isEqualTo(20);
            return;
        }
        throw new AssertionError("expected PositionSizeExceededException");
    }
}

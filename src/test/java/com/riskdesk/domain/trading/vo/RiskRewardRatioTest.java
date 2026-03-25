package com.riskdesk.domain.trading.vo;

import com.riskdesk.domain.shared.vo.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class RiskRewardRatioTest {

    @Test
    void calculate_validRiskAndReward_returnsRatio() {
        Money risk = Money.of("100.00");
        Money reward = Money.of("300.00");
        RiskRewardRatio ratio = RiskRewardRatio.calculate(risk, reward);
        assertNotNull(ratio);
        assertEquals(0, new BigDecimal("3.00").compareTo(ratio.value()));
    }

    @Test
    void calculate_equalRiskAndReward_returnsOne() {
        Money risk = Money.of("50.00");
        Money reward = Money.of("50.00");
        RiskRewardRatio ratio = RiskRewardRatio.calculate(risk, reward);
        assertNotNull(ratio);
        assertEquals(0, new BigDecimal("1.00").compareTo(ratio.value()));
    }

    @Test
    void calculate_rewardLessThanRisk_returnsLessThanOne() {
        Money risk = Money.of("200.00");
        Money reward = Money.of("100.00");
        RiskRewardRatio ratio = RiskRewardRatio.calculate(risk, reward);
        assertNotNull(ratio);
        assertEquals(0, new BigDecimal("0.50").compareTo(ratio.value()));
    }

    @Test
    void calculate_nullRisk_returnsNull() {
        assertNull(RiskRewardRatio.calculate(null, Money.of("100.00")));
    }

    @Test
    void calculate_nullReward_returnsNull() {
        assertNull(RiskRewardRatio.calculate(Money.of("100.00"), null));
    }

    @Test
    void calculate_zeroRisk_returnsNull() {
        assertNull(RiskRewardRatio.calculate(Money.ZERO, Money.of("100.00")));
    }

    @Test
    void calculate_zeroReward_returnsNull() {
        assertNull(RiskRewardRatio.calculate(Money.of("100.00"), Money.ZERO));
    }

    @Test
    void value_hasScaleTwo() {
        Money risk = Money.of("100.00");
        Money reward = Money.of("333.00");
        RiskRewardRatio ratio = RiskRewardRatio.calculate(risk, reward);
        assertNotNull(ratio);
        assertEquals(2, ratio.value().scale());
    }
}

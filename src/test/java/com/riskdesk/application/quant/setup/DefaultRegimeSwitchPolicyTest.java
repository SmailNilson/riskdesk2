package com.riskdesk.application.quant.setup;

import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.quant.setup.SetupStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRegimeSwitchPolicyTest {

    private final DefaultRegimeSwitchPolicy policy = new DefaultRegimeSwitchPolicy();

    @Test
    @DisplayName("RANGING + tight bands + small day-move → SCALP")
    void ranging_tight_quiet_scalp() {
        SetupStyle style = policy.determineStyle(MarketRegime.RANGING, 0.30, 0.20);
        assertThat(style).isEqualTo(SetupStyle.SCALP);
    }

    @Test
    @DisplayName("RANGING + wide bands → DAY (too volatile for scalp)")
    void ranging_wideBands_day() {
        SetupStyle style = policy.determineStyle(MarketRegime.RANGING, 0.80, 0.20);
        assertThat(style).isEqualTo(SetupStyle.DAY);
    }

    @Test
    @DisplayName("RANGING + big day-move → DAY (session already trending)")
    void ranging_bigMove_day() {
        SetupStyle style = policy.determineStyle(MarketRegime.RANGING, 0.30, 0.50);
        assertThat(style).isEqualTo(SetupStyle.DAY);
    }

    @Test
    @DisplayName("TRENDING regime always → DAY")
    void trending_day() {
        SetupStyle style = policy.determineStyle(MarketRegime.TRENDING, 0.10, 0.05);
        assertThat(style).isEqualTo(SetupStyle.DAY);
    }

    @Test
    @DisplayName("CHOPPY regime → DAY")
    void choppy_day() {
        SetupStyle style = policy.determineStyle(MarketRegime.CHOPPY, 0.10, 0.05);
        assertThat(style).isEqualTo(SetupStyle.DAY);
    }

    @Test
    @DisplayName("UNKNOWN regime → DAY (safe fallback)")
    void unknown_day() {
        SetupStyle style = policy.determineStyle(MarketRegime.UNKNOWN, 0.10, 0.05);
        assertThat(style).isEqualTo(SetupStyle.DAY);
    }

    @Test
    @DisplayName("RANGING + NaN signals → DAY (data unavailable)")
    void ranging_nan_day() {
        assertThat(policy.determineStyle(MarketRegime.RANGING, Double.NaN, 0.10))
            .isEqualTo(SetupStyle.DAY);
        assertThat(policy.determineStyle(MarketRegime.RANGING, 0.10, Double.NaN))
            .isEqualTo(SetupStyle.DAY);
    }
}

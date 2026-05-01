package com.riskdesk.application.quant.setup;

import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.quant.setup.SetupStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRegimeSwitchPolicyTest {

    private final DefaultRegimeSwitchPolicy policy = new DefaultRegimeSwitchPolicy();

    @Test
    @DisplayName("RANGING + low BB + low ATR → SCALP")
    void ranging_lowBb_lowAtr_scalp() {
        SetupStyle style = policy.determineStyle(MarketRegime.RANGING, 20.0, 20.0);
        assertThat(style).isEqualTo(SetupStyle.SCALP);
    }

    @Test
    @DisplayName("RANGING + high BB → DAY (too volatile for scalp)")
    void ranging_highBb_day() {
        SetupStyle style = policy.determineStyle(MarketRegime.RANGING, 60.0, 20.0);
        assertThat(style).isEqualTo(SetupStyle.DAY);
    }

    @Test
    @DisplayName("TRENDING regime always → DAY")
    void trending_day() {
        SetupStyle style = policy.determineStyle(MarketRegime.TRENDING, 10.0, 10.0);
        assertThat(style).isEqualTo(SetupStyle.DAY);
    }

    @Test
    @DisplayName("CHOPPY regime → DAY (not scalp — no clear structure)")
    void choppy_day() {
        SetupStyle style = policy.determineStyle(MarketRegime.CHOPPY, 10.0, 10.0);
        assertThat(style).isEqualTo(SetupStyle.DAY);
    }

    @Test
    @DisplayName("UNKNOWN regime → DAY (default safe fallback)")
    void unknown_day() {
        SetupStyle style = policy.determineStyle(MarketRegime.UNKNOWN, 20.0, 20.0);
        assertThat(style).isEqualTo(SetupStyle.DAY);
    }
}

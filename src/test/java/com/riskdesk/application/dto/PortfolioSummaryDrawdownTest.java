package com.riskdesk.application.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Regression tests for {@link PortfolioSummary#dailyDrawdownPct()}.
 *
 * <p>The legacy formula divided unrealized P&L by {@code totalExposure}. On the
 * IBKR path, exposure maps to the GrossPositionValue account tag, which excludes
 * futures — so a micro-contract account with a small dollar loss produced absurd
 * percentages (the AGENTS panel showed "DAILY DRAWDOWN 83.7% > 3%" on MNQ).
 * The drawdown must instead be today's net P&L over account equity.
 */
class PortfolioSummaryDrawdownTest {

    private static final BigDecimal EQUITY_25K = new BigDecimal("25000");

    private static PortfolioSummary summary(String unrealized, String todayRealized,
                                            String exposure, BigDecimal equity) {
        BigDecimal u = new BigDecimal(unrealized);
        BigDecimal r = new BigDecimal(todayRealized);
        return new PortfolioSummary(u, r, u.add(r), 1,
            new BigDecimal(exposure), BigDecimal.ZERO, equity, List.of());
    }

    @Test
    @DisplayName("Regression: small dollar loss vs near-zero exposure no longer explodes (was 83.7%)")
    void smallLossWithTinyExposure_yieldsRealisticPct() {
        // Old formula: |-167.40| / 200 * 100 = 83.7% → spurious NO MORE TRADES TODAY
        PortfolioSummary s = summary("-167.40", "0", "200.00", EQUITY_25K);

        assertThat(s.dailyDrawdownPct()).isCloseTo(0.6696, within(0.0001));
        assertThat(s.dailyDrawdownPct()).isLessThan(3.0);
    }

    @Test
    @DisplayName("Realized and unrealized day P&L combine in the numerator")
    void realizedAndUnrealizedCombine() {
        // -500 realized today + -400 unrealized = -900 on 25k equity = 3.6%
        PortfolioSummary s = summary("-400", "-500", "84000", EQUITY_25K);

        assertThat(s.dailyDrawdownPct()).isCloseTo(3.6, within(0.0001));
        assertThat(s.dailyDrawdownPct()).isGreaterThan(3.0);
    }

    @Test
    @DisplayName("Positive realized P&L offsets an unrealized loss")
    void positiveRealizedOffsetsUnrealizedLoss() {
        PortfolioSummary s = summary("-150", "+600", "42000", EQUITY_25K);

        assertThat(s.dailyDrawdownPct()).isZero();
    }

    @Test
    @DisplayName("Profitable day yields zero drawdown")
    void profitableDayIsZero() {
        PortfolioSummary s = summary("250", "100", "42000", EQUITY_25K);

        assertThat(s.dailyDrawdownPct()).isZero();
    }

    @Test
    @DisplayName("Unknown or zero equity yields zero, never a division blow-up")
    void missingEquityFailsOpen() {
        assertThat(summary("-167.40", "0", "200.00", null).dailyDrawdownPct()).isZero();
        assertThat(summary("-167.40", "0", "200.00", BigDecimal.ZERO).dailyDrawdownPct()).isZero();
        assertThat(summary("-167.40", "0", "200.00", new BigDecimal("-1")).dailyDrawdownPct()).isZero();
    }

    @Test
    @DisplayName("Null P&L components are treated as zero")
    void nullPnlComponentsAreZero() {
        PortfolioSummary s = new PortfolioSummary(null, null, null, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, EQUITY_25K, List.of());

        assertThat(s.dailyDrawdownPct()).isZero();
    }
}

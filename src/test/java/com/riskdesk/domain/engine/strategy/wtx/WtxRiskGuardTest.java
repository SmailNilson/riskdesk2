package com.riskdesk.domain.engine.strategy.wtx;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WtxRiskGuardTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    void baseline_ignoresMaxLossHit() {
        assertTrue(WtxRiskGuard.canTradeForProfile(WtxProfile.BASELINE, true, false));
        assertTrue(WtxRiskGuard.canTradeForProfile(WtxProfile.BASELINE, false, false));
    }

    @Test
    void baseline_stillBlocksOnForceCloseWindow() {
        assertFalse(WtxRiskGuard.canTradeForProfile(WtxProfile.BASELINE, false, true));
        assertFalse(WtxRiskGuard.canTradeForProfile(WtxProfile.BASELINE, true, true));
    }

    @Test
    void sessionAtr_blocksOnMaxLoss() {
        assertFalse(WtxRiskGuard.canTradeForProfile(WtxProfile.SESSION_ATR, true, false));
        assertTrue(WtxRiskGuard.canTradeForProfile(WtxProfile.SESSION_ATR, false, false));
    }

    @Test
    void htfAndStrict_blockOnMaxLoss() {
        assertFalse(WtxRiskGuard.canTradeForProfile(WtxProfile.HTF, true, false));
        assertFalse(WtxRiskGuard.canTradeForProfile(WtxProfile.STRICT, true, false));
    }

    @Test
    void nullProfile_falbacksToLegacyForceCloseOnly() {
        assertTrue(WtxRiskGuard.canTradeForProfile(null, true, false));
        assertFalse(WtxRiskGuard.canTradeForProfile(null, false, true));
    }

    @Test
    void forceCloseWindow_detectsBetween16_28and16_40NY() {
        WtxConfig cfg = WtxConfig.defaults();
        Instant insideWindow = ZonedDateTime.of(2026, 5, 13, 16, 35, 0, 0, NY).toInstant();
        Instant outsideWindow = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY).toInstant();
        assertTrue(WtxRiskGuard.isForceCloseWindow(insideWindow, cfg));
        assertFalse(WtxRiskGuard.isForceCloseWindow(outsideWindow, cfg));
    }

    @Test
    void maxLossHit_detectsBreach() {
        WtxStrategyState state = WtxStrategyState.initial("MCL", "10m", BigDecimal.valueOf(10_000));
        // Lose $500 — should trigger
        WtxStrategyState losing = state.withFlat(BigDecimal.valueOf(-500));
        assertTrue(WtxRiskGuard.isMaxLossHit(losing, BigDecimal.valueOf(500)));
        // Lose $300 — should not
        WtxStrategyState smallLoss = state.withFlat(BigDecimal.valueOf(-300));
        assertFalse(WtxRiskGuard.isMaxLossHit(smallLoss, BigDecimal.valueOf(500)));
    }

    @Test
    void newTradingDay_detectsNyMidnightChange() {
        Instant pre = ZonedDateTime.of(2026, 5, 13, 23, 30, 0, 0, NY).toInstant();
        Instant post = ZonedDateTime.of(2026, 5, 14, 0, 5, 0, 0, NY).toInstant();
        assertTrue(WtxRiskGuard.isNewTradingDay(pre, post));
    }

    @Test
    void newTradingDay_falseWithinSameDay() {
        Instant a = ZonedDateTime.of(2026, 5, 13, 10, 0, 0, 0, NY).toInstant();
        Instant b = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY).toInstant();
        assertFalse(WtxRiskGuard.isNewTradingDay(a, b));
    }
}

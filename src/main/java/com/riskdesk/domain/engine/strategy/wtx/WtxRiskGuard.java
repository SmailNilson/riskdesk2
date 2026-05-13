package com.riskdesk.domain.engine.strategy.wtx;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure domain functions for WTX risk guard checks.
 * No Spring dependencies — safe to use in unit tests without context.
 */
public final class WtxRiskGuard {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private WtxRiskGuard() {}

    /**
     * True when the strategy's daily P&L has breached the max loss threshold.
     * Mirrors Pine Script: dailyPnL <= -maxDailyLossUSD
     */
    public static boolean isMaxLossHit(WtxStrategyState state, BigDecimal maxDailyLossUsd) {
        if (state.maxLossHit()) return true;
        return state.dailyPnl().compareTo(maxDailyLossUsd.negate()) <= 0;
    }

    /**
     * True when the current candle close time falls inside the NY session close window.
     * Mirrors Pine Script: nyMinutesNow in [nyCloseLimit, nySessionEnd]
     */
    public static boolean isForceCloseWindow(Instant candleTs, WtxConfig config) {
        if (!config.forceCloseNy()) return false;
        ZonedDateTime ny = ZonedDateTime.ofInstant(candleTs, NY);
        int nowMin = ny.getHour() * 60 + ny.getMinute();
        return nowMin >= config.nyCloseLimit() && nowMin <= config.nySessionEndMinutes();
    }

    /**
     * True when the candle's NY calendar date differs from the previous candle's date.
     * Mirrors Pine Script: ta.change(time("D", "America/New_York")) — fires at midnight ET,
     * NOT at the CME 17:00 ET session boundary.
     */
    public static boolean isNewTradingDay(Instant prevTs, Instant currTs) {
        if (prevTs == null) return false;
        LocalDate prevDate = prevTs.atZone(NY).toLocalDate();
        LocalDate currDate = currTs.atZone(NY).toLocalDate();
        return !prevDate.equals(currDate);
    }

    /**
     * Legacy gate: only blocks on NY force-close window.
     * Preserved for BASELINE profile to keep existing behaviour bit-for-bit identical.
     */
    public static boolean canTrade(boolean maxLossHit, boolean forceCloseWindow) {
        return !forceCloseWindow;
    }

    /**
     * Profile-aware gate. SESSION_ATR / HTF / STRICT all block on daily max-loss.
     * BASELINE keeps the legacy behaviour (only force-close blocks).
     * Mirrors Pine Script: canTrade = !maxLossHit && !forceCloseWindow.
     */
    public static boolean canTradeForProfile(WtxProfile profile, boolean maxLossHit, boolean forceCloseWindow) {
        if (forceCloseWindow) return false;
        if (profile != null && profile.blocksOnMaxLoss() && maxLossHit) return false;
        return true;
    }
}

package com.riskdesk.domain.forwardtest.service;

import com.riskdesk.domain.forwardtest.model.*;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForwardTestEvaluatorTest {

    private static final Instrument MCL = Instrument.MCL;
    private static final Instrument MNQ = Instrument.MNQ;
    private static final ForwardTestConfig CONFIG = ForwardTestConfig.defaults();
    private static final ForwardTestEvaluator EVAL = new ForwardTestEvaluator(CONFIG);
    private static final Instant T0 = Instant.parse("2026-04-01T14:00:00Z");

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Candle candle(Instant time, String open, String high, String low, String close) {
        return new Candle(MCL, "1m", time, bd(open), bd(high), bd(low), bd(close), 100);
    }

    private static Candle candleMnq(Instant time, String open, String high, String low, String close) {
        return new Candle(MNQ, "1m", time, bd(open), bd(high), bd(low), bd(close), 500);
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private static Instant t(int minutesAfterT0) {
        return T0.plusSeconds(minutesAfterT0 * 60L);
    }

    private static ForwardTestPosition longPosition(String entry, String sl, String tp) {
        return longPosition(entry, null, sl, tp);
    }

    private static ForwardTestPosition longPosition(String entry, String deepEntry, String sl, String tp) {
        PositionLeg leg1 = PositionLeg.pending(bd(entry), 1);
        PositionLeg leg2 = deepEntry != null ? PositionLeg.pending(bd(deepEntry), 1) : null;
        return ForwardTestPosition.create(
                1L, 100L, MCL, Side.LONG, "1h",
                bd(entry), deepEntry != null ? bd(deepEntry) : null, bd(sl), bd(tp),
                leg1, leg2, T0, T0.plusSeconds(28800));
    }

    private static ForwardTestPosition shortPosition(String entry, String sl, String tp) {
        return shortPosition(entry, null, sl, tp);
    }

    private static ForwardTestPosition shortPosition(String entry, String deepEntry, String sl, String tp) {
        PositionLeg leg1 = PositionLeg.pending(bd(entry), 1);
        PositionLeg leg2 = deepEntry != null ? PositionLeg.pending(bd(deepEntry), 1) : null;
        return ForwardTestPosition.create(
                1L, 100L, MCL, Side.SHORT, "1h",
                bd(entry), deepEntry != null ? bd(deepEntry) : null, bd(sl), bd(tp),
                leg1, leg2, T0, T0.plusSeconds(28800));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LONG positions
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class LongSingleLeg {

        @Test
        void entryFilled_thenTpHit_isWin() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            List<Candle> candles = List.of(
                    candle(t(1), "62.20", "62.30", "61.95", "62.10"),  // dips to 61.95 → fills entry
                    candle(t(2), "62.10", "63.05", "62.05", "63.00")   // hits TP 63.00
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.WIN, result.status());
            assertNotNull(result.exitPrice());
            assertEquals(0, bd("63.00").compareTo(result.exitPrice()));
            assertNotNull(result.realizedPnl());
            assertTrue(result.realizedPnl().signum() > 0);
            assertNotNull(result.netPnl());
            assertTrue(result.netPnl().compareTo(result.realizedPnl()) < 0, "Net P&L < gross due to commissions");
        }

        @Test
        void entryFilled_thenSlHit_isLoss() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            List<Candle> candles = List.of(
                    candle(t(1), "62.20", "62.30", "61.95", "62.10"),  // fills entry
                    candle(t(2), "62.00", "62.10", "61.45", "61.50")   // hits SL 61.50
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.LOSS, result.status());
            assertTrue(result.netPnl().signum() < 0);
        }

        @Test
        void tpReachedWithoutEntry_isMissed() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            // Price rallies to TP without ever dipping to entry
            List<Candle> candles = List.of(
                    candle(t(1), "62.20", "63.10", "62.10", "63.00") // low=62.10 > entry=62.00, high >= TP
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.MISSED, result.status());
        }

        @Test
        void slAndTpSameCandle_pessimisticLoss() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            List<Candle> candles = List.of(
                    candle(t(1), "62.20", "62.20", "61.95", "62.10"),  // fills entry
                    candle(t(2), "62.00", "63.10", "61.40", "62.50")   // both SL and TP touched
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.LOSS, result.status(), "Pessimistic: SL wins when both touched");
        }

        @Test
        void entryAndSlSameCandle_isLoss() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            // Single candle: dips to entry AND SL
            List<Candle> candles = List.of(
                    candle(t(1), "62.20", "62.30", "61.40", "61.60")
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.LOSS, result.status());
        }

        @Test
        void entryAndTpSameCandle_isWin() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            // Single candle: dips to entry AND rallies to TP (no SL touch)
            List<Candle> candles = List.of(
                    candle(t(1), "62.20", "63.10", "61.95", "63.00")
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.WIN, result.status());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHORT positions
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ShortSingleLeg {

        @Test
        void entryFilled_thenTpHit_isWin() {
            ForwardTestPosition pos = shortPosition("62.00", "62.50", "61.00");
            List<Candle> candles = List.of(
                    candle(t(1), "61.80", "62.05", "61.70", "61.90"),  // high >= entry → fills
                    candle(t(2), "61.80", "61.85", "60.95", "61.00")   // low <= TP
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.WIN, result.status());
            assertTrue(result.realizedPnl().signum() > 0);
        }

        @Test
        void entryFilled_thenSlHit_isLoss() {
            ForwardTestPosition pos = shortPosition("62.00", "62.50", "61.00");
            List<Candle> candles = List.of(
                    candle(t(1), "61.80", "62.05", "61.70", "61.90"),  // fills
                    candle(t(2), "62.00", "62.55", "61.90", "62.40")   // hits SL
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.LOSS, result.status());
        }

        @Test
        void tpReachedWithoutEntry_isMissed() {
            ForwardTestPosition pos = shortPosition("62.00", "62.50", "61.00");
            List<Candle> candles = List.of(
                    candle(t(1), "61.50", "61.90", "60.95", "61.00") // high=61.90 < entry, low <= TP
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.MISSED, result.status());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Dual-leg (scaling-in) positions
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class DualLeg {

        @Test
        void bothLegsFilled_thenWin() {
            ForwardTestPosition pos = longPosition("62.00", "61.70", "61.50", "63.00");
            List<Candle> candles = List.of(
                    candle(t(1), "62.10", "62.20", "61.95", "62.05"),  // fills Leg 1 at 62.00
                    candle(t(2), "62.00", "62.10", "61.65", "61.80"),  // fills Leg 2 at 61.70
                    candle(t(3), "61.80", "63.05", "61.75", "63.00")   // TP hit
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.WIN, result.status());
            assertEquals(2, result.filledQuantity());
            assertNotNull(result.averageEntry());
            // Average entry should be between leg1 and leg2 fill prices
            assertTrue(result.averageEntry().compareTo(bd("61.70")) >= 0);
            assertTrue(result.averageEntry().compareTo(bd("62.02")) <= 0); // entry + slippage
        }

        @Test
        void leg1FilledOnly_thenWin() {
            ForwardTestPosition pos = longPosition("62.00", "61.70", "61.50", "63.00");
            List<Candle> candles = List.of(
                    candle(t(1), "62.10", "62.20", "61.95", "62.05"),  // fills Leg 1
                    candle(t(2), "62.10", "63.05", "62.00", "63.00")   // TP hit, Leg 2 never filled
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.WIN, result.status());
            assertEquals(1, result.filledQuantity(), "Only Leg 1 filled");
        }

        @Test
        void bothLegsFilledSameCandle() {
            ForwardTestPosition pos = longPosition("62.00", "61.70", "61.50", "63.00");
            List<Candle> candles = List.of(
                    candle(t(1), "62.10", "62.20", "61.65", "62.00"),  // dips enough for both legs
                    candle(t(2), "62.00", "63.10", "61.90", "63.00")   // TP
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.WIN, result.status());
            assertEquals(ForwardTestStatus.WIN, result.status());
            assertEquals(2, result.filledQuantity());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TTL Expiration
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class TtlExpiration {

        @Test
        void pendingPosition_expiresBeforeEntry() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            // Expires at T0 + 8h, candle at T0 + 9h
            Instant afterExpiry = T0.plusSeconds(32400); // 9h
            List<Candle> candles = List.of(
                    candle(afterExpiry, "62.20", "62.30", "62.10", "62.20")
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.EXPIRED, result.status());
        }

        @Test
        void activePosition_expiresWithPartialPnl() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            // First candle fills entry, second candle is after TTL
            Instant afterExpiry = T0.plusSeconds(32400);
            List<Candle> candles = List.of(
                    candle(t(1), "62.10", "62.20", "61.95", "62.10"),  // fills
                    candle(afterExpiry, "62.50", "62.60", "62.40", "62.50") // expired
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.EXPIRED, result.status());
            // Should have partial P&L calculated at open price of expiry candle
            assertNotNull(result.realizedPnl());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Gap Handling
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class GapHandling {

        @Test
        void longPosition_gapDownBelowSl_fillsAtOpenNotSl() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            List<Candle> candles = List.of(
                    candle(t(1), "62.10", "62.20", "61.95", "62.10"),  // fills entry
                    // Monday gap: opens at 61.00, well below SL of 61.50
                    candle(t(2), "61.00", "61.10", "60.90", "61.05")
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.LOSS, result.status());
            // Exit should be at gap open (61.00), not SL (61.50)
            assertEquals(0, bd("61.00").compareTo(result.exitPrice()),
                    "Gap fill at open price, not SL");
        }

        @Test
        void shortPosition_gapUpAboveSl_fillsAtOpenNotSl() {
            ForwardTestPosition pos = shortPosition("62.00", "62.50", "61.00");
            List<Candle> candles = List.of(
                    candle(t(1), "61.80", "62.05", "61.70", "61.90"),  // fills entry
                    // Gap up above SL
                    candle(t(2), "63.00", "63.10", "62.90", "63.05")
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.LOSS, result.status());
            assertEquals(0, bd("63.00").compareTo(result.exitPrice()),
                    "Gap fill at open price, not SL");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Slippage
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class Slippage {

        @Test
        void longEntry_slippageAddsToPrice() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            List<Candle> candles = List.of(
                    candle(t(1), "62.10", "62.20", "61.95", "62.10") // fills
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertTrue(result.leg1().isFilled());
            // MCL tick = 0.01, 1 tick slippage → fill at 62.01
            assertEquals(0, bd("62.01").compareTo(result.leg1().fillPrice()),
                    "LONG entry slippage: 62.00 + 1 tick = 62.01");
        }

        @Test
        void shortEntry_slippageSubtractsFromPrice() {
            ForwardTestPosition pos = shortPosition("62.00", "62.50", "61.00");
            List<Candle> candles = List.of(
                    candle(t(1), "61.90", "62.05", "61.80", "61.95") // fills
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertTrue(result.leg1().isFilled());
            // SHORT entry slippage: 62.00 - 0.01 = 61.99
            assertEquals(0, bd("61.99").compareTo(result.leg1().fillPrice()),
                    "SHORT entry slippage: 62.00 - 1 tick = 61.99");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Excursion Tracking
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ExcursionTracking {

        @Test
        void longPosition_tracksMaxAdverseAndFavorable() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            List<Candle> candles = List.of(
                    candle(t(1), "62.10", "62.20", "61.95", "62.10"),  // fills at ~62.01
                    candle(t(2), "62.10", "62.50", "61.80", "62.30"),  // adverse: 62.01-61.80=0.21
                    candle(t(3), "62.30", "62.80", "62.20", "62.70"),  // favorable: 62.80-62.01=0.79
                    candle(t(4), "62.70", "63.05", "62.60", "63.00")   // TP hit
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.WIN, result.status());
            assertTrue(result.maxDrawdownPoints().signum() > 0, "Should track adverse excursion");
            assertTrue(result.maxFavorablePoints().signum() > 0, "Should track favorable excursion");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Position Sizing
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class PositionSizing {

        @Test
        void computeQuantity_standardRisk() {
            // Account: $10,000, risk 1% = $100
            // MCL entry 62.00, SL 61.50 → distance = 0.50 = 50 ticks × $1.00/tick = $50/contract
            // $100 / $50 = 2 contracts
            int qty = EVAL.computeQuantity(bd("10000"), MCL, bd("62.00"), bd("61.50"));
            assertEquals(2, qty);
        }

        @Test
        void computeQuantity_minimumOneContract() {
            // Account: $100, risk 1% = $1
            // MCL 50 ticks × $1 = $50 risk/contract > $1 budget → floor to 0, but min is 1
            int qty = EVAL.computeQuantity(bd("100"), MCL, bd("62.00"), bd("61.50"));
            assertEquals(1, qty);
        }

        @Test
        void computeQuantity_mnqSmallStop() {
            // Account: $10,000, risk 1% = $100
            // MNQ entry 17500.00, SL 17490.00 → distance = 10.00 = 40 ticks (tick=0.25) × $0.50/tick = $20/contract
            // $100 / $20 = 5 contracts
            int qty = EVAL.computeQuantity(bd("10000"), MNQ, bd("17500.00"), bd("17490.00"));
            assertEquals(5, qty);
        }

        @Test
        void computeQuantity_zeroStopDistance_returnsOne() {
            int qty = EVAL.computeQuantity(bd("10000"), MCL, bd("62.00"), bd("62.00"));
            assertEquals(1, qty);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ForwardTestAccount
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class AccountTests {

        @Test
        void create_setsInitialValues() {
            ForwardTestAccount acc = ForwardTestAccount.create("Test", bd("10000"), T0);
            assertEquals(bd("10000"), acc.currentBalance());
            assertEquals(bd("10000"), acc.peakBalance());
            assertEquals(0, acc.totalTrades());
            assertEquals(bd("0"), acc.winRate());
        }

        @Test
        void applyWinningTrade_updatesBalance() {
            ForwardTestAccount acc = ForwardTestAccount.create("Test", bd("10000"), T0);
            acc = acc.applyTradeResult(bd("150.00"), true, t(1));

            assertEquals(0, bd("10150.00").compareTo(acc.currentBalance()));
            assertEquals(1, acc.totalTrades());
            assertEquals(1, acc.winningTrades());
            assertEquals(0, acc.losingTrades());
            assertEquals(0, bd("100.00").compareTo(acc.winRate()));
        }

        @Test
        void applyLosingTrade_updatesDrawdown() {
            ForwardTestAccount acc = ForwardTestAccount.create("Test", bd("10000"), T0);
            acc = acc.applyTradeResult(bd("-200.00"), false, t(1));

            assertEquals(0, bd("9800.00").compareTo(acc.currentBalance()));
            assertEquals(0, bd("200.00").compareTo(acc.maxDrawdown()));
            assertEquals(1, acc.losingTrades());
        }

        @Test
        void winThenLoss_drawdownFromPeak() {
            ForwardTestAccount acc = ForwardTestAccount.create("Test", bd("10000"), T0);
            acc = acc.applyTradeResult(bd("500.00"), true, t(1));   // balance = 10500
            acc = acc.applyTradeResult(bd("-300.00"), false, t(2));  // balance = 10200, DD = 300

            assertEquals(0, bd("10200.00").compareTo(acc.currentBalance()));
            assertEquals(0, bd("10500.00").compareTo(acc.peakBalance()));
            assertEquals(0, bd("300.00").compareTo(acc.maxDrawdown()));
            assertEquals(0, bd("50.00").compareTo(acc.winRate())); // 1W/1L = 50%
        }

        @Test
        void returnPct_computed() {
            ForwardTestAccount acc = ForwardTestAccount.create("Test", bd("10000"), T0);
            acc = acc.applyTradeResult(bd("1000.00"), true, t(1));
            assertEquals(0, bd("10.00").compareTo(acc.returnPct()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class EdgeCases {

        @Test
        void emptyCandles_noStateChange() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            ForwardTestPosition result = EVAL.evaluate(pos, List.of());
            assertEquals(ForwardTestStatus.PENDING_LEG1, result.status());
        }

        @Test
        void terminalPosition_notReprocessed() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00")
                    .withStatus(ForwardTestStatus.WIN);
            ForwardTestPosition result = EVAL.evaluate(pos, List.of(
                    candle(t(1), "60.00", "60.00", "50.00", "50.00") // would crash SL
            ));
            assertEquals(ForwardTestStatus.WIN, result.status(), "Terminal positions should not be re-evaluated");
        }

        @Test
        void noEntryTouched_staysPending() {
            ForwardTestPosition pos = longPosition("62.00", "61.50", "63.00");
            List<Candle> candles = List.of(
                    candle(t(1), "62.20", "62.50", "62.10", "62.30"),
                    candle(t(2), "62.30", "62.40", "62.15", "62.35")
            );

            ForwardTestPosition result = EVAL.evaluate(pos, candles);

            assertEquals(ForwardTestStatus.PENDING_LEG1, result.status());
        }
    }
}

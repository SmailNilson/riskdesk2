package com.riskdesk.domain.quant.backtest;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import com.riskdesk.domain.quant.simulation.QuantSimExitPolicy;
import com.riskdesk.domain.quant.simulation.QuantSimStopMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-domain tests of the exit-replay engine: pessimistic both-cross rule,
 * EOD flat at the ET boundary, recorded-AVOID policies and the HTF filter.
 */
class QuantExitReplayEngineTest {

    /** 2026-01-05 15:00 ET == 20:00 UTC (EST, winter). */
    private static final Instant ENTRY_WINTER = Instant.parse("2026-01-05T20:00:00Z");

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Candle candle(Instant ts, double o, double h, double l, double c) {
        return new Candle(Instrument.MNQ, "1m", ts,
            BigDecimal.valueOf(o), BigDecimal.valueOf(h), BigDecimal.valueOf(l), BigDecimal.valueOf(c), 100);
    }

    /** Flat 1m candles [from, from + minutes) at {@code px} with zero range. */
    private static List<Candle> flatSeries(Instant from, int minutes, double px) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < minutes; i++) {
            out.add(candle(from.plusSeconds(60L * i), px, px, px, px));
        }
        return out;
    }

    private static Quant7GatesSimulation recorded(long id,
                                                  Quant7GatesSimulation.Direction dir,
                                                  double entry,
                                                  Instant openedAt,
                                                  Quant7GatesSimulationStatus status,
                                                  Double exitPrice,
                                                  Instant closedAt) {
        return new Quant7GatesSimulation(
            id, Instrument.MNQ, dir, entry,
            entry - 25, entry + 40, entry + 80,
            openedAt, "test", "LIVE_PUSH",
            status, exitPrice, "LIVE_PUSH", closedAt, "test-exit",
            0.0, 0.0);
    }

    private static QuantExitReplayParams fixedParams(QuantSimExitPolicy policy, double sl, double tp) {
        return new QuantExitReplayParams(
            QuantSimStopMode.FIXED, sl, tp,
            14, 2.0, 3.0,
            policy, false, 20, 50,
            0.0, LocalTime.of(16, 55));
    }

    // ── SL / TP resolution ──────────────────────────────────────────────────

    @Test
    void bothCrossInOneCandleCountsAsLossAtSl() {
        // LONG entry 100, SL 90 / TP 110 — one wide candle [85, 115] crosses
        // both. The project-wide pessimistic rule must count this as SL.
        List<Candle> candles = new ArrayList<>(flatSeries(ENTRY_WINTER.minusSeconds(600), 10, 100));
        candles.add(candle(ENTRY_WINTER, 100, 115, 85, 100));

        Quant7GatesSimulation sim = recorded(1, Quant7GatesSimulation.Direction.LONG, 100,
            ENTRY_WINTER, Quant7GatesSimulationStatus.CLOSED_SL, 90.0, ENTRY_WINTER.plusSeconds(120));
        QuantExitReplayResult r = QuantExitReplayEngine.replay(
            List.of(sim), Map.of(Instrument.MNQ, candles),
            fixedParams(QuantSimExitPolicy.SLTP_ONLY, 10, 10));

        assertThat(r.overall().n()).isEqualTo(1);
        assertThat(r.trades().get(0).exitReason()).isEqualTo("SL");
        assertThat(r.trades().get(0).pnlPoints()).isEqualTo(-10.0);
    }

    @Test
    void tpOnlyTouchResolvesAsWin() {
        List<Candle> candles = new ArrayList<>(flatSeries(ENTRY_WINTER.minusSeconds(600), 10, 100));
        candles.add(candle(ENTRY_WINTER, 100, 100, 100, 100));
        candles.add(candle(ENTRY_WINTER.plusSeconds(60), 100, 112, 99, 111));

        Quant7GatesSimulation sim = recorded(1, Quant7GatesSimulation.Direction.LONG, 100,
            ENTRY_WINTER, Quant7GatesSimulationStatus.CLOSED_SL, null, null);
        QuantExitReplayResult r = QuantExitReplayEngine.replay(
            List.of(sim), Map.of(Instrument.MNQ, candles),
            fixedParams(QuantSimExitPolicy.SLTP_ONLY, 10, 10));

        assertThat(r.trades().get(0).exitReason()).isEqualTo("TP");
        assertThat(r.trades().get(0).pnlPoints()).isEqualTo(10.0);
        assertThat(r.overall().wins()).isEqualTo(1);
    }

    @Test
    void shortDirectionMirrorsSlAndTp() {
        // SHORT entry 100, SL 110 / TP 90 — price rallies through the SL.
        List<Candle> candles = new ArrayList<>(flatSeries(ENTRY_WINTER.minusSeconds(600), 10, 100));
        candles.add(candle(ENTRY_WINTER, 100, 100, 100, 100));
        candles.add(candle(ENTRY_WINTER.plusSeconds(60), 100, 112, 100, 111));

        Quant7GatesSimulation sim = recorded(1, Quant7GatesSimulation.Direction.SHORT, 100,
            ENTRY_WINTER, Quant7GatesSimulationStatus.CLOSED_SL, null, null);
        QuantExitReplayResult r = QuantExitReplayEngine.replay(
            List.of(sim), Map.of(Instrument.MNQ, candles),
            fixedParams(QuantSimExitPolicy.SLTP_ONLY, 10, 10));

        assertThat(r.trades().get(0).exitReason()).isEqualTo("SL");
        assertThat(r.trades().get(0).pnlPoints()).isEqualTo(-10.0);
    }

    // ── EOD flat ────────────────────────────────────────────────────────────

    @Test
    void unresolvedTradeFlattensAtEodCutoff() {
        // Entry 15:00 ET, price never moves — the replay must flatten at the
        // first candle at/after 16:55 ET (21:55 UTC in winter), not ride on.
        List<Candle> candles = flatSeries(ENTRY_WINTER.minusSeconds(600), 10 + 130, 100);

        Quant7GatesSimulation sim = recorded(1, Quant7GatesSimulation.Direction.LONG, 100,
            ENTRY_WINTER, Quant7GatesSimulationStatus.CLOSED_SL, null, null);
        QuantExitReplayResult r = QuantExitReplayEngine.replay(
            List.of(sim), Map.of(Instrument.MNQ, candles),
            fixedParams(QuantSimExitPolicy.SLTP_ONLY, 10, 10));

        assertThat(r.trades().get(0).exitReason()).isEqualTo("EOD");
        assertThat(r.trades().get(0).pnlPoints()).isEqualTo(0.0);
    }

    // ── recorded AVOID policies ─────────────────────────────────────────────

    @Test
    void avoidInProfitHonoursProfitableFlipOnly() {
        // Two LONG trades, both with a recorded AVOID flip 2 minutes in:
        // one at +5 (honoured → AVOID exit), one at -5 (ignored → rides to TP).
        Instant flipAt = ENTRY_WINTER.plusSeconds(120);
        List<Candle> candles = new ArrayList<>(flatSeries(ENTRY_WINTER.minusSeconds(600), 10, 100));
        candles.addAll(flatSeries(ENTRY_WINTER, 5, 100));
        candles.add(candle(ENTRY_WINTER.plusSeconds(300), 100, 112, 99, 111)); // TP touch later

        Quant7GatesSimulation profitable = recorded(1, Quant7GatesSimulation.Direction.LONG, 100,
            ENTRY_WINTER, Quant7GatesSimulationStatus.CLOSED_FLOW_AVOID, 105.0, flipAt);
        Quant7GatesSimulation losing = recorded(2, Quant7GatesSimulation.Direction.LONG, 100,
            ENTRY_WINTER, Quant7GatesSimulationStatus.CLOSED_FLOW_AVOID, 95.0, flipAt);

        QuantExitReplayResult r = QuantExitReplayEngine.replay(
            List.of(profitable, losing), Map.of(Instrument.MNQ, candles),
            fixedParams(QuantSimExitPolicy.FLOW_AVOID_IN_PROFIT, 10, 10));

        QuantExitReplayResult.ReplayedTrade first = r.trades().stream()
            .filter(t -> t.recordedId() == 1).findFirst().orElseThrow();
        QuantExitReplayResult.ReplayedTrade second = r.trades().stream()
            .filter(t -> t.recordedId() == 2).findFirst().orElseThrow();
        assertThat(first.exitReason()).isEqualTo("AVOID");
        assertThat(first.pnlPoints()).isEqualTo(5.0);
        assertThat(second.exitReason()).isEqualTo("TP");
        assertThat(second.pnlPoints()).isEqualTo(10.0);
    }

    @Test
    void sltpOnlyIgnoresRecordedAvoid() {
        Instant flipAt = ENTRY_WINTER.plusSeconds(120);
        List<Candle> candles = new ArrayList<>(flatSeries(ENTRY_WINTER.minusSeconds(600), 10, 100));
        candles.addAll(flatSeries(ENTRY_WINTER, 5, 100));
        candles.add(candle(ENTRY_WINTER.plusSeconds(300), 100, 112, 99, 111));

        Quant7GatesSimulation sim = recorded(1, Quant7GatesSimulation.Direction.LONG, 100,
            ENTRY_WINTER, Quant7GatesSimulationStatus.CLOSED_FLOW_AVOID, 105.0, flipAt);
        QuantExitReplayResult r = QuantExitReplayEngine.replay(
            List.of(sim), Map.of(Instrument.MNQ, candles),
            fixedParams(QuantSimExitPolicy.SLTP_ONLY, 10, 10));

        assertThat(r.trades().get(0).exitReason()).isEqualTo("TP");
    }

    // ── HTF filter ──────────────────────────────────────────────────────────

    @Test
    void htfFilterSkipsCounterTrendEntry() {
        // Hourly closes fall monotonically → fast EMA below slow EMA → a LONG
        // entry must be skipped (counted in skippedHtf), a SHORT one replayed.
        List<Candle> candles = new ArrayList<>();
        Instant start = ENTRY_WINTER.minusSeconds(3600L * 8);
        for (int h = 0; h < 8; h++) {
            double px = 200 - h * 10; // falling
            candles.addAll(flatSeries(start.plusSeconds(3600L * h), 60, px));
        }
        // Post-entry data so the SHORT can resolve.
        candles.addAll(flatSeries(ENTRY_WINTER, 5, 130));
        candles.add(candle(ENTRY_WINTER.plusSeconds(300), 130, 130, 118, 119)); // SHORT TP touch

        QuantExitReplayParams params = new QuantExitReplayParams(
            QuantSimStopMode.FIXED, 10, 10,
            14, 2.0, 3.0,
            QuantSimExitPolicy.SLTP_ONLY,
            true, 2, 3, // tiny EMA periods so 8 hourly buckets suffice
            0.0, LocalTime.of(16, 55));

        Quant7GatesSimulation longSim = recorded(1, Quant7GatesSimulation.Direction.LONG, 130,
            ENTRY_WINTER, Quant7GatesSimulationStatus.CLOSED_SL, null, null);
        Quant7GatesSimulation shortSim = recorded(2, Quant7GatesSimulation.Direction.SHORT, 130,
            ENTRY_WINTER, Quant7GatesSimulationStatus.CLOSED_SL, null, null);

        QuantExitReplayResult r = QuantExitReplayEngine.replay(
            List.of(longSim, shortSim), Map.of(Instrument.MNQ, candles), params);

        assertThat(r.skippedHtf()).isEqualTo(1);
        assertThat(r.trades()).hasSize(1);
        assertThat(r.trades().get(0).direction()).isEqualTo("SHORT");
        assertThat(r.trades().get(0).exitReason()).isEqualTo("TP");
    }

    // ── ATR sizing ──────────────────────────────────────────────────────────

    @Test
    void atrModeSizesRiskFromHistory() {
        // Warm-up with constant-range 5m buckets so the Wilder ATR is exactly
        // the per-bucket range, then assert riskPoints = slAtrMult × ATR.
        List<Candle> candles = new ArrayList<>();
        Instant start = ENTRY_WINTER.minusSeconds(300L * 20);
        for (int b = 0; b < 20; b++) {
            Instant bucket = start.plusSeconds(300L * b);
            // One 1m candle per 5m bucket with range 4 (high 102, low 98).
            candles.add(candle(bucket, 100, 102, 98, 100));
        }
        candles.addAll(flatSeries(ENTRY_WINTER, 3, 100));
        candles.add(candle(ENTRY_WINTER.plusSeconds(180), 100, 100, 80, 81)); // deep drop → SL

        QuantExitReplayParams params = new QuantExitReplayParams(
            QuantSimStopMode.ATR, 25, 40,
            14, 2.0, 3.0,
            QuantSimExitPolicy.SLTP_ONLY,
            false, 20, 50,
            0.0, LocalTime.of(16, 55));

        Quant7GatesSimulation sim = recorded(1, Quant7GatesSimulation.Direction.LONG, 100,
            ENTRY_WINTER, Quant7GatesSimulationStatus.CLOSED_SL, null, null);
        QuantExitReplayResult r = QuantExitReplayEngine.replay(
            List.of(sim), Map.of(Instrument.MNQ, candles), params);

        assertThat(r.trades()).hasSize(1);
        QuantExitReplayResult.ReplayedTrade t = r.trades().get(0);
        // ATR of constant-range buckets = 4 → risk = 2.0 × 4 = 8 points.
        assertThat(t.riskPoints()).isCloseTo(8.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(t.exitReason()).isEqualTo("SL");
        assertThat(t.pnlPoints()).isCloseTo(-8.0, org.assertj.core.data.Offset.offset(0.01));
    }
}

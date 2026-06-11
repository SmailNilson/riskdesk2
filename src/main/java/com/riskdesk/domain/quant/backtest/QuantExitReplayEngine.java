package com.riskdesk.domain.quant.backtest;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import com.riskdesk.domain.quant.simulation.QuantSimExitPolicy;
import com.riskdesk.domain.quant.simulation.QuantSimStopMode;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Replays the RECORDED Quant 7-Gates entry signals against historical 1m
 * candles under an alternative exit policy — the order-flow signal stream
 * (entries) is mixed with price/indicator-based trade management (ATR stops,
 * HTF EMA filter), which is exactly the calibration question the live harness
 * cannot answer about itself.
 *
 * <p>Conventions (mirror the project backtest rules):
 * <ul>
 *   <li><b>Pessimistic both-cross rule</b> — when a single 1m candle crosses
 *       both SL and TP, the trade counts as a LOSS at the SL.</li>
 *   <li><b>No lookahead</b> — ATR and EMA inputs only use buckets fully closed
 *       before the entry instant.</li>
 *   <li><b>EOD flat</b> — any open replay position is closed at the first
 *       candle close at/after the configured ET wall-clock time (DST-aware via
 *       {@code America/New_York} projection), matching the live force-close.</li>
 * </ul>
 *
 * <p>Pure domain logic: candles in, aggregates out. Candle loading and REST
 * exposure live in the application/presentation layers.
 */
public final class QuantExitReplayEngine {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /** Max staleness of the first candle relative to entry before we skip the trade. */
    private static final long MAX_ENTRY_GAP_SECONDS = 300;

    private QuantExitReplayEngine() {
    }

    /**
     * Replays {@code recorded} (any status; OPEN rows are skipped) against the
     * supplied per-instrument ascending 1m candle series.
     */
    public static QuantExitReplayResult replay(List<Quant7GatesSimulation> recorded,
                                               Map<Instrument, List<Candle>> oneMinuteByInstrument,
                                               QuantExitReplayParams params) {
        Map<Instrument, Series> series = new LinkedHashMap<>();
        for (Map.Entry<Instrument, List<Candle>> e : oneMinuteByInstrument.entrySet()) {
            series.put(e.getKey(), Series.of(e.getValue(), params));
        }

        List<QuantExitReplayResult.ReplayedTrade> trades = new ArrayList<>();
        int skippedHtf = 0;
        int skippedNoData = 0;

        for (Quant7GatesSimulation sim : recorded) {
            if (sim.isOpen()) continue;
            Series s = series.get(sim.instrument());
            if (s == null || s.times.length == 0) { skippedNoData++; continue; }

            long t0 = sim.openedAt().getEpochSecond();
            int start = s.firstCandleTouching(t0);
            if (start < 0) { skippedNoData++; continue; }

            boolean isLong = sim.direction() == Quant7GatesSimulation.Direction.LONG;

            if (params.htfFilter()) {
                Boolean fastAboveSlow = s.htfFastAboveSlow(t0);
                if (fastAboveSlow == null || fastAboveSlow != isLong) { skippedHtf++; continue; }
            }

            double slDist;
            double tpDist;
            if (params.stopMode() == QuantSimStopMode.ATR) {
                Double atr = s.atrAt(t0);
                if (atr == null || atr <= 0) { skippedNoData++; continue; }
                slDist = params.slAtrMult() * atr;
                tpDist = params.tpAtrMult() * atr;
            } else {
                slDist = params.fixedSlPts();
                tpDist = params.fixedTpPts();
            }

            double entry = sim.entryPrice();
            double sign = isLong ? 1.0 : -1.0;
            double sl = entry - sign * slDist;
            double tp = entry + sign * tpDist;

            // Recorded AVOID flip (only rows that actually closed on it carry one).
            Long avoidAt = null;
            Double avoidPx = null;
            if (params.exitPolicy() != QuantSimExitPolicy.SLTP_ONLY
                && sim.status() == Quant7GatesSimulationStatus.CLOSED_FLOW_AVOID
                && sim.closedAt() != null && sim.exitPrice() != null) {
                avoidAt = sim.closedAt().getEpochSecond();
                avoidPx = sim.exitPrice();
            }

            long eodCutoff = eodCutoffEpoch(sim.openedAt(), params.eodFlatEt());

            String reason = "DATA_END";
            double exitPts = 0.0;
            boolean resolved = false;
            for (int j = start; j < s.times.length; j++) {
                long ct = s.times[j];
                long candleEnd = ct + 60;
                if (candleEnd < t0) continue;

                if (avoidAt != null && candleEnd >= avoidAt) {
                    double signedPts = sign * (avoidPx - entry);
                    boolean honour = params.exitPolicy() == QuantSimExitPolicy.FLOW_AVOID
                        || (params.exitPolicy() == QuantSimExitPolicy.FLOW_AVOID_IN_PROFIT && signedPts > 0);
                    if (honour) {
                        reason = "AVOID";
                        exitPts = signedPts;
                        resolved = true;
                        break;
                    }
                    avoidAt = null; // ignored — ride on to SL/TP/EOD
                }

                boolean hitSl = isLong ? s.lows[j] <= sl : s.highs[j] >= sl;
                boolean hitTp = isLong ? s.highs[j] >= tp : s.lows[j] <= tp;
                if (hitSl) { // pessimistic: both-cross counts as SL
                    reason = "SL";
                    exitPts = sign * (sl - entry);
                    resolved = true;
                    break;
                }
                if (hitTp) {
                    reason = "TP";
                    exitPts = sign * (tp - entry);
                    resolved = true;
                    break;
                }
                if (ct >= eodCutoff) {
                    reason = "EOD";
                    exitPts = sign * (s.closes[j] - entry);
                    resolved = true;
                    break;
                }
            }
            if (!resolved) {
                exitPts = sign * (s.closes[s.times.length - 1] - entry);
            }

            double mult = sim.instrument().getContractMultiplier().doubleValue();
            trades.add(new QuantExitReplayResult.ReplayedTrade(
                sim.id(), sim.instrument().name(), sim.direction().name(),
                reason, exitPts, exitPts * mult, slDist));
        }

        return aggregate(trades, recorded, params, skippedHtf, skippedNoData);
    }

    /** First 16:55-ET (configurable) candle-close boundary at/after the entry. */
    private static long eodCutoffEpoch(Instant openedAt, LocalTime eodFlatEt) {
        ZonedDateTime entryEt = openedAt.atZone(ET);
        ZonedDateTime cut = entryEt.with(eodFlatEt);
        if (!entryEt.isBefore(cut)) {
            cut = cut.plusDays(1);
        }
        return cut.toEpochSecond();
    }

    private static QuantExitReplayResult aggregate(List<QuantExitReplayResult.ReplayedTrade> trades,
                                                   List<Quant7GatesSimulation> recorded,
                                                   QuantExitReplayParams params,
                                                   int skippedHtf,
                                                   int skippedNoData) {
        Map<Long, Instant> openedById = new LinkedHashMap<>();
        for (Quant7GatesSimulation sim : recorded) {
            openedById.put(sim.id(), sim.openedAt());
        }

        Map<String, List<QuantExitReplayResult.ReplayedTrade>> byInstrument = new TreeMap<>();
        Map<String, List<QuantExitReplayResult.ReplayedTrade>> byDirection = new TreeMap<>();
        Map<String, List<QuantExitReplayResult.ReplayedTrade>> byReason = new TreeMap<>();
        Map<String, List<QuantExitReplayResult.ReplayedTrade>> byDay = new TreeMap<>();
        for (QuantExitReplayResult.ReplayedTrade t : trades) {
            byInstrument.computeIfAbsent(t.instrument(), k -> new ArrayList<>()).add(t);
            byDirection.computeIfAbsent(t.direction(), k -> new ArrayList<>()).add(t);
            byReason.computeIfAbsent(t.exitReason(), k -> new ArrayList<>()).add(t);
            Instant openedAt = openedById.get(t.recordedId());
            if (openedAt != null) {
                byDay.computeIfAbsent(openedAt.atZone(ET).toLocalDate().toString(), k -> new ArrayList<>()).add(t);
            }
        }

        return new QuantExitReplayResult(
            bucket(trades, params),
            buckets(byInstrument, params),
            buckets(byDirection, params),
            buckets(byReason, params),
            buckets(byDay, params),
            skippedHtf,
            skippedNoData,
            trades);
    }

    private static Map<String, QuantExitReplayResult.Bucket> buckets(
            Map<String, List<QuantExitReplayResult.ReplayedTrade>> grouped, QuantExitReplayParams params) {
        Map<String, QuantExitReplayResult.Bucket> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<QuantExitReplayResult.ReplayedTrade>> e : grouped.entrySet()) {
            out.put(e.getKey(), bucket(e.getValue(), params));
        }
        return out;
    }

    private static QuantExitReplayResult.Bucket bucket(List<QuantExitReplayResult.ReplayedTrade> trades,
                                                       QuantExitReplayParams params) {
        int n = trades.size();
        int wins = 0;
        double gross = 0.0;
        double rSum = 0.0;
        int rCount = 0;
        for (QuantExitReplayResult.ReplayedTrade t : trades) {
            if (t.pnlPoints() > 0) wins++;
            gross += t.pnlUsd();
            if (t.riskPoints() > 0) {
                rSum += t.pnlPoints() / t.riskPoints();
                rCount++;
            }
        }
        Double wr = n == 0 ? null : wins * 100.0 / n;
        Double expectancy = rCount == 0 ? null : rSum / rCount;
        double net = gross - n * params.commissionPerTradeUsd();
        return new QuantExitReplayResult.Bucket(n, wins, wr, round2(gross), round2(net), expectancy);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Pre-indexed candle series: ascending 1m arrays plus 5m-resampled ATR and
     * hourly EMA fast/slow series keyed by bucket END epoch-second (so a
     * lookup at the entry instant only ever sees fully-closed buckets).
     */
    private static final class Series {
        final long[] times;
        final double[] highs;
        final double[] lows;
        final double[] closes;
        /** Parallel arrays: ATR value valid from bucketEnd[i] onwards. */
        final long[] atrValidFrom;
        final double[] atrValues;
        /** Parallel arrays: true = fast EMA above slow, valid from hourEnd[i] onwards. */
        final long[] htfValidFrom;
        final boolean[] htfFastAboveSlow;

        private Series(long[] times, double[] highs, double[] lows, double[] closes,
                       long[] atrValidFrom, double[] atrValues,
                       long[] htfValidFrom, boolean[] htfFastAboveSlow) {
            this.times = times;
            this.highs = highs;
            this.lows = lows;
            this.closes = closes;
            this.atrValidFrom = atrValidFrom;
            this.atrValues = atrValues;
            this.htfValidFrom = htfValidFrom;
            this.htfFastAboveSlow = htfFastAboveSlow;
        }

        static Series of(List<Candle> ascending1m, QuantExitReplayParams params) {
            int n = ascending1m.size();
            long[] times = new long[n];
            double[] highs = new double[n];
            double[] lows = new double[n];
            double[] closes = new double[n];
            for (int i = 0; i < n; i++) {
                Candle c = ascending1m.get(i);
                times[i] = c.getTimestamp().getEpochSecond();
                highs[i] = c.getHigh().doubleValue();
                lows[i] = c.getLow().doubleValue();
                closes[i] = c.getClose().doubleValue();
            }

            // 5m resample → Wilder ATR(period), keyed by bucket END.
            TreeMap<Long, double[]> fiveMin = new TreeMap<>(); // start → [high, low, close]
            for (int i = 0; i < n; i++) {
                long b = times[i] / 300 * 300;
                double[] agg = fiveMin.get(b);
                if (agg == null) {
                    fiveMin.put(b, new double[] { highs[i], lows[i], closes[i] });
                } else {
                    agg[0] = Math.max(agg[0], highs[i]);
                    agg[1] = Math.min(agg[1], lows[i]);
                    agg[2] = closes[i];
                }
            }
            int period = params.atrPeriod();
            List<Long> atrFrom = new ArrayList<>();
            List<Double> atrVal = new ArrayList<>();
            Double prevClose = null;
            Double atr = null;
            List<Double> seed = new ArrayList<>();
            for (Map.Entry<Long, double[]> e : fiveMin.entrySet()) {
                double[] b = e.getValue();
                double tr = prevClose == null
                    ? b[0] - b[1]
                    : Math.max(b[0] - b[1], Math.max(Math.abs(b[0] - prevClose), Math.abs(b[1] - prevClose)));
                prevClose = b[2];
                if (atr == null) {
                    seed.add(tr);
                    if (seed.size() == period) {
                        atr = seed.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    }
                } else {
                    atr = (atr * (period - 1) + tr) / period;
                }
                if (atr != null) {
                    atrFrom.add(e.getKey() + 300); // valid once the bucket has CLOSED
                    atrVal.add(atr);
                }
            }
            long[] atrValidFrom = atrFrom.stream().mapToLong(Long::longValue).toArray();
            double[] atrValues = atrVal.stream().mapToDouble(Double::doubleValue).toArray();

            // Hourly closes → EMA fast/slow alignment, keyed by hour END.
            TreeMap<Long, Double> hourly = new TreeMap<>(); // start → last close
            for (int i = 0; i < n; i++) {
                hourly.put(times[i] / 3600 * 3600, closes[i]);
            }
            int fast = params.htfEmaFast();
            int slow = params.htfEmaSlow();
            double kFast = 2.0 / (fast + 1);
            double kSlow = 2.0 / (slow + 1);
            List<Long> htfFrom = new ArrayList<>();
            List<Boolean> htfVal = new ArrayList<>();
            Double emaFast = null;
            Double emaSlow = null;
            int seen = 0;
            for (Map.Entry<Long, Double> e : hourly.entrySet()) {
                double px = e.getValue();
                emaFast = emaFast == null ? px : px * kFast + emaFast * (1 - kFast);
                emaSlow = emaSlow == null ? px : px * kSlow + emaSlow * (1 - kSlow);
                seen++;
                if (seen >= slow) { // warm-up before the signal is trusted
                    htfFrom.add(e.getKey() + 3600); // valid once the hour has CLOSED
                    htfVal.add(emaFast > emaSlow);
                }
            }
            long[] htfValidFrom = htfFrom.stream().mapToLong(Long::longValue).toArray();
            boolean[] htfAligned = new boolean[htfVal.size()];
            for (int i = 0; i < htfVal.size(); i++) htfAligned[i] = htfVal.get(i);

            return new Series(times, highs, lows, closes, atrValidFrom, atrValues, htfValidFrom, htfAligned);
        }

        /** Index of the first candle whose window touches {@code t0}, or -1 when too far away. */
        int firstCandleTouching(long t0) {
            int lo = 0;
            int hi = times.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (times[mid] + 60 < t0) lo = mid + 1;
                else hi = mid;
            }
            if (lo >= times.length) return -1;
            if (times[lo] > t0 + MAX_ENTRY_GAP_SECONDS) return -1; // gap around entry → skip
            return lo;
        }

        Double atrAt(long t) {
            int idx = lastIndexAtOrBefore(atrValidFrom, t);
            return idx < 0 ? null : atrValues[idx];
        }

        Boolean htfFastAboveSlow(long t) {
            int idx = lastIndexAtOrBefore(htfValidFrom, t);
            return idx < 0 ? null : htfFastAboveSlow[idx];
        }

        private static int lastIndexAtOrBefore(long[] sortedFrom, long t) {
            int lo = 0;
            int hi = sortedFrom.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (sortedFrom[mid] <= t) lo = mid + 1;
                else hi = mid;
            }
            return lo - 1;
        }
    }
}

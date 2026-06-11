package com.riskdesk.backtest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.service.AbsorptionCache;
import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.application.service.PlaybookService;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Offline replay of the PLAYBOOK MNQ 10m automation against stored candles.
 *
 * Phase A re-runs the real production pipeline (IndicatorService snapshot →
 * PlaybookService → PlaybookEvaluator) bar-by-bar over stored 10m candles and
 * records every decision the automation would have persisted (paper threshold 4,
 * setup + plan present) — identical inputs, identical domain code.
 *
 * Phase B simulates each decision's TradeSimulation lifecycle on 1m candles
 * (live uses 5m; 1m is strictly finer) with the exact TradeSimulationService
 * rules: 1h PENDING_ENTRY timeout, MISSED when TP trades before entry,
 * pessimistic same-candle SL+TP = LOSS, opposite-direction reversal. Trailing
 * is intentionally absent: with the 1h entry timeout a playbook fill can never
 * accumulate the 15 five-minute bars computeAtrAtActivation needs, so live
 * trailing never engages for PLAYBOOK sims.
 *
 * Data files are produced by querying /api/candles/MNQ/{tf}/range on prod and
 * are NOT part of the repo. Run with:
 *   mvn -q -Dtest=PlaybookBacktestHarnessTest test
 */
class PlaybookBacktestHarnessTest {

    private static final String DIR = System.getProperty("pb.data.dir", "/tmp");
    private static final String F_1M = DIR + "/mnq_1m.json";
    private static final String F_10M = DIR + "/mnq_10m.json";
    private static final String F_1H = DIR + "/mnq_1h.json";
    private static final String F_DECISIONS = DIR + "/pb_bt_decisions.json";
    private static final String F_RESULTS = DIR + "/pb_bt_results.json";

    private static final int WARMUP_BARS = 1_000;
    private static final long TEN_MIN = 600;
    private static final long ENTRY_TIMEOUT_S = 3_600;
    private static final double MULT = 2.0;          // MNQ $/point
    private static final double FRICTION = 2.0;      // $/resolved round trip (commission + 1 tick SL slip)

    private static final ObjectMapper M = new ObjectMapper();

    // ── Phase A output ────────────────────────────────────────────────────────

    public record DecisionRec(long candleTs, int score, boolean late, String dir, String setupType,
                              double entry, double sl, double tp1, double rr,
                              double zoneHigh, double zoneLow, double atr,
                              Double ema20h, Double ema50h) {
        long decisionTime() { return candleTs + TEN_MIN; }
    }

    // ── data loading ──────────────────────────────────────────────────────────

    private record Bar(long t, double o, double h, double l, double c, long v) {}

    private static List<Bar> loadBars(String path) throws Exception {
        List<Map<String, Object>> raw = M.readValue(new File(path), new TypeReference<>() {});
        List<Bar> bars = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            bars.add(new Bar(
                ((Number) m.get("time")).longValue(),
                ((Number) m.get("open")).doubleValue(),
                ((Number) m.get("high")).doubleValue(),
                ((Number) m.get("low")).doubleValue(),
                ((Number) m.get("close")).doubleValue(),
                ((Number) m.get("volume")).longValue()));
        }
        bars.sort(Comparator.comparingLong(Bar::t));
        return bars;
    }

    private static List<Candle> toCandles(List<Bar> bars, String tf) {
        List<Candle> out = new ArrayList<>(bars.size());
        for (Bar b : bars) {
            out.add(new Candle(Instrument.MNQ, tf, Instant.ofEpochSecond(b.t()),
                BigDecimal.valueOf(b.o()), BigDecimal.valueOf(b.h()),
                BigDecimal.valueOf(b.l()), BigDecimal.valueOf(b.c()), b.v()));
        }
        return out;
    }

    // ── fakes ─────────────────────────────────────────────────────────────────

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public T getObject() { throw new UnsupportedOperationException(); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }

    /** In-memory candle port with a movable "now": bars after the cutoff are invisible. */
    private static final class ReplayPort implements CandleRepositoryPort {
        private final List<Candle> c10m;
        private final List<Candle> c1h;
        private final long[] t1h;
        private final int cutoff10m;     // inclusive index of the last closed 10m bar
        private final long evalTime;     // close time of that bar

        ReplayPort(List<Candle> c10m, List<Candle> c1h, long[] t1h, int cutoff10m, long evalTime) {
            this.c10m = c10m;
            this.c1h = c1h;
            this.t1h = t1h;
            this.cutoff10m = cutoff10m;
            this.evalTime = evalTime;
        }

        @Override public List<Candle> findRecentCandles(Instrument instrument, String timeframe, int limit) {
            List<Candle> src;
            int last;
            if ("10m".equals(timeframe)) {
                src = c10m;
                last = cutoff10m;
            } else if ("1h".equals(timeframe)) {
                src = c1h;
                last = lastClosed1h();
            } else {
                return List.of();
            }
            if (last < 0) return List.of();
            int from = Math.max(0, last - limit + 1);
            List<Candle> slice = new ArrayList<>(src.subList(from, last + 1));
            Collections.reverse(slice); // port contract: newest first
            return slice;
        }

        private int lastClosed1h() {
            int lo = 0, hi = t1h.length - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (t1h[mid] + 3_600 <= evalTime) { ans = mid; lo = mid + 1; } else { hi = mid - 1; }
            }
            return ans;
        }

        @Override public List<Candle> findRecentCandlesByContractMonth(Instrument i, String tf, String cm, int limit) {
            return findRecentCandles(i, tf, limit);
        }
        @Override public List<Candle> findCandles(Instrument i, String tf, Instant from) { return List.of(); }
        @Override public Optional<Instant> findLatestTimestamp(Instrument i, String tf) { return Optional.empty(); }
        @Override public List<Candle> findCandlesBetween(Instrument i, String tf, Instant f, Instant t) { return List.of(); }
        @Override public List<Candle> findCandlesBetweenPaged(Instrument i, String tf, Instant f, Instant t, int l) { return List.of(); }
        @Override public Candle save(Candle candle) { return candle; }
        @Override public List<Candle> saveAll(List<Candle> candles) { return candles; }
        @Override public void deleteAll() { }
        @Override public void deleteByInstrumentAndTimeframe(Instrument i, String tf) { }
        @Override public int deleteRange(Instrument i, String tf, Instant f, Instant t) { return 0; }
        @Override public long count() { return 0; }
    }

    // ── Phase A: replay decisions ─────────────────────────────────────────────

    private List<DecisionRec> computeDecisions(List<Candle> c10m, List<Candle> c1h) throws Exception {
        long[] t1h = c1h.stream().mapToLong(c -> c.getTimestamp().getEpochSecond()).toArray();
        double[] closes1h = c1h.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
        double[] ema20 = ema(closes1h, 20);
        double[] ema50 = ema(closes1h, 50);

        int n = c10m.size();
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<DecisionRec>> futures = new ArrayList<>();

        for (int i = WARMUP_BARS; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> evaluateBar(c10m, c1h, t1h, ema20, ema50, idx)));
        }
        List<DecisionRec> decisions = new ArrayList<>();
        int done = 0;
        for (Future<DecisionRec> f : futures) {
            DecisionRec d = f.get();
            if (d != null) decisions.add(d);
            if (++done % 2_000 == 0) {
                System.out.printf("  phase A: %d/%d bars, %d decisions%n", done, futures.size(), decisions.size());
            }
        }
        pool.shutdown();
        decisions.sort(Comparator.comparingLong(DecisionRec::candleTs));
        return decisions;
    }

    private DecisionRec evaluateBar(List<Candle> c10m, List<Candle> c1h, long[] t1h,
                                    double[] ema20, double[] ema50, int idx) {
        long candleTs = c10m.get(idx).getTimestamp().getEpochSecond();
        long evalTime = candleTs + TEN_MIN;
        ReplayPort port = new ReplayPort(c10m, c1h, t1h, idx, evalTime);
        IndicatorService indicators = new IndicatorService(
            port, new ActiveContractRegistry(), emptyProvider(), emptyProvider(), new AbsorptionCache());
        PlaybookService playbook = new PlaybookService(indicators, port);

        IndicatorSnapshot snap = indicators.computeSnapshot(Instrument.MNQ, "10m");
        BigDecimal atr = playbook.computeAtr(Instrument.MNQ, "10m");
        PlaybookEvaluation eval = playbook.evaluateFromSnapshot(snap, atr);
        if (eval == null || eval.checklistScore() < 4 || eval.bestSetup() == null || eval.plan() == null) {
            return null;
        }
        int h = lastIndexLeq(t1h, evalTime - 3_600);
        Double e20 = (h >= 0 && !Double.isNaN(ema20[h])) ? ema20[h] : null;
        Double e50 = (h >= 0 && !Double.isNaN(ema50[h])) ? ema50[h] : null;
        return new DecisionRec(
            candleTs,
            eval.checklistScore(),
            eval.lateEntry(),
            eval.filters().tradeDirection().name(),
            eval.bestSetup().type().name(),
            eval.plan().entryPrice().doubleValue(),
            eval.plan().stopLoss().doubleValue(),
            eval.plan().takeProfit1().doubleValue(),
            eval.plan().rrRatio(),
            eval.bestSetup().zoneHigh() != null ? eval.bestSetup().zoneHigh().doubleValue() : 0,
            eval.bestSetup().zoneLow() != null ? eval.bestSetup().zoneLow().doubleValue() : 0,
            atr != null ? atr.doubleValue() : 0,
            e20, e50);
    }

    private static double[] ema(double[] v, int p) {
        double[] out = new double[v.length];
        java.util.Arrays.fill(out, Double.NaN);
        if (v.length < p) return out;
        double sum = 0;
        for (int i = 0; i < p; i++) sum += v[i];
        out[p - 1] = sum / p;
        double k = 2.0 / (p + 1);
        for (int i = p; i < v.length; i++) out[i] = v[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    private static int lastIndexLeq(long[] arr, long key) {
        int lo = 0, hi = arr.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] <= key) { ans = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        return ans;
    }

    // ── Phase B: 1m simulation ────────────────────────────────────────────────

    public record Config(String entryStyle, int minScore, double minRr, boolean htf,
                         boolean dedup, boolean skipLate, int maxHoldHours,
                         String exitStyle, boolean invert) {
        Config(String entryStyle, int minScore, double minRr, boolean htf,
               boolean dedup, boolean skipLate, int maxHoldHours) {
            this(entryStyle, minScore, minRr, htf, dedup, skipLate, maxHoldHours, "PLAN", false);
        }
        String label() {
            return String.format("entry=%s score>=%d rr>=%.1f htf=%s dedup=%s skipLate=%s hold<=%s exit=%s%s",
                entryStyle, minScore, minRr, htf ? "Y" : "n", dedup ? "Y" : "n", skipLate ? "Y" : "n",
                maxHoldHours <= 0 ? "inf" : maxHoldHours + "h", exitStyle, invert ? " INVERT" : "");
        }
    }

    private enum St { PENDING, ACTIVE, WIN, LOSS, MISSED, CANCELLED, REVERSED, TIMEOUT, OPEN }

    private static final class Sim {
        final DecisionRec d;
        final double entry;
        double sl, tp;             // re-anchored on the real fill for ATR brackets
        final double slDist, tpDist; // NaN for PLAN exits (structural levels stay fixed)
        final boolean isShort;     // traded direction (after optional inversion)
        final boolean trigShort;   // original signal side — governs the entry-touch condition
        long fillTime = -1;
        long resolveTime = -1;
        double fillPrice = Double.NaN;
        double exitPrice = Double.NaN;   // set for WIN/LOSS/TIMEOUT
        St status = St.PENDING;

        Sim(DecisionRec d, double entry, double sl, double tp, double slDist, double tpDist,
            boolean isShort, boolean trigShort) {
            this.d = d;
            this.entry = entry;
            this.sl = sl;
            this.tp = tp;
            this.slDist = slDist;
            this.tpDist = tpDist;
            this.isShort = isShort;
            this.trigShort = trigShort;
        }

        double pnl() {
            if (Double.isNaN(exitPrice) || Double.isNaN(fillPrice)) return 0;
            return (isShort ? fillPrice - exitPrice : exitPrice - fillPrice) * MULT;
        }
        boolean resolvedTrade() { return status == St.WIN || status == St.LOSS || status == St.TIMEOUT; }
        boolean openAt(long t) {
            if (status == St.PENDING || status == St.ACTIVE || status == St.OPEN) return true;
            return resolveTime > 0 && t < resolveTime;
        }
    }

    /** Exact port of TradeSimulationService.evaluateWithPlan on 1m bars + 1h entry timeout.
     *  maxHoldS > 0 adds a time-stop: position closes at the bar close once exceeded. */
    private void walk(Sim s, long[] t1, double[] o1, double[] h1, double[] l1, double[] c1, long maxHoldS) {
        long start = s.d.decisionTime();
        int i = firstIndexGeq(t1, start);
        boolean active = false;
        long deadline = start + ENTRY_TIMEOUT_S;
        for (; i < t1.length; i++) {
            double hi = h1[i], lo = l1[i];
            long t = t1[i];
            if (!active) {
                if (t > deadline) { s.status = St.CANCELLED; s.resolveTime = t; return; }
                // MISSED only applies to limit-style entries (original side == traded side);
                // inverted trades enter on a stop trigger, which has no "TP before entry" miss
                boolean missed = s.trigShort == s.isShort && (s.isShort
                    ? (lo <= s.tp && hi < s.entry)
                    : (hi >= s.tp && lo > s.entry));
                if (missed) { s.status = St.MISSED; s.resolveTime = t; return; }
                boolean touchesEntry = s.trigShort ? hi >= s.entry : lo <= s.entry;
                if (touchesEntry) {
                    active = true;
                    s.fillTime = t;
                    // Limit-style entries (trigger side == traded side) fill at the limit price —
                    // conservative. Stop-style entries (inverted) can never fill better than the
                    // market: when the bar opens beyond the trigger, fill at the open.
                    if (s.trigShort == s.isShort) {
                        s.fillPrice = s.entry;
                    } else {
                        s.fillPrice = s.isShort ? Math.min(s.entry, o1[i]) : Math.max(s.entry, o1[i]);
                    }
                    if (!Double.isNaN(s.slDist)) { // ATR brackets re-anchor on the real fill
                        s.sl = s.isShort ? s.fillPrice + s.slDist : s.fillPrice - s.slDist;
                        s.tp = s.isShort ? s.fillPrice - s.tpDist : s.fillPrice + s.tpDist;
                    }
                    boolean stop = s.isShort ? hi >= s.sl : lo <= s.sl;
                    boolean target = s.isShort ? lo <= s.tp : hi >= s.tp;
                    if (stop) { s.status = St.LOSS; s.resolveTime = t; s.exitPrice = s.sl; return; }
                    if (target) { s.status = St.WIN; s.resolveTime = t; s.exitPrice = s.tp; return; }
                }
                continue;
            }
            boolean stop = s.isShort ? hi >= s.sl : lo <= s.sl;
            boolean target = s.isShort ? lo <= s.tp : hi >= s.tp;
            if (stop) { s.status = St.LOSS; s.resolveTime = t; s.exitPrice = s.sl; return; }  // pessimistic
            if (target) { s.status = St.WIN; s.resolveTime = t; s.exitPrice = s.tp; return; }
            if (maxHoldS > 0 && t - s.fillTime >= maxHoldS) {
                s.status = St.TIMEOUT; s.resolveTime = t; s.exitPrice = c1[i]; return;
            }
        }
        s.status = active ? St.OPEN : St.CANCELLED;
    }

    private static int firstIndexGeq(long[] arr, long key) {
        int lo = 0, hi = arr.length - 1, ans = arr.length;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] >= key) { ans = mid; hi = mid - 1; } else { lo = mid + 1; }
        }
        return ans;
    }

    public record Result(String label, int decisions, int sims, int wins, int losses, int missed,
                         int cancelled, int reversed, int timeout, int open, double winRate,
                         double totalPnl, double netPnl, double profitFactor, double avgWin,
                         double avgLoss, double maxDrawdown, double topDayShare, int posMonths,
                         int nMonths, Map<String, Double> monthlyPnl) {}

    /** Applies decision gates + exit geometry; returns the ready-to-walk Sim or null when filtered out. */
    private Sim buildSim(DecisionRec d, Config cfg) {
        if (d.score() < cfg.minScore()) return null;
        if (cfg.skipLate() && d.late()) return null;
        double entry = d.entry();
        if ("EDGE".equals(cfg.entryStyle()) && !"LIQUIDITY_SWEEP".equals(d.setupType())) {
            entry = "SHORT".equals(d.dir()) ? d.zoneLow() : d.zoneHigh();
        }
        boolean trigShort = "SHORT".equals(d.dir());
        boolean isShort = trigShort ^ cfg.invert();
        double sl, tp;
        double slDist = Double.NaN, tpDist = Double.NaN;
        if ("ATR".equals(cfg.exitStyle())) {
            double atr = d.atr();
            if (atr <= 0) return null;
            slDist = 1.5 * atr;
            tpDist = 2.25 * atr;
            sl = isShort ? entry + slDist : entry - slDist;
            tp = isShort ? entry - tpDist : entry + tpDist;
        } else if (cfg.invert()) {
            sl = d.tp1();  // swap roles: the plan's TP level protects, its SL level pays
            tp = d.sl();
        } else {
            sl = d.sl();
            tp = d.tp1();
        }
        boolean geometryOk = isShort ? (sl > entry && tp < entry) : (sl < entry && tp > entry);
        if (!geometryOk) return null;
        double rr = Math.abs(tp - entry) / Math.abs(entry - sl);
        if (rr < cfg.minRr()) return null;
        if (cfg.htf() && d.ema20h() != null && d.ema50h() != null) {
            boolean aligned = isShort ? d.ema20h() < d.ema50h() : d.ema20h() > d.ema50h();
            if (!aligned) return null;
        }
        return new Sim(d, entry, sl, tp, slDist, tpDist, isShort, trigShort);
    }

    private boolean zoneCooldownBlocked(List<Sim> sims, DecisionRec d, double entry, long now) {
        boolean sameDirOpen = false;
        for (Sim prev : sims) {
            if (prev.d.dir().equals(d.dir()) && prev.openAt(now)) { sameDirOpen = true; break; }
        }
        if (sameDirOpen) return true;
        for (int j = sims.size() - 1; j >= 0; j--) {
            Sim prev = sims.get(j);
            if (now - prev.d.decisionTime() > 7_200) break;
            if (!prev.d.dir().equals(d.dir())) continue;
            double tol = 0.3 * Math.max(Math.max(d.atr(), prev.d.atr()), 1.0);
            if (Math.abs(entry - prev.entry) <= tol) return true;
        }
        return false;
    }

    /** Dashboard convention: every gated decision becomes an independent overlapping sim. */
    private Result simulate(List<DecisionRec> decisions, Config cfg,
                            long[] t1, double[] o1, double[] h1, double[] l1, double[] c1) {
        List<Sim> sims = new ArrayList<>();
        for (DecisionRec d : decisions) {
            Sim s = buildSim(d, cfg);
            if (s == null) continue;
            long now = d.decisionTime();
            if (cfg.dedup() && zoneCooldownBlocked(sims, d, s.entry, now)) continue;

            walk(s, t1, o1, h1, l1, c1, cfg.maxHoldHours() * 3_600L);

            // mirror TradeSimulationService.reverseConflictingTrades: a newer
            // opposite-direction sim closes older still-open ones (PENDING→CANCELLED, ACTIVE→REVERSED)
            for (Sim prev : sims) {
                if (prev.d.dir().equals(d.dir()) || !prev.openAt(now)) continue;
                boolean filledBefore = prev.fillTime > 0 && prev.fillTime <= now;
                prev.status = filledBefore ? St.REVERSED : St.CANCELLED;
                prev.resolveTime = now;
                prev.fillTime = filledBefore ? prev.fillTime : -1;
                prev.exitPrice = Double.NaN; // dashboard counts reversals as $0
            }
            sims.add(s);
        }
        return aggregate(cfg, sims);
    }

    /** Realistic account: strictly one position at a time, processed chronologically. */
    private Result simulatePortfolio(List<DecisionRec> decisions, Config cfg,
                                     long[] t1, double[] o1, double[] h1, double[] l1, double[] c1) {
        List<Sim> sims = new ArrayList<>();
        Sim last = null;
        for (DecisionRec d : decisions) {
            Sim s = buildSim(d, cfg);
            if (s == null) continue;
            long now = d.decisionTime();
            if (last != null && last.openAt(now)) continue;       // K=1: busy until resolved
            if (cfg.dedup() && zoneCooldownBlocked(sims, d, s.entry, now)) continue;

            walk(s, t1, o1, h1, l1, c1, cfg.maxHoldHours() * 3_600L);
            sims.add(s);
            last = s;
        }
        return aggregate(cfg, sims);
    }

    private Result aggregate(Config cfg, List<Sim> sims) {
        int w = 0, l = 0, miss = 0, canc = 0, rev = 0, tmo = 0, open = 0;
        double pnl = 0, grossW = 0, grossL = 0, equity = 0, peak = 0, maxDd = 0;
        int resolved = 0;
        Map<String, Double> daily = new java.util.TreeMap<>();
        Map<String, Double> monthly = new java.util.TreeMap<>();
        for (Sim s : sims) {
            switch (s.status) {
                case WIN -> w++;
                case LOSS -> l++;
                case MISSED -> miss++;
                case CANCELLED -> canc++;
                case REVERSED -> rev++;
                case TIMEOUT -> tmo++;
                default -> open++;
            }
            double p = s.pnl();
            pnl += p;
            if (p > 0) grossW += p;
            if (p < 0) grossL -= p;
            if (s.resolvedTrade()) {
                resolved++;
                String day = Instant.ofEpochSecond(s.resolveTime).toString().substring(0, 10);
                daily.merge(day, p, Double::sum);
                monthly.merge(day.substring(0, 7), p, Double::sum);
            }
            equity += p;
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
        }
        // wins for WR purposes: TIMEOUT counts on its pnl sign
        int wEff = w, lEff = l;
        for (Sim s : sims) {
            if (s.status == St.TIMEOUT) {
                if (s.pnl() > 0) wEff++; else lEff++;
            }
        }
        double wr = (wEff + lEff) == 0 ? 0 : (double) wEff / (wEff + lEff);
        double pf = grossL == 0 ? (grossW > 0 ? 999 : 0) : grossW / grossL;
        double topDay = daily.values().stream().mapToDouble(Math::abs).max().orElse(0);
        double topDayShare = Math.abs(pnl) < 1 ? 0 : topDay / Math.abs(pnl);
        int posMonths = (int) monthly.values().stream().filter(v -> v > 0).count();
        return new Result(cfg.label(), sims.size(), sims.size(), w, l, miss, canc, rev, tmo, open,
            wr, pnl, pnl - resolved * FRICTION, pf,
            w == 0 ? 0 : grossW / w, l == 0 ? 0 : -grossL / l, maxDd,
            topDayShare, posMonths, monthly.size(), monthly);
    }

    // ── entry point ───────────────────────────────────────────────────────────

    @Test
    void runBacktest() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            new File(F_1M).exists() && new File(F_10M).exists() && new File(F_1H).exists(),
            "candle data files not present (download from /api/candles/MNQ/{tf}/range) — skipping");
        List<Bar> b1m = loadBars(F_1M);
        List<Bar> b10m = loadBars(F_10M);
        List<Bar> b1h = loadBars(F_1H);
        System.out.printf("loaded: 1m=%d 10m=%d 1h=%d%n", b1m.size(), b10m.size(), b1h.size());

        List<DecisionRec> decisions;
        File cache = new File(F_DECISIONS);
        if (cache.exists()) {
            decisions = M.readValue(cache, new TypeReference<>() {});
            System.out.printf("phase A: loaded %d cached decisions from %s%n", decisions.size(), F_DECISIONS);
        } else {
            long t0 = System.currentTimeMillis();
            decisions = computeDecisions(toCandles(b10m, "10m"), toCandles(b1h, "1h"));
            System.out.printf("phase A: %d decisions in %.0fs%n", decisions.size(),
                (System.currentTimeMillis() - t0) / 1000.0);
            M.writerWithDefaultPrettyPrinter().writeValue(cache, decisions);
        }

        long[] t1 = b1m.stream().mapToLong(Bar::t).toArray();
        double[] o1 = b1m.stream().mapToDouble(Bar::o).toArray();
        double[] h1 = b1m.stream().mapToDouble(Bar::h).toArray();
        double[] l1 = b1m.stream().mapToDouble(Bar::l).toArray();
        double[] c1 = b1m.stream().mapToDouble(Bar::c).toArray();

        Config baseline = new Config("MID", 4, 0, false, false, false, 0);
        Result baseRaw = simulate(decisions, baseline, t1, o1, h1, l1, c1);
        Result basePf = simulatePortfolio(decisions, baseline, t1, o1, h1, l1, c1);
        System.out.println("\nBASELINE dashboard (overlapping sims): " + fmt(baseRaw));
        System.out.println("BASELINE portfolio (1 position max):    " + fmt(basePf));

        List<Result> results = new ArrayList<>();
        for (String entry : List.of("MID", "EDGE"))
            for (int score : List.of(4, 5, 6))
                for (double rr : List.of(0.0, 1.0, 1.5))
                    for (boolean htf : List.of(false, true))
                        for (boolean dedup : List.of(false, true))
                            for (boolean skipLate : List.of(false, true))
                                for (int hold : List.of(0, 6))
                                    for (String exit : List.of("PLAN", "ATR"))
                                        for (boolean invert : List.of(false, true)) {
                                            Config c = new Config(entry, score, rr, htf, dedup,
                                                skipLate, hold, exit, invert);
                                            results.add(simulatePortfolio(decisions, c, t1, o1, h1, l1, c1));
                                        }

        results.sort(Comparator.comparingDouble(Result::netPnl).reversed());
        System.out.println("\nPORTFOLIO MODE — TOP 25 by net PnL (min 25 resolved trades):");
        results.stream()
            .filter(r -> r.wins() + r.losses() + r.timeout() >= 25)
            .limit(25)
            .forEach(r -> System.out.println(fmt(r)));

        System.out.println("\nPORTFOLIO MODE — TOP 10 by win rate (min 25 resolved, net>0):");
        results.stream()
            .filter(r -> r.wins() + r.losses() + r.timeout() >= 25 && r.netPnl() > 0)
            .sorted(Comparator.comparingDouble(Result::winRate).reversed())
            .limit(10)
            .forEach(r -> System.out.println(fmt(r)));

        System.out.println("\nMonthly PnL of top-3 net configs:");
        results.stream()
            .filter(r -> r.wins() + r.losses() + r.timeout() >= 25)
            .limit(3)
            .forEach(r -> System.out.println("  " + r.label() + " → " + r.monthlyPnl()));

        M.writerWithDefaultPrettyPrinter().writeValue(new File(F_RESULTS), results);
        System.out.println("\nfull results: " + F_RESULTS);
    }

    private static String fmt(Result r) {
        return String.format(
            "%-72s sims=%4d W=%3d L=%3d M=%3d C=%3d R=%2d T=%3d open=%2d WR=%4.1f%% PnL=%9.0f$ net=%9.0f$ PF=%5.2f avgW=%6.1f avgL=%7.1f maxDD=%7.0f topDay=%3.0f%% posM=%d/%d",
            r.label(), r.sims(), r.wins(), r.losses(), r.missed(), r.cancelled(), r.reversed(), r.timeout(),
            r.open(), r.winRate() * 100, r.totalPnl(), r.netPnl(), r.profitFactor(), r.avgWin(), r.avgLoss(),
            r.maxDrawdown(), r.topDayShare() * 100, r.posMonths(), r.nMonths());
    }
}

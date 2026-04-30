package com.riskdesk.domain.quant.engine;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.DistEntry;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.MarketSnapshot;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.model.QuantState;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless, framework-free implementation of the seven SHORT-setup gates
 * (G0–G6) lifted verbatim from {@code mnq_monitor_v3.py}, plus the
 * symmetric LONG-setup gates (L0–L6) added in the LONG-symmetry slice.
 *
 * <p>Each {@link #evaluate} call returns a single {@link Outcome} containing
 * both the SHORT and LONG gate maps and the merged next state. The caller
 * persists the state once and publishes the snapshot once — both directions
 * are observed in a single pass to minimise contention with the rest of the
 * pipeline (per-instrument lock in {@code QuantGateService}).</p>
 */
public final class GateEvaluator {

    public static final ZoneId ET = ZoneId.of("America/New_York");

    /** Thresholds (kept as constants so they appear once, mirroring the Python source). */
    static final double G0_DAY_MOVE_LIMIT      = 75.0;
    static final int    G0_RECENT_BULL_LIMIT   = 3;
    static final int    G1_MIN_N8              = 8;
    static final double G1_DELTA_INCOHERENCE   = 500.0;
    static final int    G2_MIN_PERSISTENCE     = 2;
    static final int    G2_CONF_THRESHOLD      = 60;
    static final double G3_DELTA_THRESHOLD     = -100.0;
    static final double G4_BUY_PCT_LIMIT       = 48.0;
    static final int    G5_ABS_SCORE_THRESHOLD = 8;
    static final String LIVE_PUSH              = "LIVE_PUSH";

    // ── LONG mirrors ───────────────────────────────────────────────────────
    /** Symmetric LONG threshold for L3: Δ &gt; +100. */
    static final double L3_DELTA_THRESHOLD     = 100.0;
    /** Symmetric LONG threshold for L4: buy% &gt; 52. */
    static final double L4_BUY_PCT_LIMIT       = 52.0;

    /**
     * Aggregate result of a single evaluation tick. Carries both directions —
     * SHORT (the original 7-gate verdict surfaced as {@code snapshot}) and the
     * LONG mirror gates merged into the same map. The wrapped
     * {@link QuantSnapshot} therefore contains 14 gate entries (7 SHORT +
     * 7 LONG), and {@link QuantSnapshot#longScore()} reports the LONG count.
     */
    public record Outcome(QuantSnapshot snapshot, QuantState nextState) {}

    public Outcome evaluate(MarketSnapshot snap, QuantState state, Instrument instrument) {
        // 1) Session reset on new ET calendar day (matches Python midnight-ET rollover).
        LocalDate today = snap.now().atZone(ET).toLocalDate();
        QuantState working = state;
        if (working == null || working.sessionDate() == null || !today.equals(working.sessionDate())) {
            working = QuantState.reset(today);
        }

        // 2) Capture monitor start price the first time we see a positive quote.
        Double price = snap.currentPrice();
        if (working.monitorStartPx() == null && price != null && price > 0.0) {
            working = working.withMonitorStartPx(price);
        }
        double startPx = working.monitorStartPx() != null
            ? working.monitorStartPx()
            : (price != null ? price : 0.0);
        double dayMove = (price != null && price > 0.0) ? (price - startPx) : 0.0;

        // 3) Append fresh delta to history (capped at 3).
        if (snap.delta() != null) {
            working = working.appendDelta(roundToOneDecimal(snap.delta()));
        }

        // 4) Route the latest dist/accu signal into the appropriate history
        // list — but only if it is a NEW event. The application layer feeds
        // the most recent event from a 10-minute window, so a single event
        // would otherwise be appended on every 60 s scan and inflate the
        // dist_only_history to 3 copies of itself, falsely satisfying G2.
        if ("DISTRIBUTION".equals(snap.distType()) && snap.distConf() != null
                && isNewerThanLast(snap.distTimestamp(), working.distOnlyHistory())) {
            working = working.appendDistOnly(new DistEntry(DistEntry.DIST, snap.distConf(), snap.distTimestamp()));
        } else if ("ACCUMULATION".equals(snap.distType()) && snap.distConf() != null
                && isNewerThanLast(snap.distTimestamp(), working.accuOnlyHistory())) {
            working = working.appendAccuOnly(new DistEntry(DistEntry.ACCU, snap.distConf(), snap.distTimestamp()));
        }

        // 5) Track ABS BULL scans (n8 ≥ 8, BULL-dominant) and prune old ones.
        boolean absBullActive = "BULL".equals(snap.dominantSide()) && snap.absBull8Count() >= G1_MIN_N8;
        if (absBullActive) {
            working = working.appendAbsBullAndPrune(snap.now(), snap.now());
        } else {
            working = working.pruneAbsBullScans(snap.now());
        }

        // 5b) LONG mirror: track ABS BEAR scans for L0 regime filter.
        boolean absBearActive = "BEAR".equals(snap.dominantSide()) && snap.absBear8Count() >= G1_MIN_N8;
        if (absBearActive) {
            working = working.appendAbsBearAndPrune(snap.now(), snap.now());
        } else {
            working = working.pruneAbsBearScans(snap.now());
        }

        // 6) Evaluate SHORT gates.
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        gates.put(Gate.G0_REGIME, evaluateG0(dayMove, working.absBullScans30m().size()));
        gates.put(Gate.G1_ABS_BEAR, evaluateG1(snap));
        gates.put(Gate.G2_DIST_PUR, evaluateG2(working.distOnlyHistory()));
        gates.put(Gate.G3_DELTA_NEG, evaluateG3(snap.delta(), working.deltaHistory()));
        gates.put(Gate.G4_BUY_PCT_LOW, evaluateG4(snap.buyPct()));
        gates.put(Gate.G5_ACCU_THRESHOLD, evaluateG5(snap));
        gates.put(Gate.G6_LIVE_PUSH, evaluateG6(snap.priceSource()));

        int score = (int) gates.values().stream()
            .filter(r -> r != null && r.ok())
            .count();

        // 6b) Evaluate LONG mirror gates and merge into the same map.
        gates.put(Gate.L0_REGIME,        evaluateL0(dayMove, working.absBearScans30m().size()));
        gates.put(Gate.L1_ABS_BULL,      evaluateL1(snap));
        gates.put(Gate.L2_ACCU_PUR,      evaluateL2(working.accuOnlyHistory()));
        gates.put(Gate.L3_DELTA_POS,     evaluateL3(snap.delta(), working.deltaHistory()));
        gates.put(Gate.L4_BUY_PCT_HIGH,  evaluateL4(snap.buyPct()));
        gates.put(Gate.L5_DIST_THRESHOLD, evaluateL5(snap));
        gates.put(Gate.L6_LIVE_PUSH,     evaluateG6(snap.priceSource()));  // same source check

        int longScore = (int) java.util.stream.Stream.of(
                Gate.L0_REGIME, Gate.L1_ABS_BULL, Gate.L2_ACCU_PUR, Gate.L3_DELTA_POS,
                Gate.L4_BUY_PCT_HIGH, Gate.L5_DIST_THRESHOLD, Gate.L6_LIVE_PUSH)
            .map(gates::get)
            .filter(r -> r != null && r.ok())
            .count();

        QuantSnapshot snapshot = new QuantSnapshot(
            instrument,
            gates,
            score,
            longScore,
            price,
            snap.priceSource(),
            dayMove,
            snap.now().atZone(ET)
        );
        return new Outcome(snapshot, working);
    }

    // ── Individual gates ────────────────────────────────────────────────────

    static GateResult evaluateG0(double dayMove, int recentBullCount) {
        if (dayMove > G0_DAY_MOVE_LIMIT) {
            return GateResult.fail(String.format("HAUSSIER +%.0fpts depuis start", dayMove));
        }
        if (recentBullCount >= G0_RECENT_BULL_LIMIT) {
            return GateResult.fail(String.format("ABS BULL x%d en 30min — biais long", recentBullCount));
        }
        return GateResult.pass(String.format("Δjour=%+.0fpts  ABSbull/30m=%d", dayMove, recentBullCount));
    }

    static GateResult evaluateG1(MarketSnapshot snap) {
        int n8 = snap.absBull8Count() + snap.absBear8Count();
        if (n8 < G1_MIN_N8) {
            return GateResult.fail(String.format("n8=%d<%d", n8, G1_MIN_N8));
        }
        if (!"BEAR".equals(snap.dominantSide())) {
            return GateResult.fail(String.format("dom=%s≠BEAR", snap.dominantSide()));
        }
        if (snap.delta() != null && snap.delta() > G1_DELTA_INCOHERENCE) {
            return GateResult.fail(String.format("Δ=%.0f>500 incohérent", snap.delta()));
        }
        return GateResult.pass(String.format("n8=%d dom=%s maxSc=%.1f ✓",
            n8, snap.dominantSide(), snap.absMaxScore()));
    }

    static GateResult evaluateG2(List<DistEntry> distOnlyHistory) {
        long persistence = distOnlyHistory.stream()
            .filter(e -> e.conf() >= G2_CONF_THRESHOLD)
            .count();
        boolean ok = persistence >= G2_MIN_PERSISTENCE;
        String reason = String.format("%d/%d DIST_only≥%d%%  hist=%s",
            persistence, Math.max(distOnlyHistory.size(), G2_MIN_PERSISTENCE + 1), G2_CONF_THRESHOLD,
            formatDistHistory(distOnlyHistory, "DIST"));
        return new GateResult(ok, reason);
    }

    static GateResult evaluateG3(Double delta, List<Double> deltaHistory) {
        if (delta == null) {
            return GateResult.fail("Δ=None");
        }
        boolean ok = delta < G3_DELTA_THRESHOLD;
        String trendStr = formatDeltaTrend(deltaHistory);
        String trendBonus = strictlyDecreasingAndNegative(deltaHistory) ? " +TREND✅" : "";
        return new GateResult(ok, String.format("Δ=%.0f [%s]%s", delta, trendStr, trendBonus));
    }

    static GateResult evaluateG4(Double buyPct) {
        if (buyPct == null) {
            return GateResult.fail("buy%=None");
        }
        boolean ok = buyPct < G4_BUY_PCT_LIMIT;
        return new GateResult(ok, String.format("buy%%=%.1f%%", buyPct));
    }

    static GateResult evaluateG5(MarketSnapshot snap) {
        int threshold = accumThreshold(snap.delta(), snap.buyPct());
        boolean accuActive = "ACCUMULATION".equals(snap.distType());
        if (!accuActive) {
            return GateResult.pass("pas d'ACCU active ✓");
        }
        int conf = snap.distConf() != null ? snap.distConf() : 0;
        boolean blocks = conf >= threshold;
        String reason = String.format("ACCU %d%% vs seuil=%d%% → %s",
            conf, threshold, blocks ? "BLOQUE ❌" : "PASS ✅");
        return new GateResult(!blocks, reason);
    }

    static GateResult evaluateG6(String priceSource) {
        boolean ok = LIVE_PUSH.equals(priceSource);
        return new GateResult(ok, "source=" + (priceSource == null ? "" : priceSource));
    }

    // ── LONG gates ─────────────────────────────────────────────────────────

    /** L0 — daily regime filter, mirror of G0. Bearish day (or repeated ABS BEAR) suspends LONG. */
    static GateResult evaluateL0(double dayMove, int recentBearCount) {
        if (dayMove < -G0_DAY_MOVE_LIMIT) {
            return GateResult.fail(String.format("BAISSIER %+.0fpts depuis start", dayMove));
        }
        if (recentBearCount >= G0_RECENT_BULL_LIMIT) {
            return GateResult.fail(String.format("ABS BEAR x%d en 30min — biais short", recentBearCount));
        }
        return GateResult.pass(String.format("Δjour=%+.0fpts  ABSbear/30m=%d", dayMove, recentBearCount));
    }

    /** L1 — coherent bullish absorption, mirror of G1. */
    static GateResult evaluateL1(MarketSnapshot snap) {
        int n8 = snap.absBull8Count() + snap.absBear8Count();
        if (n8 < G1_MIN_N8) {
            return GateResult.fail(String.format("n8=%d<%d", n8, G1_MIN_N8));
        }
        if (!"BULL".equals(snap.dominantSide())) {
            return GateResult.fail(String.format("dom=%s≠BULL", snap.dominantSide()));
        }
        if (snap.delta() != null && snap.delta() < -G1_DELTA_INCOHERENCE) {
            return GateResult.fail(String.format("Δ=%.0f<-500 incohérent", snap.delta()));
        }
        return GateResult.pass(String.format("n8=%d dom=%s maxSc=%.1f ✓",
            n8, snap.dominantSide(), snap.absMaxScore()));
    }

    /** L2 — accumulation persistence, mirror of G2. */
    static GateResult evaluateL2(List<DistEntry> accuOnlyHistory) {
        long persistence = accuOnlyHistory.stream()
            .filter(e -> e.conf() >= G2_CONF_THRESHOLD)
            .count();
        boolean ok = persistence >= G2_MIN_PERSISTENCE;
        String reason = String.format("%d/%d ACCU_only≥%d%%  hist=%s",
            persistence, Math.max(accuOnlyHistory.size(), G2_MIN_PERSISTENCE + 1), G2_CONF_THRESHOLD,
            formatDistHistory(accuOnlyHistory, "ACCU"));
        return new GateResult(ok, reason);
    }

    /** L3 — Δ &gt; +100 with optional ascending-trend bonus. */
    static GateResult evaluateL3(Double delta, List<Double> deltaHistory) {
        if (delta == null) {
            return GateResult.fail("Δ=None");
        }
        boolean ok = delta > L3_DELTA_THRESHOLD;
        String trendStr = formatDeltaTrend(deltaHistory);
        String trendBonus = strictlyIncreasingAndPositive(deltaHistory) ? " +TREND✅" : "";
        return new GateResult(ok, String.format("Δ=%.0f [%s]%s", delta, trendStr, trendBonus));
    }

    /** L4 — buy% &gt; 52, mirror of G4. */
    static GateResult evaluateL4(Double buyPct) {
        if (buyPct == null) {
            return GateResult.fail("buy%=None");
        }
        boolean ok = buyPct > L4_BUY_PCT_LIMIT;
        return new GateResult(ok, String.format("buy%%=%.1f%%", buyPct));
    }

    /** L5 — conditional DIST threshold, mirror of G5. */
    static GateResult evaluateL5(MarketSnapshot snap) {
        int threshold = distThreshold(snap.delta(), snap.buyPct());
        boolean distActive = "DISTRIBUTION".equals(snap.distType());
        if (!distActive) {
            return GateResult.pass("pas de DIST active ✓");
        }
        int conf = snap.distConf() != null ? snap.distConf() : 0;
        boolean blocks = conf >= threshold;
        String reason = String.format("DIST %d%% vs seuil=%d%% → %s",
            conf, threshold, blocks ? "BLOQUE ❌" : "PASS ✅");
        return new GateResult(!blocks, reason);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    static int accumThreshold(Double delta, Double buyPct) {
        if (delta != null && delta < -500.0 && buyPct != null && buyPct < 45.0) {
            return 75;
        }
        if (delta != null && delta < -200.0 && buyPct != null && buyPct < 47.0) {
            return 65;
        }
        return 50;
    }

    /** LONG mirror of {@link #accumThreshold}. */
    static int distThreshold(Double delta, Double buyPct) {
        if (delta != null && delta > 500.0 && buyPct != null && buyPct > 55.0) {
            return 75;
        }
        if (delta != null && delta > 200.0 && buyPct != null && buyPct > 53.0) {
            return 65;
        }
        return 50;
    }

    static boolean strictlyDecreasingAndNegative(List<Double> hist) {
        if (hist == null || hist.size() < 2) return false;
        for (int i = 0; i < hist.size() - 1; i++) {
            if (hist.get(i) <= hist.get(i + 1)) return false;
        }
        return hist.get(hist.size() - 1) < G3_DELTA_THRESHOLD;
    }

    /** LONG mirror of {@link #strictlyDecreasingAndNegative}. */
    static boolean strictlyIncreasingAndPositive(List<Double> hist) {
        if (hist == null || hist.size() < 2) return false;
        for (int i = 0; i < hist.size() - 1; i++) {
            if (hist.get(i) >= hist.get(i + 1)) return false;
        }
        return hist.get(hist.size() - 1) > L3_DELTA_THRESHOLD;
    }

    static double roundToOneDecimal(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Returns {@code true} when {@code candidateTs} represents an event the
     * caller has not yet appended to {@code history}. Compares against the
     * most recent entry's timestamp — DIST/ACCU events are always appended in
     * order so checking the tail is sufficient.
     *
     * <p>If {@code candidateTs} is {@code null} we cannot tell whether the
     * event is new, so we conservatively skip the append. Without a
     * deduplication key, the alternative (always appending) would re-introduce
     * the very bug this guard exists to prevent.</p>
     */
    static boolean isNewerThanLast(java.time.Instant candidateTs, List<DistEntry> history) {
        if (candidateTs == null) return false;
        if (history == null || history.isEmpty()) return true;
        java.time.Instant lastTs = history.get(history.size() - 1).ts();
        if (lastTs == null) return true;
        return !candidateTs.equals(lastTs);
    }

    static String formatDeltaTrend(List<Double> hist) {
        if (hist == null || hist.isEmpty()) return "N/A";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hist.size(); i++) {
            if (i > 0) sb.append("→");
            sb.append((int) Math.round(hist.get(i)));
        }
        return sb.toString();
    }

    static String formatDistHistory(List<DistEntry> hist, String label) {
        if (hist == null || hist.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < hist.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(label).append((int) Math.round(hist.get(i).conf()));
        }
        return sb.append("]").toString();
    }
}

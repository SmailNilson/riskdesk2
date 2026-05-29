package com.riskdesk.domain.orderflow.perfectsetup;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupAxis.Result;

/**
 * Pure, framework-free confluence engine for the "Perfect Setup".
 *
 * <p>It scores six order-flow axes (see {@link PerfectSetupAxis}) independently
 * for LONG and SHORT, picks the dominant direction, and runs a transition-based
 * state machine ({@link PerfectSetupState}). A setup arms when the dominant
 * direction passes at least {@code armThreshold} axes <em>and</em> a tradable
 * plan with R:R ≥ {@code minRR} can be built.</p>
 *
 * <p>Stateless: the caller feeds back the previous {@link PerfectSetupSignal} so
 * the machine can advance ARMED → TRIGGERED / INVALIDATED / EXPIRED and honour
 * the post-terminal cooldown. All time is injected via {@link PerfectSetupInputs#now()}.</p>
 */
public final class PerfectSetupDetector {

    /**
     * Evaluate the current inputs against the previous signal.
     *
     * @param in    freshly-gathered market inputs (never null)
     * @param prior previous signal for this instrument, or {@code null} on first evaluation
     * @param t     thresholds
     * @return the new signal (state may be unchanged — the caller emits only on transition)
     */
    public PerfectSetupSignal evaluate(PerfectSetupInputs in, PerfectSetupSignal prior, PerfectSetupThresholds t) {
        Instant now = in.now();

        // --- Cooldown after a terminal state: hold the terminal state until it elapses. ---
        if (prior != null && prior.state().isTerminal()) {
            if (Duration.between(prior.timestamp(), now).getSeconds() < t.cooldownSeconds()) {
                // Refresh the checklist for display but keep state + terminal timestamp.
                DirectionScore ds = scoreDirection(prior.direction() == PerfectSetupDirection.SHORT
                    ? PerfectSetupDirection.SHORT : PerfectSetupDirection.LONG, in, t);
                return new PerfectSetupSignal(in.instrument(), prior.direction(), prior.state(),
                    prior.score(), PerfectSetupThresholds.MAX_SCORE, ds.axes(),
                    prior.entryLow(), prior.entryHigh(), prior.stop(), prior.tp1(), prior.tp2(),
                    prior.riskReward(), prior.triggerLevel(), prior.invalidationLevel(),
                    "Cooldown actif après " + prior.state(), prior.timestamp());
            }
            // Cooldown over → fall through to a fresh evaluation.
            prior = null;
        }

        // --- Advance an armed setup. ---
        if (prior != null && prior.state().isArmed()) {
            return advanceArmed(in, prior, t, now);
        }

        // --- Fresh evaluation from IDLE. ---
        return freshEvaluate(in, t, now);
    }

    // ------------------------------------------------------------------
    // Fresh evaluation
    // ------------------------------------------------------------------

    private PerfectSetupSignal freshEvaluate(PerfectSetupInputs in, PerfectSetupThresholds t, Instant now) {
        DirectionScore longScore = scoreDirection(PerfectSetupDirection.LONG, in, t);
        DirectionScore shortScore = scoreDirection(PerfectSetupDirection.SHORT, in, t);

        PerfectSetupDirection dir;
        DirectionScore chosen;
        if (longScore.score() > shortScore.score()) {
            dir = PerfectSetupDirection.LONG;
            chosen = longScore;
        } else if (shortScore.score() > longScore.score()) {
            dir = PerfectSetupDirection.SHORT;
            chosen = shortScore;
        } else {
            // Tie — no clear edge. Show the LONG checklist but stay idle.
            return idle(in, longScore, now, "Axes LONG/SHORT à égalité — pas d'edge");
        }

        TradePlan plan = buildPlan(dir, in, t);
        boolean rrOk = plan != null && plan.riskReward() != null && plan.riskReward() >= t.minRR();
        boolean canArm = chosen.score() >= t.armThreshold() && rrOk;

        if (!canArm) {
            String why = plan == null
                ? "Plan non calculable (prix/niveaux manquants)"
                : !rrOk
                    ? String.format(java.util.Locale.US, "R:R %.1f < %.1f", nz(plan.riskReward()), t.minRR())
                    : String.format("Confluence %d/%d < seuil %d", chosen.score(), PerfectSetupThresholds.MAX_SCORE, t.armThreshold());
            return idle(in, chosen, now, why);
        }

        PerfectSetupState state = dir == PerfectSetupDirection.LONG
            ? PerfectSetupState.LONG_ARMED : PerfectSetupState.SHORT_ARMED;
        String reasoning = String.format("%s ARMED %d/%d — %s",
            dir, chosen.score(), PerfectSetupThresholds.MAX_SCORE, plan.summary());
        return new PerfectSetupSignal(in.instrument(), dir, state,
            chosen.score(), PerfectSetupThresholds.MAX_SCORE, chosen.axes(),
            plan.entryLow(), plan.entryHigh(), plan.stop(), plan.tp1(), plan.tp2(),
            plan.riskReward(), plan.triggerLevel(), plan.invalidationLevel(),
            reasoning, now /* arm time */);
    }

    // ------------------------------------------------------------------
    // Armed → terminal transitions
    // ------------------------------------------------------------------

    private PerfectSetupSignal advanceArmed(PerfectSetupInputs in, PerfectSetupSignal prior,
                                            PerfectSetupThresholds t, Instant now) {
        PerfectSetupDirection dir = prior.direction();
        DirectionScore ds = scoreDirection(dir, in, t);
        Double price = in.price();

        // 1. TTL expiry.
        if (Duration.between(prior.timestamp(), now).getSeconds() > t.armTtlSeconds()) {
            return terminal(in, prior, ds, PerfectSetupState.EXPIRED, now,
                "Expiré — pas d'entrée dans le délai");
        }

        // 2. Stop breached before entry → invalidation.
        if (price != null && prior.invalidationLevel() != null) {
            boolean breached = dir == PerfectSetupDirection.LONG
                ? price <= prior.invalidationLevel()
                : price >= prior.invalidationLevel();
            if (breached) {
                return terminal(in, prior, ds, PerfectSetupState.INVALIDATED, now,
                    "Invalidé — stop touché");
            }
        }

        // 3. Thesis flip: the opposite direction now clears the threshold.
        PerfectSetupDirection opposite = dir == PerfectSetupDirection.LONG
            ? PerfectSetupDirection.SHORT : PerfectSetupDirection.LONG;
        DirectionScore oppScore = scoreDirection(opposite, in, t);
        if (oppScore.score() >= t.armThreshold() && oppScore.score() > ds.score()) {
            return terminal(in, prior, ds, PerfectSetupState.INVALIDATED, now,
                "Invalidé — thèse inversée (" + opposite + " " + oppScore.score() + ")");
        }

        // 4. Price entered the entry zone → triggered.
        if (price != null && prior.entryLow() != null && prior.entryHigh() != null
            && price >= prior.entryLow() && price <= prior.entryHigh()) {
            return terminal(in, prior, ds, PerfectSetupState.TRIGGERED, now,
                "Déclenché — prix dans la zone d'entrée");
        }

        // 5. Stay armed — carry the original plan + arm timestamp, refresh checklist.
        return new PerfectSetupSignal(in.instrument(), dir, prior.state(),
            ds.score(), PerfectSetupThresholds.MAX_SCORE, ds.axes(),
            prior.entryLow(), prior.entryHigh(), prior.stop(), prior.tp1(), prior.tp2(),
            prior.riskReward(), prior.triggerLevel(), prior.invalidationLevel(),
            prior.reasoning(), prior.timestamp());
    }

    private PerfectSetupSignal terminal(PerfectSetupInputs in, PerfectSetupSignal prior, DirectionScore ds,
                                        PerfectSetupState state, Instant now, String reasoning) {
        return new PerfectSetupSignal(in.instrument(), prior.direction(), state,
            ds.score(), PerfectSetupThresholds.MAX_SCORE, ds.axes(),
            prior.entryLow(), prior.entryHigh(), prior.stop(), prior.tp1(), prior.tp2(),
            prior.riskReward(), prior.triggerLevel(), prior.invalidationLevel(),
            reasoning, now /* terminal time → cooldown */);
    }

    private PerfectSetupSignal idle(PerfectSetupInputs in, DirectionScore ds, Instant now, String reasoning) {
        return new PerfectSetupSignal(in.instrument(), PerfectSetupDirection.NONE, PerfectSetupState.IDLE,
            ds.score(), PerfectSetupThresholds.MAX_SCORE, ds.axes(),
            null, null, null, null, null, null, null, null, reasoning, now);
    }

    // ------------------------------------------------------------------
    // Axis scoring
    // ------------------------------------------------------------------

    private DirectionScore scoreDirection(PerfectSetupDirection dir, PerfectSetupInputs in, PerfectSetupThresholds t) {
        boolean lng = dir == PerfectSetupDirection.LONG;
        List<Result> axes = new ArrayList<>(PerfectSetupThresholds.MAX_SCORE);

        axes.add(regimeAxis(lng, in, t));
        axes.add(icebergAxis(lng, in, t));
        axes.add(absorptionAxis(lng, in, t));
        axes.add(valueAxis(lng, in, t));
        axes.add(liquidityGrabAxis(lng, in, t));
        axes.add(riskRewardAxis(dir, in, t));

        int score = (int) axes.stream().filter(Result::passed).count();
        return new DirectionScore(score, axes);
    }

    private Result regimeAxis(boolean lng, PerfectSetupInputs in, PerfectSetupThresholds t) {
        String want = lng ? "ACCUMULATION" : "DISTRIBUTION";
        boolean ok = want.equals(in.distType()) && in.distConf() != null && in.distConf() >= t.regimeMinConf();
        String cyc = in.cycleType();
        boolean cycleAligned = cyc != null && ((lng && cyc.contains("BULL")) || (!lng && cyc.contains("BEAR")));
        String detail = in.distType() == null
            ? "aucun régime récent"
            : in.distType() + " conf " + (in.distConf() == null ? "?" : in.distConf())
              + (cycleAligned ? " + cycle aligné" : "");
        return new Result(PerfectSetupAxis.REGIME, ok, detail);
    }

    private Result icebergAxis(boolean lng, PerfectSetupInputs in, PerfectSetupThresholds t) {
        IcebergContext ice = in.iceberg();
        Double price = in.price();
        double nearBand = t.nearLevelTicks() * Math.max(in.tickSize(), 0.0);
        Double level = lng ? ice.bidLevel() : ice.askLevel();
        double score = lng ? ice.bidScore() : ice.askScore();
        boolean ok = false;
        String detail = lng ? "pas d'iceberg BID proche" : "pas d'iceberg ASK proche";
        if (level != null && score >= t.icebergMinScore()) {
            boolean near = price == null || nearBand <= 0 || Math.abs(price - level) <= nearBand;
            // Support must sit at/below price (LONG); resistance at/above (SHORT).
            boolean correctSide = price == null
                || (lng ? level <= price + nearBand : level >= price - nearBand);
            ok = near && correctSide;
            detail = String.format(java.util.Locale.US, "%s @%.2f score %.0f", lng ? "BID" : "ASK", level, score);
        }
        return new Result(PerfectSetupAxis.ICEBERG, ok, detail);
    }

    private Result absorptionAxis(boolean lng, PerfectSetupInputs in, PerfectSetupThresholds t) {
        String want = lng ? "BULL" : "BEAR";
        boolean ok = want.equals(in.absDominantSide()) && in.absMaxScore() >= t.absorptionClimaxMinScore();
        String decay = decayNote(in.recentAbsMagnitudes());
        String detail = in.absDominantSide() == null
            ? "pas d'absorption dominante"
            : String.format(java.util.Locale.US, "dominante %s, score max %.1f%s",
                in.absDominantSide(), in.absMaxScore(), decay);
        return new Result(PerfectSetupAxis.ABSORPTION, ok, detail);
    }

    private Result valueAxis(boolean lng, PerfectSetupInputs in, PerfectSetupThresholds t) {
        Double price = in.price();
        Double vwap = in.vwap();
        Double bb = in.bbPct();
        boolean bandOk = bb != null && (lng ? bb <= t.bbLow() : bb >= t.bbHigh());
        boolean vwapOk = vwap != null && price != null && (lng ? price <= vwap : price >= vwap);
        boolean ok = bandOk || vwapOk;
        String detail = String.format(java.util.Locale.US, "%%B=%s, prix %s VWAP",
            bb == null ? "?" : String.format(java.util.Locale.US, "%.2f", bb),
            (vwap == null || price == null) ? "?" : (price <= vwap ? "≤" : ">"));
        return new Result(PerfectSetupAxis.VALUE, ok, detail);
    }

    private Result liquidityGrabAxis(boolean lng, PerfectSetupInputs in, PerfectSetupThresholds t) {
        // Flash crashes are downside events: a REVERSING phase is a bid-collapse
        // that was reclaimed → bullish. Only contributes to LONG.
        FlashCrashContext f = in.flash();
        boolean ok = lng && f.isReversing() && f.reversalScore() >= t.flashReversalMinScore();
        String detail = f.isReversing()
            ? String.format(java.util.Locale.US, "REVERSING (reversal %.0f)", f.reversalScore())
            : "phase " + f.phase();
        return new Result(PerfectSetupAxis.LIQUIDITY_GRAB, ok, detail);
    }

    private Result riskRewardAxis(PerfectSetupDirection dir, PerfectSetupInputs in, PerfectSetupThresholds t) {
        TradePlan plan = buildPlan(dir, in, t);
        boolean ok = plan != null && plan.riskReward() != null && plan.riskReward() >= t.minRR();
        String detail = (plan == null || plan.riskReward() == null)
            ? "R:R non calculable"
            : String.format(java.util.Locale.US, "R:R %.1f (min %.1f)", plan.riskReward(), t.minRR());
        return new Result(PerfectSetupAxis.RISK_REWARD, ok, detail);
    }

    // ------------------------------------------------------------------
    // Trade-plan geometry
    // ------------------------------------------------------------------

    private TradePlan buildPlan(PerfectSetupDirection dir, PerfectSetupInputs in, PerfectSetupThresholds t) {
        Double price = in.price();
        if (price == null || dir == PerfectSetupDirection.NONE) return null;

        double tick = Math.max(in.tickSize(), 0.0);
        double atr = (in.atr() != null && in.atr() > 0)
            ? in.atr()
            : Math.max(tick * 8, Math.abs(price) * 0.001);
        double slBuffer = Math.max(atr * t.slBufferAtrFraction(), tick * 4);
        double band = Math.max(atr * 0.25, tick * 2);

        IcebergContext ice = in.iceberg();

        if (dir == PerfectSetupDirection.LONG) {
            double support = firstFinite(ice.bidLevel(),
                belowPrice(in.vwapLowerBand(), price), price - atr);
            double resistance = firstFinite(abovePrice(ice.askLevel(), price),
                abovePrice(in.vwapUpperBand(), price), price + atr * 2);
            double entryLow = support;
            double entryHigh = Math.max(support + tick, Math.min(price, support + band));
            double entryMid = (entryLow + entryHigh) / 2.0;
            double stop = support - slBuffer;
            double tp1 = Math.max(resistance, entryHigh + atr);
            double tp2 = tp1 + (tp1 - entryMid);
            double risk = entryMid - stop;
            Double rr = risk > 0 ? (tp1 - entryMid) / risk : null;
            return new TradePlan(entryLow, entryHigh, stop, tp1, tp2, rr, entryMid, stop);
        } else {
            double resistance = firstFinite(ice.askLevel(),
                abovePrice(in.vwapUpperBand(), price), price + atr);
            double support = firstFinite(belowPrice(ice.bidLevel(), price),
                belowPrice(in.vwapLowerBand(), price), price - atr * 2);
            double entryHigh = resistance;
            double entryLow = Math.min(resistance - tick, Math.max(price, resistance - band));
            double entryMid = (entryLow + entryHigh) / 2.0;
            double stop = resistance + slBuffer;
            double tp1 = Math.min(support, entryLow - atr);
            double tp2 = tp1 - (entryMid - tp1);
            double risk = stop - entryMid;
            Double rr = risk > 0 ? (entryMid - tp1) / risk : null;
            return new TradePlan(entryLow, entryHigh, stop, tp1, tp2, rr, entryMid, stop);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Returns the magnitude only if it is below price (used for a LONG support candidate). */
    private static Double belowPrice(Double level, double price) {
        return (level != null && level < price) ? level : null;
    }

    /** Returns the magnitude only if it is above price (used for a resistance candidate). */
    private static Double abovePrice(Double level, double price) {
        return (level != null && level > price) ? level : null;
    }

    private static double firstFinite(Double... candidates) {
        for (Double c : candidates) {
            if (c != null && Double.isFinite(c)) return c;
        }
        return Double.NaN;
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    /** Climax-decay note: whether the most recent absorption magnitude is below the window max. */
    private static String decayNote(List<Long> magnitudes) {
        if (magnitudes == null || magnitudes.size() < 2) return "";
        long latest = Math.abs(magnitudes.get(0));
        long max = 0;
        for (Long m : magnitudes) max = Math.max(max, Math.abs(m));
        return latest < max ? " (climax en décrue)" : "";
    }

    private record DirectionScore(int score, List<Result> axes) {}

    private record TradePlan(
        double entryLow, double entryHigh, double stop, double tp1, double tp2,
        Double riskReward, double triggerLevel, double invalidationLevel
    ) {
        String summary() {
            return String.format(java.util.Locale.US, "entrée %.2f-%.2f, SL %.2f, TP %.2f/%.2f, R:R %.1f",
                entryLow, entryHigh, stop, tp1, tp2, riskReward == null ? 0.0 : riskReward);
        }
    }
}

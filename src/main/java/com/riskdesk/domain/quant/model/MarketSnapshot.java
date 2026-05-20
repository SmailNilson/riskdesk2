package com.riskdesk.domain.quant.model;

import java.time.Instant;

/**
 * Fully-aggregated market view fed to {@link com.riskdesk.domain.quant.engine.GateEvaluator}.
 * It is built by the application service from raw port data and contains
 * everything the gate logic needs — the evaluator stays a pure function.
 *
 * <p>All numeric fields use boxed types so that "no data" can be expressed
 * with {@code null} (matching the Python script's {@code None} semantics).</p>
 *
 * @param now              evaluation timestamp (UTC)
 * @param currentPrice     latest price (nullable if no quote)
 * @param priceSource      provenance of the price (e.g. {@code LIVE_PUSH})
 * @param delta            cumulative delta over the active window
 * @param buyPct           buy ratio percentage (0–100)
 * @param absFreshTotal    count of ABS events within the last 3 minutes
 * @param absBull8Count    bullish ABS events with score ≥ 8 within 3 minutes
 * @param absBear8Count    bearish ABS events with score ≥ 8 within 3 minutes
 * @param absMaxScore      max absorption score over the 3-minute window
 * @param distType         most recent distribution type ({@code DISTRIBUTION} / {@code ACCUMULATION}) within 10 minutes
 * @param distConf         confidence of {@code distType} (0–100)
 * @param distTimestamp    timestamp of the most recent dist event
 * @param cycleType        most recent cycle type within 10 minutes
 * @param cyclePhase       phase of {@code cycleType}
 * @param cycleAgeMinutes  minutes since the cycle event was emitted
 */
public record MarketSnapshot(
    Instant now,
    Double currentPrice,
    String priceSource,
    Double delta,
    Double buyPct,
    int absFreshTotal,
    int absBull8Count,
    int absBear8Count,
    double absMaxScore,
    String distType,
    Integer distConf,
    Instant distTimestamp,
    String cycleType,
    String cyclePhase,
    Integer cycleAgeMinutes
) {
    /** Dominant absorption side: BULL, BEAR or MIX (matching the Python {@code dom} variable). */
    public String dominantSide() {
        if (absBull8Count > absBear8Count) return "BULL";
        if (absBear8Count > absBull8Count) return "BEAR";
        return "MIX";
    }

    /** Convenience builder — there are too many fields for a positional constructor. */
    public static final class Builder {
        private Instant now = Instant.now();
        private Double currentPrice;
        private String priceSource = "";
        private Double delta;
        private Double buyPct;
        private int absFreshTotal;
        private int absBull8Count;
        private int absBear8Count;
        private double absMaxScore;
        private String distType;
        private Integer distConf;
        private Instant distTimestamp;
        private String cycleType;
        private String cyclePhase;
        private Integer cycleAgeMinutes;

        public Builder now(Instant t) { this.now = t; return this; }
        public Builder price(Double p) { this.currentPrice = p; return this; }
        public Builder priceSource(String s) { this.priceSource = s; return this; }
        public Builder delta(Double d) { this.delta = d; return this; }
        public Builder buyPct(Double b) { this.buyPct = b; return this; }
        public Builder absFresh(int n) { this.absFreshTotal = n; return this; }
        public Builder absBull8(int n) { this.absBull8Count = n; return this; }
        public Builder absBear8(int n) { this.absBear8Count = n; return this; }
        public Builder absMaxScore(double s) { this.absMaxScore = s; return this; }
        public Builder dist(String type, Integer conf) {
            this.distType = type;
            this.distConf = conf;
            return this;
        }
        public Builder distTimestamp(Instant t) { this.distTimestamp = t; return this; }
        public Builder cycle(String type, String phase) {
            this.cycleType = type;
            this.cyclePhase = phase;
            return this;
        }
        public Builder cycleAge(Integer minutes) { this.cycleAgeMinutes = minutes; return this; }

        public MarketSnapshot build() {
            return new MarketSnapshot(now, currentPrice, priceSource, delta, buyPct,
                absFreshTotal, absBull8Count, absBear8Count, absMaxScore,
                distType, distConf, distTimestamp, cycleType, cyclePhase, cycleAgeMinutes);
        }
    }
}

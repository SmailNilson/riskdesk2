package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the Order Flow subsystem.
 * Binds to {@code riskdesk.order-flow.*} in application.properties.
 */
@Component
@ConfigurationProperties(prefix = "riskdesk.order-flow")
public class OrderFlowProperties {

    private TickByTick tickByTick = new TickByTick();
    private Depth depth = new Depth();
    private TickLog tickLog = new TickLog();
    private DepthLog depthLog = new DepthLog();
    private Absorption absorption = new Absorption();
    private Distribution distribution = new Distribution();
    private Momentum momentum = new Momentum();
    private Cycle cycle = new Cycle();
    private Freshness freshness = new Freshness();
    private Iceberg iceberg = new Iceberg();
    private Spoofing spoofing = new Spoofing();
    private FlashCrash flashCrash = new FlashCrash();
    private TickChart tickChart = new TickChart();
    private Footprint footprint = new Footprint();
    private WallTracker wallTracker = new WallTracker();
    private Cvd cvd = new Cvd();
    private Tape tape = new Tape();
    private BigPrint bigPrint = new BigPrint();
    private VolumeProfile volumeProfile = new VolumeProfile();

    public TickByTick getTickByTick() { return tickByTick; }
    public void setTickByTick(TickByTick tickByTick) { this.tickByTick = tickByTick; }
    public Depth getDepth() { return depth; }
    public void setDepth(Depth depth) { this.depth = depth; }
    public TickLog getTickLog() { return tickLog; }
    public void setTickLog(TickLog tickLog) { this.tickLog = tickLog; }
    public DepthLog getDepthLog() { return depthLog; }
    public void setDepthLog(DepthLog depthLog) { this.depthLog = depthLog; }
    public Absorption getAbsorption() { return absorption; }
    public void setAbsorption(Absorption absorption) { this.absorption = absorption; }
    public Distribution getDistribution() { return distribution; }
    public void setDistribution(Distribution distribution) { this.distribution = distribution; }
    public Momentum getMomentum() { return momentum; }
    public void setMomentum(Momentum momentum) { this.momentum = momentum; }
    public Cycle getCycle() { return cycle; }
    public void setCycle(Cycle cycle) { this.cycle = cycle; }
    public Freshness getFreshness() { return freshness; }
    public void setFreshness(Freshness freshness) { this.freshness = freshness; }
    public Iceberg getIceberg() { return iceberg; }
    public void setIceberg(Iceberg iceberg) { this.iceberg = iceberg; }
    public Spoofing getSpoofing() { return spoofing; }
    public void setSpoofing(Spoofing spoofing) { this.spoofing = spoofing; }
    public FlashCrash getFlashCrash() { return flashCrash; }
    public void setFlashCrash(FlashCrash flashCrash) { this.flashCrash = flashCrash; }
    public TickChart getTickChart() { return tickChart; }
    public void setTickChart(TickChart tickChart) { this.tickChart = tickChart; }
    public Footprint getFootprint() { return footprint; }
    public void setFootprint(Footprint footprint) { this.footprint = footprint; }
    public WallTracker getWallTracker() { return wallTracker; }
    public void setWallTracker(WallTracker wallTracker) { this.wallTracker = wallTracker; }
    public Cvd getCvd() { return cvd; }
    public void setCvd(Cvd cvd) { this.cvd = cvd; }
    public Tape getTape() { return tape; }
    public void setTape(Tape tape) { this.tape = tape; }
    public BigPrint getBigPrint() { return bigPrint; }
    public void setBigPrint(BigPrint bigPrint) { this.bigPrint = bigPrint; }
    public VolumeProfile getVolumeProfile() { return volumeProfile; }
    public void setVolumeProfile(VolumeProfile volumeProfile) { this.volumeProfile = volumeProfile; }

    /** Tick-by-tick data subscription (reqTickByTickData). */
    public static class TickByTick {
        private boolean enabled = true;
        /**
         * Instruments that actually open a tick-by-tick line. Trimmed to the two traded
         * instruments (MNQ, MCL) to reduce concurrent IBKR market-data pressure — 4 AllLast
         * lines on one tick clientId is a frequent cause of {@code totalTicksReceived:0}
         * (354/10089 entitlement, 10197 competing session). This is a pressure reduction,
         * not a provable cap fix; confirm via {@code /api/order-flow/status}.
         */
        private List<String> instruments = List.of("MNQ", "MCL");
        /**
         * Instruments intentionally NOT subscribed to a tick line (delta panels stay blank,
         * reported as {@code DEGRADED_NOT_SUBSCRIBED} rather than a misleading CLV estimate).
         */
        private List<String> degradedInstruments = List.of("MGC", "E6");
        /**
         * Max staleness (seconds) for the last-known-good BBO cache used to classify ticks
         * when no live quote is available. Larger than the price stream's 30s bid/ask nulling
         * so a slightly-stale-but-valid BBO classifies before the tick-rule fallback (L1).
         */
        private int bboMaxStalenessSeconds = 90;
        /**
         * When no fresh BBO/quote is available, classify ticks by trade-to-trade direction
         * (uptick=BUY / downtick=SELL) instead of dropping them as UNCLASSIFIED (L2).
         * Such windows are stamped {@code REAL_TICKS_TICKRULE} (0.5 confidence), never REAL.
         */
        private boolean tickRuleFallbackEnabled = true;
        /**
         * Minimum fraction of quote-classified (Lee-Ready) volume for a window to keep the
         * {@code REAL_TICKS} (1.0) source; below it the window is {@code REAL_TICKS_TICKRULE} (L2).
         */
        private double realTicksMinQuoteFraction = 0.5;
        /** Shared per-instrument resubscribe rate cap across all watchdog loops (L3). */
        private int maxResubscribesPerMinute = 2;
        /**
         * When false (default once the orchestrator delta-freshness watchdog owns recovery),
         * the internal 60s tick watchdog only raises an alarm and does NOT cancel/resubscribe —
         * a single owner of resubscription prevents reqId churn that IBKR throttles (L3).
         */
        private boolean internalWatchdogResubscribes = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getInstruments() { return instruments; }
        public void setInstruments(List<String> instruments) { this.instruments = instruments; }
        public List<String> getDegradedInstruments() { return degradedInstruments; }
        public void setDegradedInstruments(List<String> degradedInstruments) { this.degradedInstruments = degradedInstruments; }
        public int getBboMaxStalenessSeconds() { return bboMaxStalenessSeconds; }
        public void setBboMaxStalenessSeconds(int v) { this.bboMaxStalenessSeconds = v; }
        public boolean isTickRuleFallbackEnabled() { return tickRuleFallbackEnabled; }
        public void setTickRuleFallbackEnabled(boolean v) { this.tickRuleFallbackEnabled = v; }
        public double getRealTicksMinQuoteFraction() { return realTicksMinQuoteFraction; }
        public void setRealTicksMinQuoteFraction(double v) { this.realTicksMinQuoteFraction = v; }
        public int getMaxResubscribesPerMinute() { return maxResubscribesPerMinute; }
        public void setMaxResubscribesPerMinute(int v) { this.maxResubscribesPerMinute = v; }
        public boolean isInternalWatchdogResubscribes() { return internalWatchdogResubscribes; }
        public void setInternalWatchdogResubscribes(boolean v) { this.internalWatchdogResubscribes = v; }
    }

    /** Market depth subscription (reqMarketDepth). */
    public static class Depth {
        private boolean enabled = true;
        private List<String> instruments = List.of("MNQ", "MCL");
        private int numRows = 10;
        private double wallThresholdMultiplier = 5.0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getInstruments() { return instruments; }
        public void setInstruments(List<String> instruments) { this.instruments = instruments; }
        public int getNumRows() { return numRows; }
        public void setNumRows(int numRows) { this.numRows = numRows; }
        public double getWallThresholdMultiplier() { return wallThresholdMultiplier; }
        public void setWallThresholdMultiplier(double wallThresholdMultiplier) { this.wallThresholdMultiplier = wallThresholdMultiplier; }
    }

    /**
     * Wall lifecycle tracker (UC-OF-012): traces every DOM "WALL" level (≥ wall-threshold ×
     * average level size) from appearance to outcome — CONSUMED / PULLED / FADED /
     * OUT_OF_RANGE. History persisted in {@code order_flow_wall_episodes}.
     */
    public static class WallTracker {
        private boolean enabled = true;
        /** Seconds a wall may vanish from the book before its episode is finalized (re-flag within grace = same episode). */
        private double graceSeconds = 5.0;
        /** Episodes flagged for less than this (seconds) are dropped as book flicker. */
        private double minLifetimeSeconds = 3.0;
        /** End distance (ticks) at or below which the wall counts as CONSUMED — price reached it. */
        private double consumedProximityTicks = 1.0;
        /** End size below this fraction of max size (price still away) = PULLED (spoof suspect). */
        private double pulledRemnantRatio = 0.25;
        /** Absolute minimum size (contracts) to open an episode. 0 = relative threshold only. */
        private long minSize = 0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getGraceSeconds() { return graceSeconds; }
        public void setGraceSeconds(double v) { this.graceSeconds = v; }
        public double getMinLifetimeSeconds() { return minLifetimeSeconds; }
        public void setMinLifetimeSeconds(double v) { this.minLifetimeSeconds = v; }
        public double getConsumedProximityTicks() { return consumedProximityTicks; }
        public void setConsumedProximityTicks(double v) { this.consumedProximityTicks = v; }
        public double getPulledRemnantRatio() { return pulledRemnantRatio; }
        public void setPulledRemnantRatio(double v) { this.pulledRemnantRatio = v; }
        public long getMinSize() { return minSize; }
        public void setMinSize(long v) { this.minSize = v; }
    }

    /** Tick data logging (persistence for calibration). */
    public static class TickLog {
        private boolean enabled = true;
        private int retentionDays = 30;
        private int batchSize = 100;
        private long flushIntervalMs = 5000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public long getFlushIntervalMs() { return flushIntervalMs; }
        public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
    }

    /** Depth snapshot logging (periodic persistence for calibration). */
    public static class DepthLog {
        private boolean enabled = true;
        private long intervalMs = 10_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    }

    /**
     * Per-cycle absorption detector tuning.
     * <p>
     * The TickByTickAggregator stores a 5 min rolling window for delta/divergence panels;
     * absorption needs a much shorter window to detect transient passive-absorption events.
     */
    public static class Absorption {
        private boolean enabled = true;
        /** Window for absorption snapshot — short enough to detect transient absorption bursts. */
        private int windowSeconds = 10;
        /**
         * Number of recent windows used to compute baseline {@code avgVolume}.
         * 12 windows × 5s polling = 60s of history.
         */
        private int volumeHistorySize = 12;
        /** Baseline delta normaliser — score = (|delta|/deltaThreshold) × stability × (vol/avgVol). */
        private long deltaThreshold = 50;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int v) { this.windowSeconds = v; }
        public int getVolumeHistorySize() { return volumeHistorySize; }
        public void setVolumeHistorySize(int v) { this.volumeHistorySize = v; }
        public long getDeltaThreshold() { return deltaThreshold; }
        public void setDeltaThreshold(long v) { this.deltaThreshold = v; }

        /**
         * Per-instrument minimum score for an absorption event to be DISPLAYED
         * (WebSocket /topic/absorption + REST history). Internal consumers
         * (distribution chaining, quant gates, persistence) are NOT filtered.
         * Calibrated 2026-06-10 from 14 days of prod score percentiles, targeting
         * ~5-15 displayed events/day per instrument (raw emission was ~1100/day on MNQ).
         */
        private Map<String, Double> minDisplayScore = new HashMap<>(Map.of(
            "MNQ", 80.0,  // ≈ P99 → ~11/day
            "MCL", 30.0,  // ~7/day
            "MGC", 30.0,  // ~3/day
            "E6", 30.0    // ~7/day
        ));

        public Map<String, Double> getMinDisplayScore() { return minDisplayScore; }
        public void setMinDisplayScore(Map<String, Double> v) { this.minDisplayScore = v; }

        /** Display threshold for an instrument; 0 (no filter) when not configured. */
        public double minDisplayScoreFor(String instrument) {
            Double v = minDisplayScore.get(instrument);
            return v != null ? v : 0.0;
        }
    }

    /** Institutional distribution / accumulation detector. MNQ-tuned defaults. */
    public static class Distribution {
        private boolean enabled = true;
        private int minConsecutiveCount = 3;
        private double minAvgScore = 2.5;
        private int windowTtlMinutes = 10;
        private int maxInterEventGapSeconds = 20;
        /** Independent cooldown after firing — shorter than windowTtl allows second-wave detection. */
        private int cooldownMinutes = 8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMinConsecutiveCount() { return minConsecutiveCount; }
        public void setMinConsecutiveCount(int v) { this.minConsecutiveCount = v; }
        public double getMinAvgScore() { return minAvgScore; }
        public void setMinAvgScore(double v) { this.minAvgScore = v; }
        public int getWindowTtlMinutes() { return windowTtlMinutes; }
        public void setWindowTtlMinutes(int v) { this.windowTtlMinutes = v; }
        public int getMaxInterEventGapSeconds() { return maxInterEventGapSeconds; }
        public void setMaxInterEventGapSeconds(int v) { this.maxInterEventGapSeconds = v; }
        public int getCooldownMinutes() { return cooldownMinutes; }
        public void setCooldownMinutes(int v) { this.cooldownMinutes = v; }
    }

    /** Aggressive momentum burst detector (inverse of absorption). MNQ-tuned defaults. */
    public static class Momentum {
        private boolean enabled = true;
        /**
         * Sigmoid-scale threshold: score ∈ [0,1]. Recalibrated 2026-06-10: at 0.55 MNQ
         * fired 0-2/day (median emitted score 0.59 → half of all fires sat in
         * [0.55, 0.59], i.e. high density just under the cutoff). 0.50 targets ~3-10/day.
         */
        private double scoreThreshold = 0.50;
        /**
         * Minimum |price move| as a fraction of ATR before scoring. Lowered 0.3 → 0.2
         * (2026-06-10): on a 5-10s window, 30% of a 15-25pt MNQ ATR rejected nearly
         * every burst candidate before scoring even ran.
         */
        private double minPriceMoveFractionOfAtr = 0.2;
        /** Minimum ATR-distance from last same-direction fire before re-firing. */
        private double atrDistanceThreshold = 0.5;
        /** Safety rate cap: max fires per rolling 60-second window (both sides combined). */
        private int maxFiresPerMinute = 2;
        /** REST history cutoff (minutes). Events older than this are not returned by the history endpoint. */
        private int historyMaxAgeMinutes = 120;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getScoreThreshold() { return scoreThreshold; }
        public void setScoreThreshold(double v) { this.scoreThreshold = v; }
        public double getMinPriceMoveFractionOfAtr() { return minPriceMoveFractionOfAtr; }
        public void setMinPriceMoveFractionOfAtr(double v) { this.minPriceMoveFractionOfAtr = v; }
        public double getAtrDistanceThreshold() { return atrDistanceThreshold; }
        public void setAtrDistanceThreshold(double v) { this.atrDistanceThreshold = v; }
        public int getMaxFiresPerMinute() { return maxFiresPerMinute; }
        public void setMaxFiresPerMinute(int v) { this.maxFiresPerMinute = v; }
        public int getHistoryMaxAgeMinutes() { return historyMaxAgeMinutes; }
        public void setHistoryMaxAgeMinutes(int v) { this.historyMaxAgeMinutes = v; }
    }

    /** Smart-money cycle meta-detector (chains distribution → momentum → accumulation). MNQ-tuned. */
    public static class Cycle {
        private boolean enabled = true;
        private int momentumWindowMinutes = 10;
        private int mirrorWindowMinutes = 20;
        private int cooldownMinutes = 5;
        /**
         * Minimum confidence (0-100) for a cycle signal to be exposed (REST history +
         * WebSocket). State machine is unaffected. Recalibrated 2026-06-10: prod
         * confidences cluster at 51-53 (P90 = 53) with rare spikes >90 — at 70 the
         * panel was empty (~1 event/14 days); 55 surfaces the genuine tail.
         */
        private int minConfidence = 55;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMomentumWindowMinutes() { return momentumWindowMinutes; }
        public void setMomentumWindowMinutes(int v) { this.momentumWindowMinutes = v; }
        public int getMirrorWindowMinutes() { return mirrorWindowMinutes; }
        public void setMirrorWindowMinutes(int v) { this.mirrorWindowMinutes = v; }
        public int getCooldownMinutes() { return cooldownMinutes; }
        public void setCooldownMinutes(int v) { this.cooldownMinutes = v; }
        public int getMinConfidence() { return minConfidence; }
        public void setMinConfidence(int v) { this.minConfidence = v; }
    }

    /**
     * Freshness watchdog for the L2 depth feed. Detects a silently frozen book
     * (socket alive, no updates flowing — typical of an overloaded TWS) and forces a
     * cancel + re-subscribe. The tick-by-tick feed already self-heals via
     * {@code TickByTickClient}'s internal watchdog, so this only governs depth.
     */
    public static class Freshness {
        private boolean enabled = true;
        /** Watchdog cadence. */
        private long checkIntervalMs = 15_000;
        /** Depth is considered frozen when its last real update is older than this. */
        private int depthStalenessSeconds = 20;
        /**
         * Delta (tick) feed is considered frozen when its last <b>classified</b> tick is older
         * than this. Drives {@code checkDeltaFreshness} (L3) — the tick equivalent of the depth
         * watchdog, keyed on classified-tick yield (not raw arrival) so a 100%-UNCLASSIFIED but
         * alive stream is detected and resubscribed.
         */
        private int deltaStalenessSeconds = 45;
        /** Don't evaluate an instrument until this long after its (re)subscription — avoids churn. */
        private int graceSeconds = 45;
        /** Consecutive stale evictions before escalating to an error log (re-subscribe not recovering). */
        private int maxStrikes = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getCheckIntervalMs() { return checkIntervalMs; }
        public void setCheckIntervalMs(long v) { this.checkIntervalMs = v; }
        public int getDepthStalenessSeconds() { return depthStalenessSeconds; }
        public void setDepthStalenessSeconds(int v) { this.depthStalenessSeconds = v; }
        public int getDeltaStalenessSeconds() { return deltaStalenessSeconds; }
        public void setDeltaStalenessSeconds(int v) { this.deltaStalenessSeconds = v; }
        public int getGraceSeconds() { return graceSeconds; }
        public void setGraceSeconds(int v) { this.graceSeconds = v; }
        public int getMaxStrikes() { return maxStrikes; }
        public void setMaxStrikes(int v) { this.maxStrikes = v; }
    }

    /**
     * Iceberg detector (UC-OF-014). Scans recent wall events for repeated
     * APPEARED → DISAPPEARED → APPEARED recharge cycles at the same price level.
     * Re-scans a rolling window each cycle; de-duplicated by {@code RecentSignalGate}.
     */
    public static class Iceberg {
        private boolean enabled = true;
        /** Cadence of the live scan loop (shared with spoofing). */
        private long evalIntervalMs = 2_000;
        /** Wall-event lookback window fed to the detector. Matches its 60s recharge window. */
        private int lookbackSeconds = 60;
        /** Suppress re-emitting the same (side, price level) within this many seconds. */
        private int dedupSeconds = 60;
        /** Only emit icebergs scoring at least this (0-100); filters weak single-recharge noise. */
        private double minScore = 50.0;
        /**
         * Width of the dedup bucket in ticks: events within the same N-tick price band
         * share one dedup key, so one physical order flickering across adjacent levels
         * cannot re-fire per tick. 8 ticks = 2 points on MNQ.
         */
        private int dedupPriceTicks = 8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getEvalIntervalMs() { return evalIntervalMs; }
        public void setEvalIntervalMs(long v) { this.evalIntervalMs = v; }
        public int getLookbackSeconds() { return lookbackSeconds; }
        public void setLookbackSeconds(int v) { this.lookbackSeconds = v; }
        public int getDedupSeconds() { return dedupSeconds; }
        public void setDedupSeconds(int v) { this.dedupSeconds = v; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double v) { this.minScore = v; }
        public int getDedupPriceTicks() { return dedupPriceTicks; }
        public void setDedupPriceTicks(int v) { this.dedupPriceTicks = v; }
    }

    /**
     * Spoofing detector (UC-OF-005). Pairs APPEARED/DISAPPEARED wall events and flags
     * large walls (≥ 3× avg level size) pulled within 10s. Shares the scan loop with iceberg.
     */
    public static class Spoofing {
        private boolean enabled = true;
        /** Wall-event lookback window fed to the detector. */
        private int lookbackSeconds = 30;
        /** Suppress re-emitting the same (side, price level) within this many seconds. */
        private int dedupSeconds = 30;
        /** Only emit spoofs above this composite score (detector's own floor is 1.0). */
        private double minScore = 1.0;
        /**
         * Width of the dedup bucket in ticks: events within the same N-tick price band
         * share one dedup key, so one physical wall flickering across adjacent levels
         * cannot re-fire per tick (the 6-duplicate-spoof artifact). 8 ticks = 2 pts MNQ.
         */
        private int dedupPriceTicks = 8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getLookbackSeconds() { return lookbackSeconds; }
        public void setLookbackSeconds(int v) { this.lookbackSeconds = v; }
        public int getDedupSeconds() { return dedupSeconds; }
        public void setDedupSeconds(int v) { this.dedupSeconds = v; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double v) { this.minScore = v; }
        public int getDedupPriceTicks() { return dedupPriceTicks; }
        public void setDedupPriceTicks(int v) { this.dedupPriceTicks = v; }

        /**
         * Per-instrument minimum score for a spoofing event to be DISPLAYED
         * (WebSocket /topic/spoofing + REST history). Detection/persistence unfiltered.
         * Calibrated 2026-06-10 from prod percentiles (MNQ raw ≈ 200/day → ~18/day at 35).
         */
        private Map<String, Double> minDisplayScore = new HashMap<>(Map.of(
            "MNQ", 35.0,
            "MCL", 20.0,
            "MGC", 20.0,
            "E6", 20.0
        ));

        public Map<String, Double> getMinDisplayScore() { return minDisplayScore; }
        public void setMinDisplayScore(Map<String, Double> v) { this.minDisplayScore = v; }

        /** Display threshold for an instrument; 0 (no filter) when not configured. */
        public double minDisplayScoreFor(String instrument) {
            Double v = minDisplayScore.get(instrument);
            return v != null ? v : 0.0;
        }
    }

    /**
     * Flash-crash FSM (UC-OF-006), evaluated live on the 5s order-flow loop with a
     * stateful FSM per instrument. Thresholds come from {@code FlashCrashConfigPort}
     * (persisted, per-instrument) with {@code FlashCrashThresholds.defaults()} as fallback.
     */
    public static class FlashCrash {
        private boolean enabled = true;
        /** Tick window used to derive velocity / delta5s / volume-spike each evaluation. */
        private int windowSeconds = 5;
        /** Rolling windows kept to compute the volume-spike baseline. */
        private int volumeHistorySize = 12;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int v) { this.windowSeconds = v; }
        public int getVolumeHistorySize() { return volumeHistorySize; }
        public void setVolumeHistorySize(int v) { this.volumeHistorySize = v; }
    }

    /** Tick chart: constant-tick-count bars built from classified trades. */
    public static class TickChart {
        /** Bar size (trades per bar) for instruments not in the per-instrument map. */
        private int defaultTicksPerBar = 200;
        /** Per-instrument bar sizes; MCL trades far less than MNQ so its bars are smaller. */
        private Map<String, Integer> ticksPerBar = new HashMap<>(Map.of(
            "MNQ", 200,
            "MCL", 100
        ));
        /**
         * Completed bars kept per instrument (ring buffer). Sized so client-side
         * re-aggregation into larger bars (1000/2000 ticks) still has ~300 merged
         * bars of history (~140 bytes/bar → <1 MB for two instruments).
         */
        private int maxBars = 3000;

        public int getDefaultTicksPerBar() { return defaultTicksPerBar; }
        public void setDefaultTicksPerBar(int v) { this.defaultTicksPerBar = v; }
        public Map<String, Integer> getTicksPerBar() { return ticksPerBar; }
        public void setTicksPerBar(Map<String, Integer> v) { this.ticksPerBar = v; }
        public int getMaxBars() { return maxBars; }
        public void setMaxBars(int v) { this.maxBars = v; }
    }

    /**
     * Session-anchored CVD divergence detector (UC-OF-CVD): swing-pivot divergences
     * between 1m price closes and the session CVD, published on {@code /topic/cvd-divergence}.
     */
    public static class Cvd {
        private boolean enabled = true;
        /** Confirmed bars on each side of a swing pivot (5 left / 5 right). */
        private int pivotBars = 5;
        /** Minimum |CVD difference| (contracts) between the two pivots — filters flat-CVD noise. */
        private long minCvdSwing = 200;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPivotBars() { return pivotBars; }
        public void setPivotBars(int v) { this.pivotBars = v; }
        public long getMinCvdSwing() { return minCvdSwing; }
        public void setMinCvdSwing(long v) { this.minCvdSwing = v; }
    }

    /**
     * Speed of tape (trade-intensity z-score): trades/sec over trailing 5s and 30s windows,
     * z-scored against a rolling 30-min baseline sampled every 5s by the orchestrator pass.
     * Published in the {@code /topic/order-flow} payload.
     */
    public static class Tape {
        private boolean enabled = true;
        /** Z-score at or above which the tape counts as a burst (frontend amber; red at z≥3). */
        private double burstZ = 2.0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getBurstZ() { return burstZ; }
        public void setBurstZ(double v) { this.burstZ = v; }
    }

    /**
     * Big-print detector (UC-OF-BIGPRINT): flags AllLast prints at or above the rolling
     * size-distribution percentile (with an absolute floor), publishes events on
     * {@code /topic/big-prints} (rate-limited 1/sec/instrument) and a 5-min signed
     * big-print delta in the {@code /topic/order-flow} payload.
     */
    public static class BigPrint {
        private boolean enabled = true;
        /** Distribution percentile a print must reach to be flagged (0..1]. */
        private double percentile = 0.99;
        /** Absolute floor (contracts) — guards thin tape where the percentile collapses. */
        private int minSize = 10;
        /** Rolling window (minutes) of the print-size distribution. */
        private int windowMinutes = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getPercentile() { return percentile; }
        public void setPercentile(double v) { this.percentile = v; }
        public int getMinSize() { return minSize; }
        public void setMinSize(int v) { this.minSize = v; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int v) { this.windowMinutes = v; }
    }

    /** Footprint chart bars (UC-OF-011): clock-aligned bars of classified tick volume per price bucket. */
    public static class Footprint {
        /** Bar duration in minutes, aligned to the wall clock (10 → 14:00, 14:10, …). */
        private int barMinutes = 10;
        /**
         * Per-instrument price bucket size (points). A trade is attributed to the bucket
         * whose lower bound it falls into. Instruments not listed fall back to their
         * native tick size — which is usually far too granular for reading a footprint
         * (MNQ at 0.25 produces hundreds of one-lot levels).
         *
         * <p>MNQ lowered 5.0 → 2.0 (8 ticks) on 2026-06-11: research consensus for
         * NQ-class footprints is 4–10 ticks per row; 20-tick rows destroy diagonal
         * imbalance resolution. Bars persisted before the change have 5-point buckets.</p>
         */
        private Map<String, Double> bucketSize = new HashMap<>(Map.of(
            "MNQ", 2.0,   // 8 ticks
            "MCL", 0.05   // 5 ticks
        ));
        /** Max bars returned by the history endpoint. */
        private int historyMaxBars = 50;
        /**
         * Diagonal imbalance dominance ratio: buy at P vs sell one bucket lower (and
         * mirrored for sells). 3.0 = 300%, the industry default.
         */
        private double imbalanceRatio = 3.0;
        /**
         * Per-instrument minimum-volume filter: the larger cell of the diagonal pair
         * must hold at least this many contracts to flag (a 4-vs-1 pair must not).
         * MCL is lower because micro crude tape volume is a fraction of MNQ's.
         */
        private Map<String, Long> minCellVolume = new HashMap<>(Map.of(
            "MNQ", 20L,
            "MCL", 5L
        ));
        /** Fallback minimum-volume filter for instruments without an explicit entry. */
        private long defaultMinCellVolume = 20;

        public int getBarMinutes() { return barMinutes; }
        public void setBarMinutes(int v) { this.barMinutes = v; }
        public Map<String, Double> getBucketSize() { return bucketSize; }
        public void setBucketSize(Map<String, Double> v) { this.bucketSize = v; }
        public int getHistoryMaxBars() { return historyMaxBars; }
        public void setHistoryMaxBars(int v) { this.historyMaxBars = v; }
        public double getImbalanceRatio() { return imbalanceRatio; }
        public void setImbalanceRatio(double v) { this.imbalanceRatio = v; }
        public Map<String, Long> getMinCellVolume() { return minCellVolume; }
        public void setMinCellVolume(Map<String, Long> v) { this.minCellVolume = v; }
        public long getDefaultMinCellVolume() { return defaultMinCellVolume; }
        public void setDefaultMinCellVolume(long v) { this.defaultMinCellVolume = v; }

        /** Minimum-volume filter for an instrument, falling back to the default. */
        public long minCellVolumeFor(String instrument) {
            Long v = minCellVolume.get(instrument);
            return v != null ? v : defaultMinCellVolume;
        }
    }

    /**
     * Session volume profile (UC-OF-015): POC / VAH / VAL (70% value area) built from
     * internal 1m candles per RTH session, plus the naked-POC ladder.
     */
    public static class VolumeProfile {
        /**
         * Per-instrument price bucket size (points) for the session histogram.
         * Coarser than the footprint bucket — a session profile spans the whole
         * day's range.
         */
        private Map<String, Double> bucketSize = new HashMap<>(Map.of(
            "MNQ", 1.0,
            "MCL", 0.05
        ));
        /** Fallback bucket size for instruments without an explicit entry. */
        private double defaultBucketSize = 1.0;
        /** How many completed RTH sessions the naked-POC scan walks back. */
        private int nakedPocLookbackSessions = 10;
        /** Profiles are recomputed at most once per this many seconds (per instrument). */
        private int cacheSeconds = 60;

        public Map<String, Double> getBucketSize() { return bucketSize; }
        public void setBucketSize(Map<String, Double> v) { this.bucketSize = v; }
        public double getDefaultBucketSize() { return defaultBucketSize; }
        public void setDefaultBucketSize(double v) { this.defaultBucketSize = v; }
        public int getNakedPocLookbackSessions() { return nakedPocLookbackSessions; }
        public void setNakedPocLookbackSessions(int v) { this.nakedPocLookbackSessions = v; }
        public int getCacheSeconds() { return cacheSeconds; }
        public void setCacheSeconds(int v) { this.cacheSeconds = v; }

        /** Bucket size for an instrument, falling back to the default. */
        public double bucketSizeFor(String instrument) {
            Double v = bucketSize.get(instrument);
            return v != null && v > 0 ? v : defaultBucketSize;
        }
    }
}

package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

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
        /** Sigmoid-scale threshold: score ∈ [0,1]. 0.55 ≈ "two factors above baseline". */
        private double scoreThreshold = 0.55;
        /** MNQ: 40% ATR minimum filters 1-2 tick noise on a 15-25 pt ATR. */
        private double minPriceMoveFractionOfAtr = 0.4;
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
        /** Minimum confidence (0-100) for a cycle signal to be exposed (REST history + WebSocket). State machine is unaffected. */
        private int minConfidence = 70;

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

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getLookbackSeconds() { return lookbackSeconds; }
        public void setLookbackSeconds(int v) { this.lookbackSeconds = v; }
        public int getDedupSeconds() { return dedupSeconds; }
        public void setDedupSeconds(int v) { this.dedupSeconds = v; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double v) { this.minScore = v; }
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
}

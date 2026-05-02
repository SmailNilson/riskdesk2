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

    /** Tick-by-tick data subscription (reqTickByTickData). */
    public static class TickByTick {
        private boolean enabled = true;
        private List<String> instruments = List.of("MNQ", "MCL", "MGC", "E6");

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getInstruments() { return instruments; }
        public void setInstruments(List<String> instruments) { this.instruments = instruments; }
    }

    /** Market depth subscription (reqMarketDepth). */
    public static class Depth {
        private boolean enabled = true;
        private List<String> instruments = List.of("MNQ", "MCL", "MGC");
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
}

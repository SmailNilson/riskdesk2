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

    public TickByTick getTickByTick() { return tickByTick; }
    public void setTickByTick(TickByTick tickByTick) { this.tickByTick = tickByTick; }
    public Depth getDepth() { return depth; }
    public void setDepth(Depth depth) { this.depth = depth; }
    public TickLog getTickLog() { return tickLog; }
    public void setTickLog(TickLog tickLog) { this.tickLog = tickLog; }
    public DepthLog getDepthLog() { return depthLog; }
    public void setDepthLog(DepthLog depthLog) { this.depthLog = depthLog; }

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
}

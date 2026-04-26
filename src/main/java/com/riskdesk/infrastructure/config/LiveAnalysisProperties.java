package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.analysis.port.AnalysisConfigPort;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Live tri-layer analysis scheduler config. Bound from
 * {@code riskdesk.analysis.*} in application.properties.
 * <p>
 * Implements {@link AnalysisConfigPort} so application services depend on the
 * domain port rather than this infrastructure-side class — preserves the
 * hexagonal boundary documented in AGENTS.md (PR #270 round-3 review).
 */
@Component
@ConfigurationProperties(prefix = "riskdesk.analysis")
public class LiveAnalysisProperties implements AnalysisConfigPort {

    /** Master switch — when false the scheduler does not run at all. */
    private boolean schedulerEnabled = true;

    /** Instrument symbols to scan. Must match {@code Instrument} enum names. */
    private List<String> instruments = List.of("MNQ", "MCL", "MGC", "E6");

    /**
     * Timeframes to scan per instrument. Must match {@code Timeframe.label()}.
     * Default covers every timeframe the dashboard exposes so the read-only
     * {@code GET /api/analysis/latest} endpoint never returns 404 indefinitely
     * for a tab the user can select (PR #270 review).
     */
    private List<String> timeframes = List.of("5m", "10m", "1h", "1d");

    /** Polling interval in milliseconds. Default 15s. */
    private long pollIntervalMs = 15_000L;

    /** Days of verdict history to retain. Older rows are purged daily. */
    private int retentionDays = 30;

    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean v) { this.schedulerEnabled = v; }
    public List<String> getInstruments() { return instruments; }
    public void setInstruments(List<String> v) { this.instruments = v; }
    public List<String> getTimeframes() { return timeframes; }
    public void setTimeframes(List<String> v) { this.timeframes = v; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long v) { this.pollIntervalMs = v; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int v) { this.retentionDays = v; }
}

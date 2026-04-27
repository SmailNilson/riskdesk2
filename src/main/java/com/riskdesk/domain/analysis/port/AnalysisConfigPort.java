package com.riskdesk.domain.analysis.port;

import java.util.List;

/**
 * Domain-facing read view of the live-analysis runtime configuration.
 * <p>
 * Application services (the scheduler and the verdict facade) depend on this
 * abstraction; infrastructure provides the implementation backed by Spring
 * {@code @ConfigurationProperties}. Without this port, the application layer
 * would import {@code com.riskdesk.infrastructure.config.LiveAnalysisProperties}
 * directly, violating the {@code AGENTS.md} hexagonal contract that
 * "infrastructure details must not leak upward".
 * <p>
 * Methods are read-only and side-effect-free — runtime values can change as
 * the underlying property source is reloaded; callers must not cache the
 * returned collections beyond the call boundary.
 */
public interface AnalysisConfigPort {

    /** Master switch for the continuous scheduler. */
    boolean isSchedulerEnabled();

    /** Instrument symbols (matching {@code Instrument} enum names) to scan. */
    List<String> getInstruments();

    /** Timeframe labels (matching {@code Timeframe.label()}) to scan. */
    List<String> getTimeframes();

    /** Scheduler polling interval, milliseconds. */
    long getPollIntervalMs();

    /** Verdict-record retention, days. Older rows are purged daily. */
    int getRetentionDays();
}

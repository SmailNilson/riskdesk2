package com.riskdesk.application.externalsetup;

import java.util.Map;

/**
 * Read-only snapshot of the External Setup pipeline configuration.
 * Exposed by {@link ExternalSetupService} so the presentation layer never has to import
 * {@code ExternalSetupProperties} (ArchUnit forbids presentation → infrastructure).
 */
public record ExternalSetupStatusView(
    boolean enabled,
    boolean autoExecuteOnHighConfidence,
    long defaultTtlSeconds,
    Map<String, Long> ttlPerInstrumentSeconds,
    int rateLimitPerMinute,
    boolean hasDefaultBrokerAccount
) {
}

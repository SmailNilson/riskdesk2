package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.model.Instrument;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for the External Setup pipeline (Claude wakeup → SaaS validation).
 *
 * <pre>
 *   riskdesk.external-setup.enabled=true
 *   riskdesk.external-setup.api-token=&lt;random-32-char-token&gt;
 *   riskdesk.external-setup.default-ttl=PT5M
 *   riskdesk.external-setup.ttl.MNQ=PT3M
 *   riskdesk.external-setup.ttl.MGC=PT5M
 *   riskdesk.external-setup.ttl.MCL=PT5M
 *   riskdesk.external-setup.ttl.E6=PT8M
 *   riskdesk.external-setup.auto-execute-on-high-confidence=false
 *   riskdesk.external-setup.default-quantity=1
 *   riskdesk.external-setup.default-broker-account=&lt;ibkr-account&gt;
 *   riskdesk.external-setup.rate-limit-per-minute=12
 * </pre>
 */
@ConfigurationProperties(prefix = "riskdesk.external-setup")
public class ExternalSetupProperties {

    /** When false, all REST endpoints return 503. Defaults true. */
    private boolean enabled = true;

    /** Token required in the {@code X-Setup-Token} header for {@code POST /api/external-setups}. */
    private String apiToken;

    /** Default TTL when no instrument-specific override is configured. */
    private Duration defaultTtl = Duration.ofMinutes(5);

    /**
     * Per-instrument TTL overrides keyed by {@link Instrument} name.
     * Strings are bound by Spring to instrument-specific durations.
     */
    private Map<String, Duration> ttl = new HashMap<>();

    /**
     * When true, setups submitted with {@code confidence=HIGH} are auto-validated and
     * armed without user click. The user can still manually reject before the entry
     * order is dispatched (race window depends on broker latency).
     */
    private boolean autoExecuteOnHighConfidence = false;

    /** Default contract quantity when the user clicks Validate without specifying one. */
    private int defaultQuantity = 1;

    /** IBKR broker account id used when validating. Required when execution wiring is on. */
    private String defaultBrokerAccount;

    /**
     * Maximum POST submissions per minute per token (in-memory rate limit). Setups arriving
     * over this rate are answered with HTTP 429.
     */
    private int rateLimitPerMinute = 12;

    public Duration ttlFor(Instrument instrument) {
        if (instrument == null) {
            return defaultTtl;
        }
        Duration override = ttl.get(instrument.name());
        return override != null ? override : defaultTtl;
    }

    /** Convenience for tests. */
    public Map<Instrument, Duration> resolvedTtls() {
        EnumMap<Instrument, Duration> out = new EnumMap<>(Instrument.class);
        for (Instrument i : Instrument.values()) {
            out.put(i, ttlFor(i));
        }
        return out;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public Duration getDefaultTtl() { return defaultTtl; }
    public void setDefaultTtl(Duration defaultTtl) { this.defaultTtl = defaultTtl; }
    public Map<String, Duration> getTtl() { return ttl; }
    public void setTtl(Map<String, Duration> ttl) { this.ttl = ttl; }
    public boolean isAutoExecuteOnHighConfidence() { return autoExecuteOnHighConfidence; }
    public void setAutoExecuteOnHighConfidence(boolean v) { this.autoExecuteOnHighConfidence = v; }
    public int getDefaultQuantity() { return defaultQuantity; }
    public void setDefaultQuantity(int q) { this.defaultQuantity = q; }
    public String getDefaultBrokerAccount() { return defaultBrokerAccount; }
    public void setDefaultBrokerAccount(String acc) { this.defaultBrokerAccount = acc; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int n) { this.rateLimitPerMinute = n; }
}

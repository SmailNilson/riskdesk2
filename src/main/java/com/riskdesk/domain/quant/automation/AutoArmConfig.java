package com.riskdesk.domain.quant.automation;

import java.time.Duration;

/**
 * Pure config record consumed by {@link AutoArmEvaluator} and the auto-submit
 * scheduler. Defaults intentionally OFF at the property layer — see
 * {@code application.properties} keys {@code riskdesk.quant.auto-arm.*} and
 * {@code riskdesk.quant.auto-submit.*}.
 *
 * @param minScore                 minimum {@code QuantSnapshot.score()} (raw 7-gate, before
 *                                 structural modifier) required to arm
 * @param expireSeconds            how long after arming the decision auto-expires if not fired
 * @param autoSubmitDelaySeconds   delay between arm and automatic IBKR submission
 *                                 (0 = submit immediately, larger = longer cancel window)
 * @param cooldownSeconds          minimum seconds between two arms for the same instrument
 * @param sizePercentDefault       baseline position size (fraction of account, e.g. 0.005 = 0.5%)
 */
public record AutoArmConfig(
    int minScore,
    int expireSeconds,
    int autoSubmitDelaySeconds,
    int cooldownSeconds,
    double sizePercentDefault
) {
    public AutoArmConfig {
        if (minScore < 0) throw new IllegalArgumentException("minScore must be >= 0");
        if (expireSeconds <= 0) throw new IllegalArgumentException("expireSeconds must be > 0");
        if (autoSubmitDelaySeconds < 0) throw new IllegalArgumentException("autoSubmitDelaySeconds must be >= 0");
        if (cooldownSeconds < 0) throw new IllegalArgumentException("cooldownSeconds must be >= 0");
        if (sizePercentDefault <= 0.0 || sizePercentDefault > 1.0) {
            throw new IllegalArgumentException("sizePercentDefault must be in (0, 1]");
        }
    }

    public Duration expireDuration()       { return Duration.ofSeconds(expireSeconds); }
    public Duration autoSubmitDelay()      { return Duration.ofSeconds(autoSubmitDelaySeconds); }
    public Duration cooldownDuration()     { return Duration.ofSeconds(cooldownSeconds); }

    /** Conservative defaults used by tests and when no application property is wired. */
    public static AutoArmConfig defaults() {
        return new AutoArmConfig(7, 120, 30, 60, 0.005);
    }
}

package com.riskdesk.application.quant.automation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring-Boot binding for the {@code riskdesk.quant.auto-submit.*} keys.
 * Defaults: feature DISABLED with a 30 s cancel window.
 */
@ConfigurationProperties(prefix = "riskdesk.quant.auto-submit")
public class QuantAutoSubmitProperties {

    /**
     * Master switch. When false the scheduler runs but never calls
     * {@code submitEntryOrder} — auto-armed executions remain in
     * {@code PENDING_ENTRY_SUBMISSION} until the operator clicks Fire or
     * Cancel, or until they expire.
     */
    private boolean enabled = false;

    /**
     * Seconds between an auto-arm and the scheduled IBKR submission. 0 means
     * "submit on the very next scheduler tick"; the default 30 s gives the
     * operator a meaningful cancel window.
     */
    private int delaySeconds = 30;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getDelaySeconds() { return delaySeconds; }
    public void setDelaySeconds(int delaySeconds) { this.delaySeconds = delaySeconds; }
}

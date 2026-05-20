package com.riskdesk.application.quant.automation;

import com.riskdesk.domain.quant.automation.AutoArmConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring-Boot binding for the {@code riskdesk.quant.auto-arm.*} keys. Defaults
 * here mirror {@link AutoArmConfig#defaults()} so dropping the property block
 * from {@code application.properties} still produces a safe (disabled-but-sane)
 * config.
 */
@ConfigurationProperties(prefix = "riskdesk.quant.auto-arm")
public class QuantAutoArmProperties {

    /**
     * Master switch. When false the listener is wired but never creates an
     * execution — an extra safety latch on top of the missing-config defaults.
     */
    private boolean enabled = false;

    /** {@link AutoArmConfig#minScore()} — raw 7-gate threshold. */
    private int minScore = 7;

    /** {@link AutoArmConfig#expireSeconds()}. */
    private int expireSeconds = 120;

    /** {@link AutoArmConfig#cooldownSeconds()}. */
    private int cooldownSeconds = 60;

    /** {@link AutoArmConfig#sizePercentDefault()}. */
    private double sizePercentDefault = 0.005;

    /**
     * Broker account used when creating quant auto-arm executions. Required
     * when {@code enabled=true}; the service throws on startup if blank.
     */
    private String brokerAccountId = "";

    /**
     * Default contract quantity. The auto-arm path has no per-trade sizing
     * engine yet — the {@code sizePercent} on the decision is informational
     * (frontend) while the actual order size uses this constant.
     */
    private int defaultQuantity = 1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMinScore() { return minScore; }
    public void setMinScore(int minScore) { this.minScore = minScore; }

    public int getExpireSeconds() { return expireSeconds; }
    public void setExpireSeconds(int expireSeconds) { this.expireSeconds = expireSeconds; }

    public int getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }

    public double getSizePercentDefault() { return sizePercentDefault; }
    public void setSizePercentDefault(double sizePercentDefault) { this.sizePercentDefault = sizePercentDefault; }

    public String getBrokerAccountId() { return brokerAccountId; }
    public void setBrokerAccountId(String brokerAccountId) { this.brokerAccountId = brokerAccountId; }

    public int getDefaultQuantity() { return defaultQuantity; }
    public void setDefaultQuantity(int defaultQuantity) { this.defaultQuantity = defaultQuantity; }

    /**
     * @param autoSubmitDelaySeconds wired separately from
     *        {@code QuantAutoSubmitProperties} — the evaluator only needs the
     *        delay to compute {@code autoSubmitAt} on the published event.
     */
    public AutoArmConfig toConfig(int autoSubmitDelaySeconds) {
        return new AutoArmConfig(
            minScore,
            expireSeconds,
            autoSubmitDelaySeconds,
            cooldownSeconds,
            sizePercentDefault
        );
    }
}

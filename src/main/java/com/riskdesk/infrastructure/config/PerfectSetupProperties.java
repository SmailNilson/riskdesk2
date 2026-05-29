package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupThresholds;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration for the Perfect Setup order-flow confluence detector. Binds to
 * {@code riskdesk.perfect-setup.*}. Defaults mirror
 * {@link PerfectSetupThresholds#defaults()} so an empty property block still
 * yields a sane, advisory-only configuration.
 */
@Component
@ConfigurationProperties(prefix = "riskdesk.perfect-setup")
public class PerfectSetupProperties {

    /** Master switch for the scheduled evaluator + WebSocket/event emission. */
    private boolean enabled = true;

    /** Evaluation cadence in milliseconds. */
    private long evalIntervalMs = 5_000;

    /** Instruments to evaluate (must have order-flow / depth data). */
    private List<String> instruments = List.of("MNQ", "MGC", "MCL");

    // Confluence thresholds (see PerfectSetupThresholds) -----------------------
    private int armThreshold = 4;
    private int regimeMinConf = 70;
    private double icebergMinScore = 50.0;
    private int nearLevelTicks = 40;
    private double bbLow = 0.25;
    private double bbHigh = 0.75;
    private double absorptionClimaxMinScore = 8.0;
    private double flashReversalMinScore = 50.0;
    private double minRr = 2.0;
    private double slBufferAtrFraction = 0.5;
    private long armTtlSeconds = 900;
    private long cooldownSeconds = 300;

    // Lookback windows for the input read-services -----------------------------
    private int absorptionLookbackMinutes = 3;
    private int distributionLookbackMinutes = 10;
    private int cycleLookbackMinutes = 10;
    private int icebergLookbackMinutes = 30;
    private int icebergScanLimit = 25;

    /** Opt-in bridge to the auto-arm pipeline. */
    private AutoArm autoArm = new AutoArm();

    /** Builds the immutable domain thresholds from the bound properties. */
    public PerfectSetupThresholds toThresholds() {
        return new PerfectSetupThresholds(
            armThreshold, regimeMinConf, icebergMinScore, nearLevelTicks,
            bbLow, bbHigh, absorptionClimaxMinScore, flashReversalMinScore,
            minRr, slBufferAtrFraction, armTtlSeconds, cooldownSeconds);
    }

    public static class AutoArm {
        /** When true, an ARM transition creates a PENDING execution via QuantAutoArmService. */
        private boolean enabled = false;
        /** Fraction of account risk passed to the arm decision (0,1]. */
        private double sizePercent = 0.005;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getSizePercent() { return sizePercent; }
        public void setSizePercent(double sizePercent) { this.sizePercent = sizePercent; }
    }

    // Getters / setters --------------------------------------------------------
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getEvalIntervalMs() { return evalIntervalMs; }
    public void setEvalIntervalMs(long evalIntervalMs) { this.evalIntervalMs = evalIntervalMs; }
    public List<String> getInstruments() { return instruments; }
    public void setInstruments(List<String> instruments) { this.instruments = instruments; }
    public int getArmThreshold() { return armThreshold; }
    public void setArmThreshold(int armThreshold) { this.armThreshold = armThreshold; }
    public int getRegimeMinConf() { return regimeMinConf; }
    public void setRegimeMinConf(int regimeMinConf) { this.regimeMinConf = regimeMinConf; }
    public double getIcebergMinScore() { return icebergMinScore; }
    public void setIcebergMinScore(double icebergMinScore) { this.icebergMinScore = icebergMinScore; }
    public int getNearLevelTicks() { return nearLevelTicks; }
    public void setNearLevelTicks(int nearLevelTicks) { this.nearLevelTicks = nearLevelTicks; }
    public double getBbLow() { return bbLow; }
    public void setBbLow(double bbLow) { this.bbLow = bbLow; }
    public double getBbHigh() { return bbHigh; }
    public void setBbHigh(double bbHigh) { this.bbHigh = bbHigh; }
    public double getAbsorptionClimaxMinScore() { return absorptionClimaxMinScore; }
    public void setAbsorptionClimaxMinScore(double v) { this.absorptionClimaxMinScore = v; }
    public double getFlashReversalMinScore() { return flashReversalMinScore; }
    public void setFlashReversalMinScore(double v) { this.flashReversalMinScore = v; }
    public double getMinRr() { return minRr; }
    public void setMinRr(double minRr) { this.minRr = minRr; }
    public double getSlBufferAtrFraction() { return slBufferAtrFraction; }
    public void setSlBufferAtrFraction(double v) { this.slBufferAtrFraction = v; }
    public long getArmTtlSeconds() { return armTtlSeconds; }
    public void setArmTtlSeconds(long armTtlSeconds) { this.armTtlSeconds = armTtlSeconds; }
    public long getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(long cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    public int getAbsorptionLookbackMinutes() { return absorptionLookbackMinutes; }
    public void setAbsorptionLookbackMinutes(int v) { this.absorptionLookbackMinutes = v; }
    public int getDistributionLookbackMinutes() { return distributionLookbackMinutes; }
    public void setDistributionLookbackMinutes(int v) { this.distributionLookbackMinutes = v; }
    public int getCycleLookbackMinutes() { return cycleLookbackMinutes; }
    public void setCycleLookbackMinutes(int v) { this.cycleLookbackMinutes = v; }
    public int getIcebergLookbackMinutes() { return icebergLookbackMinutes; }
    public void setIcebergLookbackMinutes(int v) { this.icebergLookbackMinutes = v; }
    public int getIcebergScanLimit() { return icebergScanLimit; }
    public void setIcebergScanLimit(int v) { this.icebergScanLimit = v; }
    public AutoArm getAutoArm() { return autoArm; }
    public void setAutoArm(AutoArm autoArm) { this.autoArm = autoArm; }
}

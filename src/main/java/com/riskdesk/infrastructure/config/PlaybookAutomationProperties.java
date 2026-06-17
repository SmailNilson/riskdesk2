package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "riskdesk.playbook.automation")
public class PlaybookAutomationProperties {
    private boolean enabled = true;
    private List<String> instruments = new ArrayList<>(List.of("MCL", "MGC", "MNQ", "E6"));
    private List<String> timeframes = new ArrayList<>(List.of("5m", "10m", "1h"));

    /**
     * Confirmation STOP entries are submitted as STOP-LIMIT, capping how far past the zone-break
     * trigger the fill may slip (a plain STOP becomes a market order and slips uncapped on a fast
     * break). The limit is set {@code band × ATR} beyond the trigger; ATR is recovered from the
     * plan's stop distance ({@code |entry − SL| = 1.5 × ATR}). If price blows through the band
     * without trading there, the order simply does not fill (a MISSED — the desired "don't chase"
     * behaviour). Set to {@code 0} to revert to a plain market STOP (no cap).
     */
    private double stopLimitBandAtr = 0.30;

    public boolean isEnabled() {
        return enabled;
    }

    public double getStopLimitBandAtr() {
        return stopLimitBandAtr;
    }

    public void setStopLimitBandAtr(double stopLimitBandAtr) {
        this.stopLimitBandAtr = stopLimitBandAtr;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getInstruments() {
        return instruments;
    }

    public void setInstruments(List<String> instruments) {
        this.instruments = instruments == null ? new ArrayList<>() : instruments;
    }

    public List<String> getTimeframes() {
        return timeframes;
    }

    public void setTimeframes(List<String> timeframes) {
        this.timeframes = timeframes == null ? new ArrayList<>() : timeframes;
    }
}

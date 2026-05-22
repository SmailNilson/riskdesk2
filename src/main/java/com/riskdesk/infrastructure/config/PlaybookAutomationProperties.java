package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "riskdesk.playbook.automation")
public class PlaybookAutomationProperties {
    private boolean enabled = true;
    private List<String> instruments = new ArrayList<>(List.of("MCL", "MGC", "MNQ", "E6"));
    private List<String> timeframes = new ArrayList<>(List.of("5m", "10m", "1h"));

    public boolean isEnabled() {
        return enabled;
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

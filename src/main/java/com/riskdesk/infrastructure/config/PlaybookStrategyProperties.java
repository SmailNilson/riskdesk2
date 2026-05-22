package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "riskdesk.playbook")
public class PlaybookStrategyProperties {

    private boolean enabled = false;
    private List<String> instruments = List.of("MNQ", "MCL", "MGC");
    private List<String> timeframes = List.of("5m", "10m");
    private BigDecimal initialEquity = BigDecimal.valueOf(10000);
    private BigDecimal maxDailyLossUsd = BigDecimal.valueOf(500.0);
    private String brokerAccountId = "playbook-default";

    public enum PreflightMode { OFF, PORTFOLIO_HEURISTIC, WHATIF }
    private PreflightMode preflightMode = PreflightMode.PORTFOLIO_HEURISTIC;
    private BigDecimal preflightMarginPercent = new BigDecimal("0.15");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getInstruments() { return instruments; }
    public void setInstruments(List<String> instruments) { this.instruments = instruments; }

    public List<String> getTimeframes() { return timeframes; }
    public void setTimeframes(List<String> timeframes) { this.timeframes = timeframes; }

    public BigDecimal getInitialEquity() { return initialEquity; }
    public void setInitialEquity(BigDecimal initialEquity) { this.initialEquity = initialEquity; }

    public BigDecimal getMaxDailyLossUsd() { return maxDailyLossUsd; }
    public void setMaxDailyLossUsd(BigDecimal maxDailyLossUsd) { this.maxDailyLossUsd = maxDailyLossUsd; }

    public String getBrokerAccountId() { return brokerAccountId; }
    public void setBrokerAccountId(String brokerAccountId) { this.brokerAccountId = brokerAccountId; }

    public PreflightMode getPreflightMode() { return preflightMode; }
    public void setPreflightMode(PreflightMode preflightMode) { this.preflightMode = preflightMode; }

    public BigDecimal getPreflightMarginPercent() { return preflightMarginPercent; }
    public void setPreflightMarginPercent(BigDecimal preflightMarginPercent) { this.preflightMarginPercent = preflightMarginPercent; }
}

package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "playbook_automation_states")
@IdClass(PlaybookAutomationStateId.class)
public class PlaybookAutomationStateEntity {

    @Id
    @Column(nullable = false, length = 20)
    private String instrument;

    @Id
    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column
    private Integer paperThreshold;

    @Column
    private Integer liveThreshold;

    @Column
    private Boolean paperEnabled;

    @Column
    private Boolean autoExecutionEnabled;

    @Column
    private Integer configuredOrderQty;

    @Column(length = 64)
    private String brokerAccountId;

    @Column(length = 48)
    private String armedProfile;

    @Column
    private Boolean scalpProfileValidated;

    @Column(nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant updatedAt;

    public String getInstrument() {
        return instrument;
    }

    public void setInstrument(String instrument) {
        this.instrument = instrument;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public Integer getPaperThreshold() {
        return paperThreshold;
    }

    public void setPaperThreshold(Integer paperThreshold) {
        this.paperThreshold = paperThreshold;
    }

    public Integer getLiveThreshold() {
        return liveThreshold;
    }

    public void setLiveThreshold(Integer liveThreshold) {
        this.liveThreshold = liveThreshold;
    }

    public Boolean getPaperEnabled() {
        return paperEnabled;
    }

    public void setPaperEnabled(Boolean paperEnabled) {
        this.paperEnabled = paperEnabled;
    }

    public Boolean getAutoExecutionEnabled() {
        return autoExecutionEnabled;
    }

    public void setAutoExecutionEnabled(Boolean autoExecutionEnabled) {
        this.autoExecutionEnabled = autoExecutionEnabled;
    }

    public Integer getConfiguredOrderQty() {
        return configuredOrderQty;
    }

    public void setConfiguredOrderQty(Integer configuredOrderQty) {
        this.configuredOrderQty = configuredOrderQty;
    }

    public String getBrokerAccountId() {
        return brokerAccountId;
    }

    public void setBrokerAccountId(String brokerAccountId) {
        this.brokerAccountId = brokerAccountId;
    }

    public String getArmedProfile() {
        return armedProfile;
    }

    public void setArmedProfile(String armedProfile) {
        this.armedProfile = armedProfile;
    }

    public Boolean getScalpProfileValidated() {
        return scalpProfileValidated;
    }

    public void setScalpProfileValidated(Boolean scalpProfileValidated) {
        this.scalpProfileValidated = scalpProfileValidated;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

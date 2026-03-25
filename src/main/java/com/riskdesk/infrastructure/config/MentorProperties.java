package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "riskdesk.mentor")
public class MentorProperties {

    private boolean enabled = true;
    private String apiKey = "";
    private String model = "gemini-3.1-pro-preview";
    private String endpoint = "https://generativelanguage.googleapis.com";
    private int timeoutMs = 30000;
    private boolean persistAudits = true;
    private boolean memoryEnabled = true;
    private int memoryTopK = 3;
    private int memorySearchWindow = 200;
    private boolean embeddingsEnabled = true;
    private String embeddingsModel = "gemini-embedding-001";
    private int embeddingDimensions = 768;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isPersistAudits() {
        return persistAudits;
    }

    public void setPersistAudits(boolean persistAudits) {
        this.persistAudits = persistAudits;
    }

    public boolean isMemoryEnabled() {
        return memoryEnabled;
    }

    public void setMemoryEnabled(boolean memoryEnabled) {
        this.memoryEnabled = memoryEnabled;
    }

    public int getMemoryTopK() {
        return memoryTopK;
    }

    public void setMemoryTopK(int memoryTopK) {
        this.memoryTopK = memoryTopK;
    }

    public int getMemorySearchWindow() {
        return memorySearchWindow;
    }

    public void setMemorySearchWindow(int memorySearchWindow) {
        this.memorySearchWindow = memorySearchWindow;
    }

    public boolean isEmbeddingsEnabled() {
        return embeddingsEnabled;
    }

    public void setEmbeddingsEnabled(boolean embeddingsEnabled) {
        this.embeddingsEnabled = embeddingsEnabled;
    }

    public String getEmbeddingsModel() {
        return embeddingsModel;
    }

    public void setEmbeddingsModel(String embeddingsModel) {
        this.embeddingsModel = embeddingsModel;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int embeddingDimensions) {
        this.embeddingDimensions = embeddingDimensions;
    }
}

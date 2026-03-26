package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deduplicates alerts by key within a configurable cooldown window.
 * Extracted from the monolithic AlertService deduplication logic.
 */
public class AlertDeduplicator {

    private final long cooldownSeconds;
    private final Map<String, Instant> lastFired = new ConcurrentHashMap<>();

    public AlertDeduplicator(long cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public boolean isDuplicate(Alert alert) {
        Instant last = lastFired.get(alert.key());
        if (last == null) return false;
        return Instant.now().isBefore(last.plusSeconds(cooldownSeconds));
    }

    public void markFired(Alert alert) {
        lastFired.put(alert.key(), Instant.now());
    }

    public boolean shouldFire(Alert alert) {
        if (isDuplicate(alert)) return false;
        markFired(alert);
        return true;
    }

    /**
     * Rule 2 — Dynamic Cooldown: uses the provided cooldown instead of the default.
     * Call with {@code Timeframe.fromLabel(tf).periodSeconds()} so that a 10m alert
     * is blocked for 10 minutes and an H1 alert for 60 minutes.
     */
    public boolean shouldFire(Alert alert, long cooldownSeconds) {
        Instant last = lastFired.get(alert.key());
        if (last != null && Instant.now().isBefore(last.plusSeconds(cooldownSeconds))) return false;
        lastFired.put(alert.key(), Instant.now());
        return true;
    }
}

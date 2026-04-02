package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlertDeduplicatorTest {

    private Alert makeAlert(String key) {
        return new Alert(key, AlertSeverity.INFO, "test message", AlertCategory.RISK, null);
    }

    @Test
    void firstAlert_isNotDuplicate() {
        AlertDeduplicator dedup = new AlertDeduplicator(300);
        Alert alert = makeAlert("risk:margin");

        assertFalse(dedup.isDuplicate(alert));
    }

    @Test
    void sameKeyWithinCooldown_isDuplicate() {
        AlertDeduplicator dedup = new AlertDeduplicator(300);
        Alert alert = makeAlert("risk:margin");

        dedup.markFired(alert);

        assertTrue(dedup.isDuplicate(alert));
    }

    @Test
    void differentKeys_areNotDuplicates() {
        AlertDeduplicator dedup = new AlertDeduplicator(300);
        Alert alert1 = makeAlert("risk:margin");
        Alert alert2 = makeAlert("risk:concentration:1");

        dedup.markFired(alert1);

        assertFalse(dedup.isDuplicate(alert2));
    }

    @Test
    void shouldFire_returnsTrueFirstTime_falseWithinCooldown() {
        AlertDeduplicator dedup = new AlertDeduplicator(300);
        Alert alert = makeAlert("ema:golden:MCL:10m");

        assertTrue(dedup.shouldFire(alert), "First fire should return true");
        assertFalse(dedup.shouldFire(alert), "Second fire within cooldown should return false");
    }

    @Test
    void snooze_blocksFutureAlertsForDuration() {
        AlertDeduplicator dedup = new AlertDeduplicator(300);
        Alert alert = makeAlert("ema:golden:MCL:10m");

        dedup.snooze(alert.key(), 60);

        assertFalse(dedup.shouldFire(alert), "Alert should be blocked while snoozed");
        assertTrue(dedup.isDuplicate(alert), "isDuplicate should return true while snoozed");
    }

    @Test
    void shouldFire_afterCooldownExpires_returnsTrue() throws Exception {
        // Use a very short cooldown (1 second) to test expiry
        AlertDeduplicator dedup = new AlertDeduplicator(1);
        Alert alert = makeAlert("test:key");

        assertTrue(dedup.shouldFire(alert));

        // Wait for cooldown to expire
        Thread.sleep(1100);

        assertTrue(dedup.shouldFire(alert), "Should fire again after cooldown expires");
    }
}

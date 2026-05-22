package com.riskdesk.domain.playbook.automation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybookAutomationStateTest {

    @Test
    void initial_defaultsToPaperOnlyAndOneContract() {
        PlaybookAutomationState state = PlaybookAutomationState.initial("MCL", "10m");

        assertEquals("MCL", state.instrument());
        assertEquals("10m", state.timeframe());
        assertEquals(PlaybookAutomationState.DEFAULT_PAPER_THRESHOLD, state.paperThreshold());
        assertEquals(PlaybookAutomationState.DEFAULT_LIVE_THRESHOLD, state.liveThreshold());
        assertTrue(state.paperEnabled(), "Auto-simulation should default on for paper tracking");
        assertFalse(state.autoExecutionEnabled(), "Auto-IBKR must default off");
        assertEquals(PlaybookAutomationState.DEFAULT_ORDER_QTY, state.configuredOrderQty());
    }

    @Test
    void constructor_clampsInvalidThresholdsAndQuantityToSafeDefaults() {
        Instant updatedAt = Instant.parse("2026-05-22T00:00:00Z");

        PlaybookAutomationState state = new PlaybookAutomationState(
            "MNQ", "5m",
            -1, 99,
            true, true,
            0,
            "DU123",
            updatedAt
        );

        assertEquals(PlaybookAutomationState.DEFAULT_PAPER_THRESHOLD, state.paperThreshold());
        assertEquals(PlaybookAutomationState.DEFAULT_LIVE_THRESHOLD, state.liveThreshold());
        assertEquals(PlaybookAutomationState.DEFAULT_ORDER_QTY, state.configuredOrderQty());
        assertEquals(updatedAt, state.updatedAt());
    }

    @Test
    void withSettings_preservesUnspecifiedValuesAndNormalizesBlankBrokerAccount() {
        Instant updatedAt = Instant.parse("2026-05-22T00:00:00Z");
        PlaybookAutomationState state = new PlaybookAutomationState(
            "MGC", "1h",
            3, 6,
            true, false,
            2,
            "DU123",
            updatedAt
        );

        PlaybookAutomationState changed = state.withSettings(null, true, null, "   ");

        assertTrue(changed.paperEnabled());
        assertTrue(changed.autoExecutionEnabled());
        assertEquals(2, changed.configuredOrderQty());
        assertNull(changed.brokerAccountId());
        assertEquals(3, changed.paperThreshold());
        assertEquals(6, changed.liveThreshold());
    }

    @Test
    void constructor_rejectsBlankIdentityFields() {
        assertThrows(IllegalArgumentException.class, () ->
            new PlaybookAutomationState("", "10m", 4, 5, true, false, 1, null, Instant.now()));
        assertThrows(IllegalArgumentException.class, () ->
            new PlaybookAutomationState("MCL", " ", 4, 5, true, false, 1, null, Instant.now()));
    }
}

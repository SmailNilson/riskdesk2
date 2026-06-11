package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.strategy.WtxStrategyService;
import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@code PUT /api/wtx/state/{i}/{tf}/session-filter} — delegation and the strict
 * true/false parse (a malformed value must 400, never silently flip the gate on a live panel).
 */
class WtxStrategyControllerSessionFilterTest {

    private final WtxStrategyService service = mock(WtxStrategyService.class);
    private final WtxStrategyController controller = new WtxStrategyController(service);

    private WtxStrategyState stubState(String timeframe) {
        WtxStrategyState state = WtxStrategyState.initial("MNQ", timeframe, BigDecimal.valueOf(10_000));
        when(service.updateSessionFilter(eq("MNQ"), eq(timeframe), eq(false))).thenReturn(state);
        lenient().when(service.getMaxDailyLossUsd()).thenReturn(BigDecimal.valueOf(500));
        lenient().when(service.effectiveConfig(anyString(), anyString())).thenReturn(WtxConfig.defaults());
        lenient().when(service.currentSwingBias(anyString(), anyString())).thenReturn(null);
        lenient().when(service.currentRegime(anyString(), anyString())).thenReturn(null);
        lenient().when(service.effectiveStop(state)).thenReturn(null);
        return state;
    }

    @Test
    void booleanBody_delegatesToTheService() {
        stubState("10m-z35");

        ResponseEntity<Map<String, Object>> resp =
                controller.updateSessionFilter("MNQ", "10m-z35", Map.of("enabled", false));

        assertEquals(200, resp.getStatusCode().value());
        verify(service).updateSessionFilter("MNQ", "10m-z35", false);
        // The view must expose the effective gate — the panel's Session button reads it
        // (without it the toggle renders OFF regardless of the engine state).
        assertEquals(false, resp.getBody().get("sessionFilterEnabled"));
    }

    @Test
    void stringTrueFalse_isAccepted() {
        stubState("10m");

        ResponseEntity<Map<String, Object>> resp =
                controller.updateSessionFilter("MNQ", "10m", Map.of("enabled", "false"));

        assertEquals(200, resp.getStatusCode().value());
        verify(service).updateSessionFilter("MNQ", "10m", false);
    }

    @Test
    void malformedValue_returns400_withoutTouchingTheService() {
        assertEquals(400, controller.updateSessionFilter("MNQ", "10m", Map.of("enabled", "tru")).getStatusCode().value());
        assertEquals(400, controller.updateSessionFilter("MNQ", "10m", Map.of("enabled", 1)).getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void missingBodyOrField_returns400() {
        Map<String, Object> empty = new HashMap<>();
        assertEquals(400, controller.updateSessionFilter("MNQ", "10m", null).getStatusCode().value());
        assertEquals(400, controller.updateSessionFilter("MNQ", "10m", empty).getStatusCode().value());
        verifyNoInteractions(service);
    }
}

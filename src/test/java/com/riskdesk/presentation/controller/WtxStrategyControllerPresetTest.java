package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.strategy.WtxStrategyService;
import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.domain.engine.strategy.wtx.WtxParamOverride;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
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
 * Tests for the named-preset endpoint ({@code PUT /api/wtx/state/{i}/{tf}/preset}) — delegation to
 * the service for known presets, 400 on unknown/missing names. Lightweight Mockito style.
 */
class WtxStrategyControllerPresetTest {

    private final WtxStrategyService service = mock(WtxStrategyService.class);
    private final WtxStrategyController controller = new WtxStrategyController(service);

    private void stubViewCollaborators(WtxStrategyState state) {
        when(service.applyPreset(eq("MNQ"), eq("10m"), eq(WtxParamOverride.TOP_TRAIN_Z35))).thenReturn(state);
        lenient().when(service.getMaxDailyLossUsd()).thenReturn(BigDecimal.valueOf(500));
        lenient().when(service.effectiveConfig(anyString(), anyString())).thenReturn(WtxConfig.defaults());
        lenient().when(service.currentSwingBias(anyString(), anyString())).thenReturn(null);
        lenient().when(service.currentRegime(anyString(), anyString())).thenReturn(null);
        lenient().when(service.effectiveStop(state)).thenReturn(null);
    }

    @Test
    void knownPreset_appliesAndReturnsState() {
        WtxStrategyState state = WtxStrategyState.initial("MNQ", "10m", BigDecimal.valueOf(10_000));
        stubViewCollaborators(state);

        ResponseEntity<Map<String, Object>> resp =
                controller.applyPreset("MNQ", "10m", Map.of("preset", "top-train-z35"));

        assertEquals(200, resp.getStatusCode().value());
        verify(service).applyPreset("MNQ", "10m", WtxParamOverride.TOP_TRAIN_Z35);
        assertEquals("MNQ", resp.getBody().get("instrument"));
    }

    @Test
    void unknownPreset_returns400_withoutTouchingTheService() {
        ResponseEntity<Map<String, Object>> resp =
                controller.applyPreset("MNQ", "10m", Map.of("preset", "nope"));
        assertEquals(400, resp.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void missingBodyOrName_returns400() {
        assertEquals(400, controller.applyPreset("MNQ", "10m", null).getStatusCode().value());
        assertEquals(400, controller.applyPreset("MNQ", "10m", Map.of()).getStatusCode().value());
        verifyNoInteractions(service);
    }
}

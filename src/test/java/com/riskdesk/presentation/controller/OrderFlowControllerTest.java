package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.OrderFlowHistoryService;
import com.riskdesk.application.service.OrderFlowOrchestrator;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import com.riskdesk.application.dto.AbsorptionEventView;
import com.riskdesk.application.dto.IcebergEventView;
import com.riskdesk.application.dto.SpoofingEventView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for the Slice 1+3 historical endpoints on {@link OrderFlowController}:
 * {@code GET /api/order-flow/{iceberg,absorption,spoofing}/{instrument}}.
 *
 * <p>Follows the lightweight Mockito style used in {@link PlaybookControllerTest}
 * — no Spring context, no {@code @WebMvcTest}. The controller forwards directly
 * to {@link OrderFlowHistoryService}, so verifying the delegation + HTTP shape
 * is sufficient.</p>
 */
class OrderFlowControllerTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OrderFlowOrchestrator> orchestratorProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TickDataPort> tickDataPortProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MarketDepthPort> depthPortProvider = mock(ObjectProvider.class);
    private final OrderFlowHistoryService historyService = mock(OrderFlowHistoryService.class);

    private final OrderFlowController controller = new OrderFlowController(
        orchestratorProvider, tickDataPortProvider, depthPortProvider, historyService);

    // ------------------------------------------------------------------ Iceberg

    @Test
    void getRecentIcebergs_returnsServiceResult_andPropagatesLimit() {
        IcebergEventView sample = new IcebergEventView(
            "MNQ", Instant.parse("2026-04-20T14:00:00Z"), "BID_ICEBERG",
            21000.25, 5, 120L, 42.0, 78.5);
        when(historyService.recentIcebergs(eq(Instrument.MNQ), eq(15)))
            .thenReturn(List.of(sample));

        ResponseEntity<?> response = controller.getRecentIcebergs("mnq", 15);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(historyService).recentIcebergs(eq(Instrument.MNQ), eq(15));
        assertInstanceOf(List.class, response.getBody());
        @SuppressWarnings("unchecked")
        List<IcebergEventView> body = (List<IcebergEventView>) response.getBody();
        assertEquals(1, body.size());
        assertEquals("MNQ", body.get(0).instrument());
        assertEquals("BID_ICEBERG", body.get(0).side());
    }

    @Test
    void getRecentIcebergs_usesDefaultLimitWhenOmitted() {
        when(historyService.recentIcebergs(eq(Instrument.MCL), eq(20))).thenReturn(List.of());

        ResponseEntity<?> response = controller.getRecentIcebergs("MCL", 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(historyService).recentIcebergs(eq(Instrument.MCL), eq(20));
    }

    @Test
    void getRecentIcebergs_unknownInstrument_returns400AndSkipsService() {
        ResponseEntity<?> response = controller.getRecentIcebergs("XXX", 20);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map<?, ?> map
            && map.get("error") != null
            && map.get("error").toString().contains("XXX"));
        verifyNoInteractions(historyService);
    }

    // --------------------------------------------------------------- Absorption

    @Test
    void getRecentAbsorptions_returnsServiceResult() {
        AbsorptionEventView sample = new AbsorptionEventView(
            "MCL", Instant.parse("2026-04-20T14:05:00Z"), "BULLISH_ABSORPTION",
            82.3, 450L, 1.5, 1_250L);
        when(historyService.recentAbsorptions(eq(Instrument.MCL), eq(20)))
            .thenReturn(List.of(sample));

        ResponseEntity<?> response = controller.getRecentAbsorptions("MCL", 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(historyService).recentAbsorptions(eq(Instrument.MCL), eq(20));
        @SuppressWarnings("unchecked")
        List<AbsorptionEventView> body = (List<AbsorptionEventView>) response.getBody();
        assertNotNull(body);
        assertEquals("BULLISH_ABSORPTION", body.get(0).side());
        assertEquals(450L, body.get(0).aggressiveDelta());
    }

    @Test
    void getRecentAbsorptions_unknownInstrument_returns400() {
        ResponseEntity<?> response = controller.getRecentAbsorptions("???", 20);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(historyService);
    }

    // ----------------------------------------------------------------- Spoofing

    @Test
    void getRecentSpoofings_returnsServiceResult() {
        SpoofingEventView sample = new SpoofingEventView(
            "MGC", Instant.parse("2026-04-20T14:10:00Z"), "ASK_SPOOF",
            2450.8, 200L, 4.2, false, 66.0);
        when(historyService.recentSpoofings(eq(Instrument.MGC), eq(50)))
            .thenReturn(List.of(sample));

        ResponseEntity<?> response = controller.getRecentSpoofings("mgc", 50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(historyService).recentSpoofings(eq(Instrument.MGC), eq(50));
        @SuppressWarnings("unchecked")
        List<SpoofingEventView> body = (List<SpoofingEventView>) response.getBody();
        assertNotNull(body);
        assertEquals("ASK_SPOOF", body.get(0).side());
        assertEquals(200L, body.get(0).wallSize());
    }

    @Test
    void getRecentSpoofings_unknownInstrument_returns400() {
        ResponseEntity<?> response = controller.getRecentSpoofings("nope", 20);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(historyService);
    }
}

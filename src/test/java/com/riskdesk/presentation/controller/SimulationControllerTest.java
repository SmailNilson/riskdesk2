package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.TradeSimulationView;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Lightweight controller test for {@link SimulationController} — mirrors the
 * pattern used in {@code OrderFlowControllerTest}: no Spring context, plain
 * Mockito + direct instantiation. We only verify that the controller delegates
 * to {@link TradeSimulationRepositoryPort} and maps the domain aggregate to the
 * {@link TradeSimulationView} DTO correctly.
 */
class SimulationControllerTest {

    private final TradeSimulationRepositoryPort port = mock(TradeSimulationRepositoryPort.class);
    private final SimulationController controller = new SimulationController(port);

    @Test
    void recent_delegatesToPort_andMapsToView() {
        TradeSimulation sim = sample(1L, 100L, ReviewType.SIGNAL, "MNQ", "LONG",
            TradeSimulationStatus.ACTIVE);
        when(port.findRecent(eq(25))).thenReturn(List.of(sim));

        List<TradeSimulationView> result = controller.recent(25);

        verify(port).findRecent(eq(25));
        assertEquals(1, result.size());
        TradeSimulationView view = result.get(0);
        assertEquals(100L, view.reviewId());
        assertEquals(ReviewType.SIGNAL, view.reviewType());
        assertEquals("MNQ", view.instrument());
        assertEquals("LONG", view.action());
        assertEquals("ACTIVE", view.simulationStatus());
    }

    @Test
    void byInstrument_upperCasesAndDelegates() {
        TradeSimulation sim = sample(2L, 200L, ReviewType.SIGNAL, "MCL", "SHORT",
            TradeSimulationStatus.WIN);
        when(port.findByInstrument(eq("MCL"), eq(10))).thenReturn(List.of(sim));

        List<TradeSimulationView> result = controller.byInstrument("mcl", 10);

        verify(port).findByInstrument(eq("MCL"), eq(10));
        assertEquals(1, result.size());
        assertEquals("WIN", result.get(0).simulationStatus());
    }

    @Test
    void byInstrument_blank_returnsEmptyWithoutCallingPort() {
        List<TradeSimulationView> result = controller.byInstrument(" ", 10);

        assertEquals(0, result.size());
        verifyNoInteractions(port);
    }

    @Test
    void byReview_foundSignal_returnsOkView() {
        TradeSimulation sim = sample(3L, 300L, ReviewType.SIGNAL, "MGC", "LONG",
            TradeSimulationStatus.PENDING_ENTRY);
        when(port.findByReviewId(eq(300L), eq(ReviewType.SIGNAL))).thenReturn(Optional.of(sim));

        ResponseEntity<TradeSimulationView> response = controller.byReview(300L, "SIGNAL");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(300L, response.getBody().reviewId());
        assertEquals(ReviewType.SIGNAL, response.getBody().reviewType());
    }

    @Test
    void byReview_defaultsToSignalWhenTypeOmitted() {
        TradeSimulation sim = sample(4L, 400L, ReviewType.SIGNAL, "MCL", "SHORT",
            TradeSimulationStatus.LOSS);
        when(port.findByReviewId(eq(400L), eq(ReviewType.SIGNAL))).thenReturn(Optional.of(sim));

        ResponseEntity<TradeSimulationView> response = controller.byReview(400L, "SIGNAL");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(port).findByReviewId(eq(400L), eq(ReviewType.SIGNAL));
    }

    @Test
    void byReview_auditType_delegatesWithAudit() {
        TradeSimulation sim = sample(5L, 500L, ReviewType.AUDIT, "MNQ", "LONG",
            TradeSimulationStatus.ACTIVE);
        when(port.findByReviewId(eq(500L), eq(ReviewType.AUDIT))).thenReturn(Optional.of(sim));

        ResponseEntity<TradeSimulationView> response = controller.byReview(500L, "audit");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ReviewType.AUDIT, response.getBody().reviewType());
    }

    @Test
    void byReview_notFound_returns404() {
        when(port.findByReviewId(eq(999L), eq(ReviewType.SIGNAL))).thenReturn(Optional.empty());

        ResponseEntity<TradeSimulationView> response = controller.byReview(999L, "SIGNAL");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void byReview_badType_returns400() {
        ResponseEntity<TradeSimulationView> response = controller.byReview(1L, "bogus");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(port);
    }

    private TradeSimulation sample(Long id, long reviewId, ReviewType type,
                                    String instrument, String action,
                                    TradeSimulationStatus status) {
        return new TradeSimulation(
            id,
            reviewId,
            type,
            instrument,
            action,
            status,
            null,
            null,
            BigDecimal.ZERO,
            null,
            null,
            null,
            Instant.parse("2026-04-20T10:00:00Z")
        );
    }
}

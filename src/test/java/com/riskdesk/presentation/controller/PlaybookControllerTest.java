package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.AgentOrchestratorService;
import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.application.service.PlaybookService;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@link PlaybookController} — specifically the full-playbook
 * endpoint which previously passed {@code BigDecimal.ONE} to the orchestrator
 * instead of the real ATR, corrupting sizing and trap-detection downstream.
 */
class PlaybookControllerTest {

    private final PlaybookService playbookService = mock(PlaybookService.class);
    private final AgentOrchestratorService orchestratorService = mock(AgentOrchestratorService.class);
    private final IndicatorService indicatorService = mock(IndicatorService.class);

    private final PlaybookController controller = new PlaybookController(
        playbookService, orchestratorService, indicatorService);

    @Test
    void getFullPlaybook_computesAtr_andPassesItToOrchestrator() {
        // Arrange: computeAtr returns a non-trivial ATR (2.50)
        PlaybookEvaluation playbook = emptyPlaybook();
        BigDecimal realAtr = new BigDecimal("2.50");

        when(playbookService.evaluate(eq(Instrument.MCL), eq("10m"))).thenReturn(playbook);
        // IndicatorSnapshot is a final record — cannot be Mockito-mocked without mockito-inline.
        // Returning null is fine: the controller forwards it untouched and buildContext is mocked.
        when(indicatorService.computeSnapshot(eq(Instrument.MCL), eq("10m"))).thenReturn(null);
        when(playbookService.computeAtr(eq(Instrument.MCL), eq("10m"))).thenReturn(realAtr);
        // AgentContext / FinalVerdict are final records — returning null is sufficient
        // because the controller only forwards them and the test doesn't assert on them.
        when(orchestratorService.buildContext(any(), anyString(), any(), any(), any()))
            .thenReturn(null);
        when(orchestratorService.orchestrate(any(), any())).thenReturn(null);

        // Act
        controller.getFullPlaybook(Instrument.MCL, "10m");

        // Assert: computeAtr was called, and its result was propagated to buildContext
        // (NOT BigDecimal.ONE, which was the original bug).
        verify(playbookService).computeAtr(eq(Instrument.MCL), eq("10m"));

        ArgumentCaptor<BigDecimal> atrCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<PlaybookEvaluation> playbookCaptor = ArgumentCaptor.forClass(PlaybookEvaluation.class);
        verify(orchestratorService).buildContext(
            eq(Instrument.MCL), eq("10m"), any(),
            atrCaptor.capture(), playbookCaptor.capture());

        assertEquals(0, realAtr.compareTo(atrCaptor.getValue()),
            "ATR passed to buildContext must equal the computed ATR (2.50), not BigDecimal.ONE");
        assertEquals(playbook, playbookCaptor.getValue(),
            "Playbook must be forwarded so ZoneQualityAIAgent targets the right OB");
    }

    @Test
    void getFullPlaybook_whenAtrComputationReturnsNull_fallsBackToBigDecimalOne() {
        // Arrange: computeAtr returns null (insufficient candles, repo error, …)
        PlaybookEvaluation playbook = emptyPlaybook();

        when(playbookService.evaluate(any(), anyString())).thenReturn(playbook);
        when(indicatorService.computeSnapshot(any(), anyString())).thenReturn(null);
        when(playbookService.computeAtr(any(), anyString())).thenReturn(null);
        // AgentContext / FinalVerdict are final records — returning null is sufficient
        // because the controller only forwards them and the test doesn't assert on them.
        when(orchestratorService.buildContext(any(), anyString(), any(), any(), any()))
            .thenReturn(null);
        when(orchestratorService.orchestrate(any(), any())).thenReturn(null);

        // Act
        controller.getFullPlaybook(Instrument.MCL, "10m");

        // Assert: fallback path uses BigDecimal.ONE — documents the intentional default
        ArgumentCaptor<BigDecimal> atrCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(orchestratorService).buildContext(
            eq(Instrument.MCL), eq("10m"), any(),
            atrCaptor.capture(), any());

        assertEquals(0, BigDecimal.ONE.compareTo(atrCaptor.getValue()),
            "When ATR cannot be computed, controller must fall back to BigDecimal.ONE");
    }

    private static PlaybookEvaluation emptyPlaybook() {
        return new PlaybookEvaluation(
            null, List.of(), null, null, List.of(), 0, "NO TRADE", Instant.now());
    }
}

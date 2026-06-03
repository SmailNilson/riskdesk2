package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the orphan-flatten safety net (Codex P1-A): an ACTIVE {@code QUANT_SIM_AUTO}
 * row whose paper simulation has closed must be re-flattened; a row whose paper
 * sim is still open must be left alone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuantSimFlattenReconcilerTest {

    @Mock TradeExecutionRepositoryPort repo;
    @Mock Quant7GatesSimulationService simulationService;
    @Mock LivePricePort livePricePort;
    @Mock Quant7GatesExecutionBridge bridge;

    private QuantSimExecutionProperties props;
    private QuantSimFlattenReconciler reconciler;

    @BeforeEach
    void setUp() {
        props = new QuantSimExecutionProperties();
        props.setEnabled(true);
        when(bridge.flatten(any(), any())).thenReturn(RoutingResult.of(RoutingOutcome.ROUTED));
        reconciler = new QuantSimFlattenReconciler(repo, simulationService, props, livePricePort, provider(bridge));
    }

    @Test
    void flattensOrphanWhenPaperSimClosed() {
        TradeExecutionRecord row = activeRow("MNQ", "LONG");
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.QUANT_SIM_AUTO, ExecutionStatus.ACTIVE))
            .thenReturn(List.of(row));
        when(simulationService.hasOpenSimulation(com.riskdesk.domain.model.Instrument.MNQ,
            Quant7GatesSimulation.Direction.LONG)).thenReturn(false);

        reconciler.reconcile();

        verify(bridge).flatten(eq(row), any());
    }

    @Test
    void leavesPositionWhenPaperSimStillOpen() {
        TradeExecutionRecord row = activeRow("MNQ", "LONG");
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.QUANT_SIM_AUTO, ExecutionStatus.ACTIVE))
            .thenReturn(List.of(row));
        when(simulationService.hasOpenSimulation(com.riskdesk.domain.model.Instrument.MNQ,
            Quant7GatesSimulation.Direction.LONG)).thenReturn(true);

        reconciler.reconcile();

        verify(bridge, never()).flatten(any(), any());
    }

    @Test
    void noOpWhenDisabled() {
        props.setEnabled(false);
        reconciler.reconcile();
        verify(repo, never()).findByTriggerSourceAndStatus(any(), any());
        verify(bridge, never()).flatten(any(), any());
    }

    @Test
    void noOpWhenBridgeUnavailable() {
        QuantSimFlattenReconciler noBridge =
            new QuantSimFlattenReconciler(repo, simulationService, props, livePricePort, provider(null));
        noBridge.reconcile();
        verify(repo, never()).findByTriggerSourceAndStatus(any(), any());
    }

    private static TradeExecutionRecord activeRow(String instrument, String action) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(7L);
        r.setStatus(ExecutionStatus.ACTIVE);
        r.setInstrument(instrument);
        r.setAction(action);
        return r;
    }

    private static ObjectProvider<Quant7GatesExecutionBridge> provider(Quant7GatesExecutionBridge bridge) {
        return new ObjectProvider<>() {
            @Override public Quant7GatesExecutionBridge getObject() { return bridge; }
            @Override public Quant7GatesExecutionBridge getObject(Object... args) { return bridge; }
            @Override public Quant7GatesExecutionBridge getIfAvailable() { return bridge; }
            @Override public Quant7GatesExecutionBridge getIfUnique() { return bridge; }
        };
    }
}

package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.port.LivePricePort;
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
 * Tests the NY session-close force-flatten: every ACTIVE {@code QUANT_SIM_AUTO}
 * row is flattened before the CME break, and nothing fires when the feature or
 * force-close is disabled.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuantSimSessionCloseSchedulerTest {

    @Mock TradeExecutionRepositoryPort repo;
    @Mock LivePricePort livePricePort;
    @Mock Quant7GatesExecutionBridge bridge;

    private QuantSimExecutionProperties props;
    private QuantSimSessionCloseScheduler scheduler;

    @BeforeEach
    void setUp() {
        props = new QuantSimExecutionProperties();
        props.setEnabled(true);
        props.setForceCloseEnabled(true);
        when(bridge.flatten(any(), any())).thenReturn(RoutingResult.of(RoutingOutcome.ROUTED));
        scheduler = new QuantSimSessionCloseScheduler(repo, props, livePricePort, provider(bridge));
    }

    @Test
    void flattensAllActivePositions() {
        TradeExecutionRecord a = active("MNQ", "LONG");
        TradeExecutionRecord b = active("MCL", "SHORT");
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.QUANT_SIM_AUTO, ExecutionStatus.ACTIVE))
            .thenReturn(List.of(a, b));

        scheduler.forceCloseBeforeSessionEnd();

        verify(bridge).flatten(eq(a), any());
        verify(bridge).flatten(eq(b), any());
    }

    @Test
    void noOpWhenFeatureDisabled() {
        props.setEnabled(false);
        scheduler.forceCloseBeforeSessionEnd();
        verify(repo, never()).findByTriggerSourceAndStatus(any(), any());
    }

    @Test
    void noOpWhenForceCloseDisabled() {
        props.setForceCloseEnabled(false);
        scheduler.forceCloseBeforeSessionEnd();
        verify(repo, never()).findByTriggerSourceAndStatus(any(), any());
    }

    @Test
    void noOpWhenBridgeUnavailable() {
        QuantSimSessionCloseScheduler noBridge =
            new QuantSimSessionCloseScheduler(repo, props, livePricePort, provider(null));
        noBridge.forceCloseBeforeSessionEnd();
        verify(repo, never()).findByTriggerSourceAndStatus(any(), any());
    }

    private static TradeExecutionRecord active(String instrument, String action) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(1L);
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

package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.application.execution.OrderRouter;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiRiskPlan;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignal;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.execution.IntentKind;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.infrastructure.config.ExecutionProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 — WTX-RSI → unified OrderRouter migration. When {@code riskdesk.execution.unified-router.enabled} is
 * ON the bridge translates OPEN/CLOSE to a {@link TradeIntent} and routes through the shared core (never the
 * legacy broker path); when OFF the legacy path is used and the router is never called.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IbkrWtxRsiExecutionBridgeRouterTest {

    @Mock IbkrOrderService ibkrOrderService;
    @Mock TradeExecutionRepositoryPort repo;
    @Mock IbkrProperties ibkrProperties;
    @Mock OrderRouter router;

    @BeforeEach
    void setUp() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
    }

    private IbkrWtxRsiExecutionBridge bridge(boolean flagOn) {
        ExecutionProperties props = new ExecutionProperties();
        props.getUnifiedRouter().setEnabled(flagOn);
        return new IbkrWtxRsiExecutionBridge(ibkrOrderService, repo, ibkrProperties, router, props);
    }

    @Test
    void unifiedRouterOn_routesOpenAsIntent_notLegacyBrokerPath() {
        ArgumentCaptor<TradeIntent> cap = ArgumentCaptor.forClass(TradeIntent.class);
        when(router.route(cap.capture())).thenReturn(RoutingResult.of(RoutingOutcome.ROUTED, "ok"));

        WtxRoutingResult r = bridge(true).submitOpen(longSignal(), longPlan(),
                WtxRsiStrategyState.initial("MNQ", "5m"), new BigDecimal("30000.00"));

        assertThat(r.outcome()).isEqualTo(WtxRoutingOutcome.ROUTED);
        verify(ibkrOrderService, never()).submitEntryOrder(any()); // legacy broker path NOT used
        TradeIntent intent = cap.getValue();
        assertThat(intent.kind()).isEqualTo(IntentKind.OPEN);
        assertThat(intent.side()).isEqualTo(Side.LONG);
        assertThat(intent.instrument()).isEqualTo(Instrument.MNQ);
        assertThat(intent.timeframe()).isEqualTo("5m");
        assertThat(intent.quantity()).isEqualTo(1);
        assertThat(intent.source()).isEqualTo(ExecutionTriggerSource.WTXRSI_AUTO);
        // null account → the router reads the real default managed account (filter no-op), not the placeholder.
        assertThat(intent.brokerAccountId()).isNull();
        assertThat(intent.idempotencyKey()).startsWith("wtxrsi:MNQ:5m:").endsWith(":OPEN_LONG");
    }

    @Test
    void unifiedRouterOn_routesCloseAsIntent_onHeldSide() {
        ArgumentCaptor<TradeIntent> cap = ArgumentCaptor.forClass(TradeIntent.class);
        when(router.route(cap.capture())).thenReturn(RoutingResult.of(RoutingOutcome.ROUTED, "ok"));

        WtxRoutingResult r = bridge(true).submitClose(longStateWithPosition(),
                WtxRsiSignalRecord.Action.CLOSE_LONG, new BigDecimal("30050.00"));

        assertThat(r.outcome()).isEqualTo(WtxRoutingOutcome.ROUTED);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
        TradeIntent intent = cap.getValue();
        assertThat(intent.kind()).isEqualTo(IntentKind.CLOSE);
        assertThat(intent.side()).isEqualTo(Side.LONG);
        assertThat(intent.source()).isEqualTo(ExecutionTriggerSource.WTXRSI_AUTO);
        assertThat(intent.idempotencyKey()).contains(":CLOSE_LONG");
    }

    @Test
    void unifiedRouterOn_mapsBrokerMarginRejectToSkippedInsufficientMargin() {
        when(router.route(any())).thenReturn(RoutingResult.of(RoutingOutcome.FAILED_INSUFFICIENT_MARGIN, "no margin"));

        WtxRoutingResult r = bridge(true).submitOpen(longSignal(), longPlan(),
                WtxRsiStrategyState.initial("MNQ", "5m"), new BigDecimal("30000.00"));

        assertThat(r.outcome()).isEqualTo(WtxRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN);
        verify(router).route(any());
    }

    @Test
    void unifiedRouterOff_usesLegacyPath_routerNotCalled() {
        when(repo.findByExecutionKey(any())).thenReturn(java.util.Optional.empty());
        when(repo.createIfAbsent(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ibkrOrderService.submitEntryOrder(any()))
                .thenReturn(new com.riskdesk.application.dto.BrokerEntryOrderSubmission(7L, "Submitted", "ref", Instant.now()));

        bridge(false).submitOpen(longSignal(), longPlan(),
                WtxRsiStrategyState.initial("MNQ", "5m"), new BigDecimal("30000.00"));

        verify(router, never()).route(any());
        verify(ibkrOrderService).submitEntryOrder(any()); // legacy path submitted
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static WtxRsiSignal longSignal() {
        return new WtxRsiSignal(0, Instant.parse("2026-06-03T13:00:00Z"), WtxRsiSignal.Side.LONG, true,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("30000.00"));
    }

    private static WtxRsiRiskPlan longPlan() {
        return new WtxRsiRiskPlan(WtxRsiSignal.Side.LONG, 1, new BigDecimal("30000.00"),
                new BigDecimal("29950.00"), new BigDecimal("30100.00"), new BigDecimal("50.00"), new BigDecimal("29950.00"));
    }

    private static WtxRsiStrategyState longStateWithPosition() {
        return WtxRsiStrategyState.initial("MNQ", "5m").withPosition(
                WtxRsiPosition.LONG, new BigDecimal("30000.00"), BigDecimal.ONE,
                new BigDecimal("29950.00"), new BigDecimal("30100.00"));
    }
}

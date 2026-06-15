package com.riskdesk.application.service;

import com.ib.client.Contract;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.event.ContractRolloverEvent;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.port.BigPrintPort;
import com.riskdesk.domain.orderflow.port.FlashCrashConfigPort;
import com.riskdesk.domain.orderflow.port.FootprintPort;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import com.riskdesk.domain.orderflow.port.TickBarPort;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayNativeClient;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayResolvedContract;
import com.riskdesk.infrastructure.marketdata.ibkr.SubscriptionRegistry;
import com.riskdesk.infrastructure.marketdata.ibkr.TickByTickClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the order-flow side of a contract rollover.
 * <p>
 * Before this wiring, {@code RolloverDetectionService.confirmRollover()} switched only the
 * live-price/quote streams. The tick-by-tick stream (a separate {@link TickByTickClient} socket)
 * and market depth stayed pinned to the <b>expired</b> contract — the 30s subscription loops only
 * touch <i>missing</i> instruments, and the tick watchdog never fires because the old contract
 * keeps trading up to expiry. The visible symptom was the TickChart / delta / book frozen on the
 * old month while the headline price had already rolled. This test fails if the
 * {@link OrderFlowOrchestrator#onContractRollover} re-subscribe is removed.
 */
class OrderFlowRolloverResubscribeTest {

    private IbGatewayNativeClient nativeClient;
    private IbGatewayContractResolver contractResolver;
    private TickByTickClient tickByTickClient;
    private TickDataPort tickDataPort;
    private OrderFlowOrchestrator orchestrator;

    private Contract newContract;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        OrderFlowProperties properties = new OrderFlowProperties();   // tick + depth enabled, MNQ configured
        nativeClient = mock(IbGatewayNativeClient.class);
        contractResolver = mock(IbGatewayContractResolver.class);
        tickByTickClient = mock(TickByTickClient.class);
        tickDataPort = mock(TickDataPort.class);

        when(nativeClient.isConnected()).thenReturn(true);
        when(tickByTickClient.isConnected()).thenReturn(true);

        // The resolver cache is refreshed to the NEW contract before the rollover event is published,
        // so re-resolution during the listener returns September.
        newContract = new Contract();
        newContract.symbol("MNQ");
        newContract.lastTradeDateOrContractMonth("202609");
        when(contractResolver.resolve(Instrument.MNQ))
            .thenReturn(Optional.of(new IbGatewayResolvedContract(Instrument.MNQ, newContract, null)));

        ObjectProvider<TickDataPort> tickProvider = mock(ObjectProvider.class);
        when(tickProvider.getIfAvailable()).thenReturn(tickDataPort);
        ObjectProvider<MarketDepthPort> depthProvider = mock(ObjectProvider.class);
        ObjectProvider<FootprintPort> footprintProvider = mock(ObjectProvider.class);
        ObjectProvider<TickBarPort> tickBarProvider = mock(ObjectProvider.class);
        ObjectProvider<BigPrintPort> bigPrintProvider = mock(ObjectProvider.class);

        orchestrator = new OrderFlowOrchestrator(
            nativeClient,
            contractResolver,
            properties,
            tickProvider,
            mock(SimpMessagingTemplate.class),
            mock(TickLogService.class),
            mock(SubscriptionRegistry.class),
            tickByTickClient,
            depthProvider,
            footprintProvider,
            tickBarProvider,
            bigPrintProvider,
            mock(ApplicationEventPublisher.class),
            mock(CandleRepositoryPort.class),
            mock(FlashCrashConfigPort.class)
        );
    }

    @Test
    void rollover_cancelsOldStreamsAndResubscribesNewContract() {
        orchestrator.onContractRollover(
            new ContractRolloverEvent(Instrument.MNQ, "202606", "202609", Instant.now()));

        // Old contract streams cancelled
        verify(tickByTickClient).cancelTickByTick(Instrument.MNQ);
        verify(nativeClient).unsubscribeDepth(Instrument.MNQ);

        // Per-instrument flow state purged so nothing spans the old→new price gap
        verify(tickDataPort).purgeInstrument(Instrument.MNQ);

        // Re-subscribed immediately on the NEW (September) contract — not deferred to the 30s loop
        verify(tickByTickClient).subscribeTickByTick(eq(newContract), eq(Instrument.MNQ));
        verify(nativeClient).subscribeDepth(eq(newContract), eq(Instrument.MNQ), anyInt());
    }

    @Test
    void rollover_ignoresSyntheticInstruments() {
        // DXY is synthetic (no exchange-traded contract) — the order-flow feeds must not be touched.
        orchestrator.onContractRollover(
            new ContractRolloverEvent(Instrument.DXY, "202606", "202609", Instant.now()));

        verify(tickByTickClient, never()).cancelTickByTick(Instrument.DXY);
        verify(nativeClient, never()).unsubscribeDepth(Instrument.DXY);
        verify(tickDataPort, never()).purgeInstrument(Instrument.DXY);
    }
}

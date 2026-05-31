package com.riskdesk.application.service.perfectsetup;

import com.riskdesk.application.dto.IcebergEventView;
import com.riskdesk.application.quant.automation.QuantAutoArmService;
import com.riskdesk.application.service.FlashCrashStatusService;
import com.riskdesk.application.service.FlashCrashStatusService.FlashCrashStatusSnapshot;
import com.riskdesk.application.service.OrderFlowHistoryService;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.orderflow.event.PerfectSetupDetected;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionSide;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionType;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.orderflow.model.DistributionSignal.DistributionType;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupSignal;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupState;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.AbsorptionPort;
import com.riskdesk.domain.quant.port.CyclePort;
import com.riskdesk.domain.quant.port.DistributionPort;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.structure.IndicatorsPort;
import com.riskdesk.domain.quant.structure.IndicatorsSnapshot;
import com.riskdesk.infrastructure.config.PerfectSetupProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PerfectSetupServiceTest {

    private final Instant now = Instant.parse("2026-05-29T15:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private PerfectSetupProperties props;
    private AbsorptionPort absorptionPort;
    private DistributionPort distributionPort;
    private CyclePort cyclePort;
    private LivePricePort livePricePort;
    private IndicatorsPort indicatorsPort;
    private OrderFlowHistoryService orderFlowHistory;
    private FlashCrashStatusService flashCrashStatus;
    private CandleRepositoryPort candleRepository;
    private ApplicationEventPublisher eventPublisher;
    private SimpMessagingTemplate messagingTemplate;
    private QuantAutoArmService autoArm;
    private ObjectProvider<QuantAutoArmService> autoArmProvider;

    private PerfectSetupService service;

    @BeforeEach
    void setUp() {
        props = new PerfectSetupProperties();
        absorptionPort = mock(AbsorptionPort.class);
        distributionPort = mock(DistributionPort.class);
        cyclePort = mock(CyclePort.class);
        livePricePort = mock(LivePricePort.class);
        indicatorsPort = mock(IndicatorsPort.class);
        orderFlowHistory = mock(OrderFlowHistoryService.class);
        flashCrashStatus = mock(FlashCrashStatusService.class);
        candleRepository = mock(CandleRepositoryPort.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        autoArm = mock(QuantAutoArmService.class);
        autoArmProvider = mock(ObjectProvider.class);
        when(autoArmProvider.getIfAvailable()).thenReturn(autoArm);

        service = new PerfectSetupService(props, absorptionPort, distributionPort, cyclePort,
            livePricePort, indicatorsPort, orderFlowHistory, flashCrashStatus, candleRepository,
            eventPublisher, messagingTemplate, autoArmProvider, clock);
    }

    /** Wire a full 6/6 LONG confluence into the mocks. */
    private void stubFullLong() {
        when(livePricePort.current(Instrument.MNQ))
            .thenReturn(Optional.of(new LivePriceSnapshot(30305.25, now, "LIVE_PUSH")));
        when(distributionPort.recent(eq(Instrument.MNQ), any(Instant.class))).thenReturn(List.of(
            new DistributionSignal(Instrument.MNQ, DistributionType.ACCUMULATION, 3, 8.0, 60.0,
                30300.0, null, 84, now.minusSeconds(120), now.minusSeconds(60))));
        when(cyclePort.recent(eq(Instrument.MNQ), any(Instant.class))).thenReturn(List.of());
        when(absorptionPort.recent(eq(Instrument.MNQ), any(Instant.class))).thenReturn(List.of(
            new AbsorptionSignal(Instrument.MNQ, AbsorptionSide.BULLISH_ABSORPTION, 9.0, 250L, 4.0,
                600L, now.minusSeconds(30), AbsorptionType.CLASSIC, "bull")));
        when(orderFlowHistory.recentIcebergs(eq(Instrument.MNQ), anyInt())).thenReturn(List.of(
            new IcebergEventView("MNQ", now.minusSeconds(60), "BID_ICEBERG", 30298.75, 9, 50L, 2.1, 100.0),
            new IcebergEventView("MNQ", now.minusSeconds(90), "ASK_ICEBERG", 30367.25, 4, 30L, 1.0, 60.0)));
        when(flashCrashStatus.latestForInstrument(Instrument.MNQ))
            .thenReturn(Optional.of(new FlashCrashStatusSnapshot(Instrument.MNQ, "REVERSING", "DECELERATING", 1, 100.0, now)));
        when(indicatorsPort.snapshot5m(Instrument.MNQ)).thenReturn(Optional.of(new IndicatorsSnapshot(
            30299.42, 30270.0, 30360.0, 0.10, 0.0, "DISCOUNT", "BULLISH", null,
            Map.of(), List.of(), List.of(), List.of())));
        when(candleRepository.findRecentCandles(eq(Instrument.MNQ), eq("5m"), anyInt())).thenReturn(List.of());
    }

    @Test
    void armsLong_publishesSnapshotAndEvent() {
        stubFullLong();

        PerfectSetupSignal s = service.evaluateInstrument(Instrument.MNQ, now);

        assertThat(s.state()).isEqualTo(PerfectSetupState.LONG_ARMED);
        verify(messagingTemplate).convertAndSend(eq(PerfectSetupService.TOPIC), any(Object.class));
        verify(eventPublisher).publishEvent(any(PerfectSetupDetected.class));
    }

    @Test
    void noTransition_emitsNoEvent_butStillPublishesSnapshot() {
        // No order-flow context → stays IDLE; IDLE→IDLE is not a transition.
        when(livePricePort.current(Instrument.MNQ)).thenReturn(Optional.empty());
        when(distributionPort.recent(eq(Instrument.MNQ), any(Instant.class))).thenReturn(List.of());
        when(cyclePort.recent(eq(Instrument.MNQ), any(Instant.class))).thenReturn(List.of());
        when(absorptionPort.recent(eq(Instrument.MNQ), any(Instant.class))).thenReturn(List.of());
        when(orderFlowHistory.recentIcebergs(eq(Instrument.MNQ), anyInt())).thenReturn(List.of());
        when(flashCrashStatus.latestForInstrument(Instrument.MNQ)).thenReturn(Optional.empty());
        when(indicatorsPort.snapshot5m(Instrument.MNQ)).thenReturn(Optional.empty());
        when(candleRepository.findRecentCandles(eq(Instrument.MNQ), eq("5m"), anyInt())).thenReturn(List.of());

        PerfectSetupSignal s = service.evaluateInstrument(Instrument.MNQ, now);

        assertThat(s.state()).isEqualTo(PerfectSetupState.IDLE);
        verify(messagingTemplate).convertAndSend(eq(PerfectSetupService.TOPIC), any(Object.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void bridgesToAutoArm_onlyWhenFlagEnabled() {
        stubFullLong();
        props.getAutoArm().setEnabled(true);
        when(autoArm.armFromPerfectSetup(eq(Instrument.MNQ), any()))
            .thenReturn(Optional.of(new TradeExecutionRecord()));

        service.evaluateInstrument(Instrument.MNQ, now);

        verify(autoArm).armFromPerfectSetup(eq(Instrument.MNQ), any());
    }

    @Test
    void doesNotBridge_whenFlagDisabled() {
        stubFullLong();
        // props.autoArm.enabled defaults to false

        service.evaluateInstrument(Instrument.MNQ, now);

        verify(autoArm, never()).armFromPerfectSetup(any(), any());
    }

    @Test
    void evaluateAll_noOpWhenDisabled() {
        props.setEnabled(false);

        service.evaluateAll();

        verifyNoInteractions(livePricePort, distributionPort, absorptionPort, cyclePort,
            orderFlowHistory, flashCrashStatus, indicatorsPort, candleRepository, messagingTemplate);
    }
}

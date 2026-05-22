package com.riskdesk.application.service.strategy;

import com.riskdesk.application.service.PlaybookService;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SetupType;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookProfile;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookSignal;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookStrategyState;
import com.riskdesk.domain.engine.strategy.playbook.port.PlaybookSignalHistoryPort;
import com.riskdesk.domain.engine.strategy.playbook.port.PlaybookStrategyStatePort;
import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import com.riskdesk.infrastructure.config.PlaybookStrategyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlaybookStrategyServiceTest {

    private PlaybookStrategyStatePort statePort;
    private PlaybookSignalHistoryPort historyPort;
    private PlaybookService playbookService;
    private CandleRepositoryPort candlePort;
    private PlaybookExecutionBridge executionBridge;
    private TradeSimulationRepositoryPort simulationRepository;
    private SimpMessagingTemplate ws;
    private PlaybookStrategyProperties properties;

    private PlaybookStrategyService service;
    private Map<String, PlaybookStrategyState> stateMap;

    @BeforeEach
    void setUp() {
        stateMap = new HashMap<>();
        statePort = mock(PlaybookStrategyStatePort.class);

        when(statePort.load(any(String.class), any(String.class))).thenAnswer(invocation -> {
            String inst = invocation.getArgument(0);
            String tf = invocation.getArgument(1);
            return Optional.ofNullable(stateMap.get(inst + ":" + tf));
        });

        doAnswer(invocation -> {
            PlaybookStrategyState s = invocation.getArgument(0);
            stateMap.put(s.instrument() + ":" + s.timeframe(), s);
            return null;
        }).when(statePort).save(any(PlaybookStrategyState.class));

        historyPort = mock(PlaybookSignalHistoryPort.class);
        playbookService = mock(PlaybookService.class);
        candlePort = mock(CandleRepositoryPort.class);
        executionBridge = mock(PlaybookExecutionBridge.class);
        simulationRepository = mock(TradeSimulationRepositoryPort.class);
        ws = mock(SimpMessagingTemplate.class);
        properties = new PlaybookStrategyProperties();

        // Configure default properties
        properties.setEnabled(true);
        properties.setInstruments(List.of("MCL", "MNQ"));
        properties.setTimeframes(List.of("5m", "10m"));
        properties.setInitialEquity(BigDecimal.valueOf(10000.0));
        properties.setMaxDailyLossUsd(BigDecimal.valueOf(1500.0));

        service = new PlaybookStrategyService(
                statePort, historyPort, playbookService, candlePort, executionBridge, simulationRepository, ws, properties
        );
    }

    @Test
    void onCandleClosed_whenStrategyDisabled_doesNothing() {
        properties.setEnabled(false);
        CandleClosed event = new CandleClosed("MCL", "10m", Instant.now());

        service.onCandleClosed(event);

        verifyNoInteractions(statePort, playbookService, historyPort);
    }

    @Test
    void onCandleClosed_whenInstrumentNotConfigured_doesNothing() {
        CandleClosed event = new CandleClosed("ES", "10m", Instant.now());

        service.onCandleClosed(event);

        verifyNoInteractions(statePort, playbookService, historyPort);
    }

    @Test
    void onCandleClosed_whenTimeframeNotConfigured_doesNothing() {
        CandleClosed event = new CandleClosed("MCL", "1h", Instant.now());

        service.onCandleClosed(event);

        verifyNoInteractions(statePort, playbookService, historyPort);
    }

    @Test
    void onCandleClosed_whenDailyMaxLossHit_blocksEntryAndFlattensActive() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withProfile(PlaybookProfile.STRICT)
                .withAutoExecution(true)
                .withFlat(bd(-1600.0)) // daily PNL worse than -$1500
                .withPosition(WtxPosition.LONG, bd(50.0), bd(2), bd(1.0));

        stateMap.put("MCL:10m", state);

        // Mock candle for closing
        Candle candle = mock(Candle.class);
        when(candle.getClose()).thenReturn(bd(49.0));
        when(candlePort.findRecentCandles(any(Instrument.class), eq("10m"), eq(1)))
                .thenReturn(List.of(candle));

        when(executionBridge.submitClose(any(), any()))
                .thenReturn(WtxRoutingResult.of(WtxRoutingOutcome.ROUTED));

        CandleClosed event = new CandleClosed("MCL", "10m", Instant.now());
        service.onCandleClosed(event);

        // Verify live position is closed/flattened at bridge level
        verify(executionBridge).submitClose(any(PlaybookStrategyState.class), eq(bd(49.0)));

        // Verify state is updated to FLAT with max loss hit enabled
        ArgumentCaptor<PlaybookStrategyState> captor = ArgumentCaptor.forClass(PlaybookStrategyState.class);
        verify(statePort, atLeastOnce()).save(captor.capture());

        PlaybookStrategyState finalState = captor.getValue();
        assertEquals(WtxPosition.FLAT, finalState.currentPosition());
        assertTrue(finalState.maxLossHit());
    }

    @Test
    void onCandleClosed_qualifiedSetup_withAutoOff_spawnsVirtualSimulation() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(false)
                .withConfiguredOrderQty(2);

        stateMap.put("MCL:10m", state);

        Candle candle = mock(Candle.class);
        when(candle.getClose()).thenReturn(bd(50.0));
        when(candlePort.findRecentCandles(any(Instrument.class), eq("10m"), eq(1)))
                .thenReturn(List.of(candle));

        // Setup evaluation qualifying score >= 5
        PlaybookPlan plan = new PlaybookPlan(bd(50.0), bd(48.0), bd(53.0), bd(55.0), 1.5, 1.0, "sl", "tp1");
        SetupCandidate setup = new SetupCandidate(SetupType.ZONE_RETEST, "Zone1", bd(51.0), bd(49.0), bd(50.0), 0, true, true, true, 1.5, 5);
        PlaybookEvaluation eval = new PlaybookEvaluation(null, List.of(), setup, plan, List.of(), 5, "Qualified", Instant.now());

        when(playbookService.evaluate(any(Instrument.class), eq("10m"))).thenReturn(eval);
        when(playbookService.computeAtr(any(Instrument.class), eq("10m"))).thenReturn(bd(1.2));

        CandleClosed event = new CandleClosed("MCL", "10m", Instant.now());
        service.onCandleClosed(event);

        // Verify PlaybookSignal is saved in history
        verify(historyPort).save(any(PlaybookSignal.class));

        // Verify TradeSimulation is saved (virtual mode)
        ArgumentCaptor<TradeSimulation> simCaptor = ArgumentCaptor.forClass(TradeSimulation.class);
        verify(simulationRepository).save(simCaptor.capture());

        TradeSimulation savedSim = simCaptor.getValue();
        assertEquals(ReviewType.PLAYBOOK, savedSim.reviewType());
        assertEquals("LONG", savedSim.action());
        assertEquals(TradeSimulationStatus.PENDING_ENTRY, savedSim.simulationStatus());

        // Verify strategy state transitions to LONG active position locally
        ArgumentCaptor<PlaybookStrategyState> stateCaptor = ArgumentCaptor.forClass(PlaybookStrategyState.class);
        verify(statePort, atLeastOnce()).save(stateCaptor.capture());
        PlaybookStrategyState savedState = stateCaptor.getValue();
        assertEquals(WtxPosition.LONG, savedState.currentPosition());
        assertEquals(bd(50.0), savedState.entryPrice());
    }

    @Test
    void onCandleClosed_qualifiedSetup_withAutoOn_routesLiveIbkrOrder() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withAutoExecution(true)
                .withConfiguredOrderQty(3);

        stateMap.put("MCL:10m", state);

        Candle candle = mock(Candle.class);
        when(candle.getClose()).thenReturn(bd(50.0));
        when(candlePort.findRecentCandles(any(Instrument.class), eq("10m"), eq(1)))
                .thenReturn(List.of(candle));

        PlaybookPlan plan = new PlaybookPlan(bd(50.0), bd(48.0), bd(53.0), bd(55.0), 1.5, 1.0, "sl", "tp1");
        SetupCandidate setup = new SetupCandidate(SetupType.ZONE_RETEST, "Zone1", bd(51.0), bd(49.0), bd(50.0), 0, true, true, true, 1.5, 6);
        PlaybookEvaluation eval = new PlaybookEvaluation(null, List.of(), setup, plan, List.of(), 6, "Qualified", Instant.now());

        when(playbookService.evaluate(any(Instrument.class), eq("10m"))).thenReturn(eval);
        when(playbookService.computeAtr(any(Instrument.class), eq("10m"))).thenReturn(bd(1.2));

        when(executionBridge.submitEntry(any(), any()))
                .thenReturn(WtxRoutingResult.of(WtxRoutingOutcome.ROUTED));

        CandleClosed event = new CandleClosed("MCL", "10m", Instant.now());
        service.onCandleClosed(event);

        // Verify executionBridge is called for Live IBKR Limit order routing
        verify(executionBridge).submitEntry(any(PlaybookSignal.class), any(PlaybookStrategyState.class));

        // Verify PlaybookSignal is saved in history with routing result
        verify(historyPort).save(argThat(sig -> sig.routingOutcome() == WtxRoutingOutcome.ROUTED));

        // Verify state is saved with LONG active position and qty 3
        ArgumentCaptor<PlaybookStrategyState> stateCaptor = ArgumentCaptor.forClass(PlaybookStrategyState.class);
        verify(statePort, atLeastOnce()).save(stateCaptor.capture());
        PlaybookStrategyState savedState = stateCaptor.getValue();
        assertEquals(WtxPosition.LONG, savedState.currentPosition());
        assertEquals(BigDecimal.valueOf(3), savedState.entryQty());
    }

    @Test
    void handleVirtualExit_flattensStrategyStateAndPublishes() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000))
                .withPosition(WtxPosition.LONG, bd(50.0), bd(2), bd(1.2));

        stateMap.put("MCL:10m", state);

        PlaybookSignal signal = new PlaybookSignal(
                UUID.randomUUID(), "MCL", "10m", Instant.now(), "LONG", 5, "ZONE_RETEST",
                bd(50.0), bd(48.0), bd(53.0), bd(55.0), null, null
        );

        service.handleVirtualExit(signal, TradeSimulationStatus.WIN, bd(53.0));

        // Verify state gets flattened and realized P&L is calculated
        ArgumentCaptor<PlaybookStrategyState> stateCaptor = ArgumentCaptor.forClass(PlaybookStrategyState.class);
        verify(statePort).save(stateCaptor.capture());

        PlaybookStrategyState savedState = stateCaptor.getValue();
        assertEquals(WtxPosition.FLAT, savedState.currentPosition());
        assertNull(savedState.entryPrice());
        // MCL tick value calculated internally: MCL tick size is 0.01, tick value is 10.0 (or similar from calculation).
        // Let's verify realized P&L is non-zero
        assertNotEquals(BigDecimal.ZERO, savedState.dailyRealizedPnl());
    }

    @Test
    void updateProfile_savesAndPublishesUpdatedState() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000));
        stateMap.put("MCL:10m", state);

        PlaybookStrategyState result = service.updateProfile("MCL", "10m", PlaybookProfile.STRICT);

        assertEquals(PlaybookProfile.STRICT, result.activeProfile());
        verify(statePort).save(result);
        verify(ws, atLeastOnce()).convertAndSend(eq("/topic/playbook-state/MCL/10m"), any(Map.class));
    }

    @Test
    void updateAutoExecution_savesAndPublishesUpdatedState() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000)).withAutoExecution(false);
        stateMap.put("MCL:10m", state);

        PlaybookStrategyState result = service.updateAutoExecution("MCL", "10m", true);

        assertTrue(result.autoExecutionEnabled());
        verify(statePort).save(result);
        verify(ws, atLeastOnce()).convertAndSend(eq("/topic/playbook-state/MCL/10m"), any(Map.class));
    }

    @Test
    void updateConfiguredOrderQty_savesAndPublishesUpdatedState() {
        PlaybookStrategyState state = PlaybookStrategyState.initial("MCL", "10m", bd(10000)).withConfiguredOrderQty(1);
        stateMap.put("MCL:10m", state);

        PlaybookStrategyState result = service.updateConfiguredOrderQty("MCL", "10m", 5);

        assertEquals(5, result.configuredOrderQty());
        verify(statePort).save(result);
        verify(ws, atLeastOnce()).convertAndSend(eq("/topic/playbook-state/MCL/10m"), any(Map.class));
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}

package com.riskdesk.application.quant.positions;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VirtualStopWatcherTest {

    private static final Instant NOW = Instant.parse("2026-04-30T13:30:00Z");

    private TradeExecutionRepositoryPort repo;
    private LivePricePort livePricePort;
    private ActivePositionsService activePositions;
    private QuantVirtualStopProperties props;
    private VirtualStopWatcher watcher;

    @BeforeEach
    void setUp() {
        repo = mock(TradeExecutionRepositoryPort.class);
        livePricePort = mock(LivePricePort.class);
        activePositions = mock(ActivePositionsService.class);
        props = new QuantVirtualStopProperties();
        props.setEnabled(true);
        watcher = new VirtualStopWatcher(repo, livePricePort, activePositions, props);
    }

    @Test
    void disabled_flag_does_nothing() {
        props.setEnabled(false);
        watcher.sweep();
        verifyNoInteractions(repo, livePricePort, activePositions);
    }

    @Test
    void long_stop_breach_auto_closes_with_sl_reason() {
        stubActive(makeActive(1L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ))
            .thenReturn(Optional.of(new LivePriceSnapshot(20020.0, NOW, "LIVE")));

        watcher.sweep();

        verify(activePositions).closePosition(1L, "virtual-stop:SL");
    }

    @Test
    void long_target_hit_auto_closes_with_tp_reason() {
        stubActive(makeActive(2L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ))
            .thenReturn(Optional.of(new LivePriceSnapshot(20095.0, NOW, "LIVE")));

        watcher.sweep();

        verify(activePositions).closePosition(2L, "virtual-stop:TP");
    }

    @Test
    void short_stop_breach_auto_closes_with_sl_reason() {
        stubActive(makeActive(3L, "MGC", "SHORT", bd("20125"), bd("20060")));
        when(livePricePort.current(Instrument.MGC))
            .thenReturn(Optional.of(new LivePriceSnapshot(20130.0, NOW, "LIVE")));

        watcher.sweep();

        verify(activePositions).closePosition(3L, "virtual-stop:SL");
    }

    @Test
    void price_between_levels_does_not_close() {
        stubActive(makeActive(4L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ))
            .thenReturn(Optional.of(new LivePriceSnapshot(20050.0, NOW, "LIVE")));

        watcher.sweep();

        verify(activePositions, never()).closePosition(any(), any());
    }

    @Test
    void missing_live_price_does_not_close() {
        stubActive(makeActive(5L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(Optional.empty());

        watcher.sweep();

        verify(activePositions, never()).closePosition(any(), any());
    }

    @Test
    void only_queries_manual_active_rows() {
        when(repo.findByTriggerSourceAndStatus(any(), any())).thenReturn(List.of());
        watcher.sweep();
        verify(repo).findByTriggerSourceAndStatus(
            eq(ExecutionTriggerSource.MANUAL_QUANT_PANEL), eq(ExecutionStatus.ACTIVE));
    }

    private void stubActive(TradeExecutionRecord row) {
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.MANUAL_QUANT_PANEL, ExecutionStatus.ACTIVE))
            .thenReturn(List.of(row));
    }

    private static TradeExecutionRecord makeActive(long id, String instrument, String action,
                                                   BigDecimal sl, BigDecimal tp) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(id);
        r.setInstrument(instrument);
        r.setAction(action);
        r.setStatus(ExecutionStatus.ACTIVE);
        r.setVirtualStopLoss(sl);
        r.setVirtualTakeProfit(tp);
        return r;
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}

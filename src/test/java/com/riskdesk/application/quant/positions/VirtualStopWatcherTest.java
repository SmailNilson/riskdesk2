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
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20020.0));

        watcher.sweep();

        verify(activePositions).closePosition(1L, "virtual-stop:SL");
    }

    @Test
    void long_target_hit_auto_closes_with_tp_reason() {
        stubActive(makeActive(2L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20095.0));

        watcher.sweep();

        verify(activePositions).closePosition(2L, "virtual-stop:TP");
    }

    @Test
    void short_stop_breach_auto_closes_with_sl_reason() {
        stubActive(makeActive(3L, "MGC", "SHORT", bd("20125"), bd("20060")));
        when(livePricePort.current(Instrument.MGC)).thenReturn(liveQuote(20130.0));

        watcher.sweep();

        verify(activePositions).closePosition(3L, "virtual-stop:SL");
    }

    @Test
    void price_between_levels_does_not_close() {
        stubActive(makeActive(4L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20050.0));

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
    void stale_quote_does_not_auto_close() {
        // A LIVE-sourced but OLD quote (beyond the freshness window) must not flatten — auto-close is
        // irreversible and the market may have moved away from a stale tick.
        stubActive(makeActive(6L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(
            Optional.of(new LivePriceSnapshot(20020.0, Instant.now().minusSeconds(120), "LIVE_PUSH")));

        watcher.sweep();

        verify(activePositions, never()).closePosition(any(), any());
    }

    @Test
    void non_live_source_does_not_auto_close() {
        // A fresh-but-cached / DB-fallback quote is not authoritative for an irreversible close.
        stubActive(makeActive(7L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(
            Optional.of(new LivePriceSnapshot(20020.0, Instant.now(), "FALLBACK_DB")));

        watcher.sweep();

        verify(activePositions, never()).closePosition(any(), any());
    }

    @Test
    void stop_wins_tie_when_sl_equals_tp_equals_live() {
        // Pessimistic convention: when SL and TP coincide at the live price, the STOP wins.
        stubActive(makeActive(8L, "MNQ", "LONG", bd("20050"), bd("20050")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20050.0));

        watcher.sweep();

        verify(activePositions).closePosition(8L, "virtual-stop:SL");
    }

    @Test
    void one_failing_row_does_not_stop_the_sweep() {
        // Per-row isolation: a close that throws on the first breached row must not abort the batch.
        TradeExecutionRecord first = makeActive(9L, "MNQ", "LONG", bd("20025"), bd("20090"));
        TradeExecutionRecord second = makeActive(10L, "MGC", "SHORT", bd("20125"), bd("20060"));
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.MANUAL_QUANT_PANEL, ExecutionStatus.ACTIVE))
            .thenReturn(List.of(first, second));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20020.0)); // first breaches SL
        when(livePricePort.current(Instrument.MGC)).thenReturn(liveQuote(20130.0)); // second breaches SL
        when(activePositions.closePosition(9L, "virtual-stop:SL")).thenThrow(new RuntimeException("boom"));

        watcher.sweep();

        verify(activePositions).closePosition(9L, "virtual-stop:SL");
        verify(activePositions).closePosition(10L, "virtual-stop:SL"); // still reached despite the first failure
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

    /** A genuinely live + fresh quote — passes the watcher's source + freshness guard. */
    private static Optional<LivePriceSnapshot> liveQuote(double price) {
        return Optional.of(new LivePriceSnapshot(price, Instant.now(), "LIVE_PUSH"));
    }
}

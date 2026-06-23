package com.riskdesk.application.execution;

import com.riskdesk.application.quant.positions.ActivePositionsService;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlaybookPositionReconcilerTest {

    private TradeExecutionRepositoryPort repo;
    private LivePricePort livePricePort;
    private ActivePositionsService activePositions;
    private IbkrProperties ibkrProperties;
    private PlaybookPositionWatchProperties props;
    private PlaybookPositionReconciler reconciler;

    @BeforeEach
    void setUp() {
        repo = mock(TradeExecutionRepositoryPort.class);
        livePricePort = mock(LivePricePort.class);
        activePositions = mock(ActivePositionsService.class);
        ibkrProperties = mock(IbkrProperties.class);
        when(ibkrProperties.isEnabled()).thenReturn(true);
        props = new PlaybookPositionWatchProperties();
        props.setEnabled(true);
        reconciler = new PlaybookPositionReconciler(repo, livePricePort, activePositions, ibkrProperties, props);
    }

    @Test
    void disabled_flag_does_nothing() {
        props.setEnabled(false);
        reconciler.sweep();
        verifyNoInteractions(repo, livePricePort, activePositions);
    }

    @Test
    void ibkr_disabled_does_nothing() {
        // No broker to flatten against — PLAYBOOK_AUTO broker rows only exist when IBKR is enabled.
        when(ibkrProperties.isEnabled()).thenReturn(false);
        reconciler.sweep();
        verifyNoInteractions(repo, livePricePort, activePositions);
    }

    @Test
    void long_stop_breach_auto_closes_with_sl_reason() {
        stubActive(makeActive(1L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20020.0));

        reconciler.sweep();

        verify(activePositions).closePosition(1L, "playbook-stop:SL");
    }

    @Test
    void long_target_hit_auto_closes_with_tp_reason() {
        stubActive(makeActive(2L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20095.0));

        reconciler.sweep();

        verify(activePositions).closePosition(2L, "playbook-stop:TP");
    }

    @Test
    void short_stop_breach_auto_closes_with_sl_reason() {
        stubActive(makeActive(3L, "MGC", "SHORT", bd("20125"), bd("20060")));
        when(livePricePort.current(Instrument.MGC)).thenReturn(liveQuote(20130.0));

        reconciler.sweep();

        verify(activePositions).closePosition(3L, "playbook-stop:SL");
    }

    @Test
    void price_between_levels_does_not_close() {
        stubActive(makeActive(4L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20050.0));

        reconciler.sweep();

        verify(activePositions, never()).closePosition(any(), any());
    }

    @Test
    void missing_live_price_does_not_close() {
        stubActive(makeActive(5L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(Optional.empty());

        reconciler.sweep();

        verify(activePositions, never()).closePosition(any(), any());
    }

    @Test
    void stale_quote_does_not_auto_close() {
        // A LIVE-sourced but OLD quote (beyond the freshness window) must not flatten — auto-close is
        // irreversible and the market may have moved away from a stale tick.
        stubActive(makeActive(6L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(
            Optional.of(new LivePriceSnapshot(20020.0, Instant.now().minusSeconds(120), "LIVE_PUSH")));

        reconciler.sweep();

        verify(activePositions, never()).closePosition(any(), any());
    }

    @Test
    void non_live_source_does_not_auto_close() {
        // A fresh-but-cached / DB-fallback quote is not authoritative for an irreversible close.
        stubActive(makeActive(7L, "MNQ", "LONG", bd("20025"), bd("20090")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(
            Optional.of(new LivePriceSnapshot(20020.0, Instant.now(), "FALLBACK_DB")));

        reconciler.sweep();

        verify(activePositions, never()).closePosition(any(), any());
    }

    @Test
    void stop_wins_tie_when_sl_equals_tp_equals_live() {
        // Pessimistic convention: when SL and TP coincide at the live price, the STOP wins.
        stubActive(makeActive(8L, "MNQ", "LONG", bd("20050"), bd("20050")));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20050.0));

        reconciler.sweep();

        verify(activePositions).closePosition(8L, "playbook-stop:SL");
    }

    @Test
    void one_failing_row_does_not_stop_the_sweep() {
        // Per-row isolation: a close that throws on the first breached row must not abort the batch.
        TradeExecutionRecord first = makeActive(9L, "MNQ", "LONG", bd("20025"), bd("20090"));
        TradeExecutionRecord second = makeActive(10L, "MGC", "SHORT", bd("20125"), bd("20060"));
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.ACTIVE))
            .thenReturn(List.of(first, second));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20020.0)); // first breaches SL
        when(livePricePort.current(Instrument.MGC)).thenReturn(liveQuote(20130.0)); // second breaches SL
        when(activePositions.closePosition(9L, "playbook-stop:SL")).thenThrow(new RuntimeException("boom"));

        reconciler.sweep();

        verify(activePositions).closePosition(9L, "playbook-stop:SL");
        verify(activePositions).closePosition(10L, "playbook-stop:SL"); // still reached despite the first failure
    }

    @Test
    void only_queries_playbook_active_rows() {
        when(repo.findByTriggerSourceAndStatus(any(), any())).thenReturn(List.of());
        reconciler.sweep();
        verify(repo).findByTriggerSourceAndStatus(
            eq(ExecutionTriggerSource.PLAYBOOK_AUTO), eq(ExecutionStatus.ACTIVE));
    }

    @Test
    void still_breached_exit_submitted_row_is_redriven() {
        // A first auto-close that rested (EXIT_SUBMITTED) and never filled must be re-attempted while the
        // level is still breached — otherwise the virtual stop silently stops protecting after one try.
        TradeExecutionRecord resting = makeActive(11L, "MNQ", "LONG", bd("20025"), bd("20090"));
        resting.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.ACTIVE))
            .thenReturn(List.of());
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.EXIT_SUBMITTED))
            .thenReturn(List.of(resting));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20020.0)); // still through the SL

        reconciler.sweep();

        verify(activePositions).closePosition(11L, "playbook-stop:SL");
    }

    @Test
    void live_price_resolved_once_per_instrument_across_rows() {
        // Two rows on the same instrument must hit the live-price port only once per sweep (per-instrument cache).
        TradeExecutionRecord a = makeActive(12L, "MNQ", "LONG", bd("20025"), bd("20090"));
        TradeExecutionRecord b = makeActive(13L, "MNQ", "LONG", bd("20025"), bd("20090"));
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.ACTIVE))
            .thenReturn(List.of(a, b));
        when(livePricePort.current(Instrument.MNQ)).thenReturn(liveQuote(20020.0));

        reconciler.sweep();

        verify(livePricePort, times(1)).current(Instrument.MNQ);
        verify(activePositions).closePosition(12L, "playbook-stop:SL");
        verify(activePositions).closePosition(13L, "playbook-stop:SL");
    }

    private void stubActive(TradeExecutionRecord row) {
        when(repo.findByTriggerSourceAndStatus(ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.ACTIVE))
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

    /** A genuinely live + fresh quote — passes the reconciler's source + freshness guard. */
    private static Optional<LivePriceSnapshot> liveQuote(double price) {
        return Optional.of(new LivePriceSnapshot(price, Instant.now(), "LIVE_PUSH"));
    }
}

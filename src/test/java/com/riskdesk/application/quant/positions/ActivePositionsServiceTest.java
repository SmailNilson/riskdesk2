package com.riskdesk.application.quant.positions;

import com.riskdesk.application.dto.ActivePositionView;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.positions.ActivePositionChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivePositionsServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-30T13:30:00Z");

    private TradeExecutionRepositoryPort repo;
    private LivePricePort livePricePort;
    private ApplicationEventPublisher eventPublisher;
    private ActivePositionsService service;

    @BeforeEach
    void setUp() {
        repo = mock(TradeExecutionRepositoryPort.class);
        livePricePort = mock(LivePricePort.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ActivePositionsService(repo, livePricePort, eventPublisher,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void list_active_long_computes_positive_pnl_when_price_up() {
        // MNQ LONG entry 27450 → currentPrice 27478.25 → +28.25 pts
        // MNQ tick=0.25, tickValue=$0.50 → 28.25 / 0.25 = 113 ticks * $0.50 = $56.50
        TradeExecutionRecord record = makeRecord(1L, "MNQ", "BUY", new BigDecimal("27450.00"));
        when(repo.findAllActive()).thenReturn(List.of(record));
        when(livePricePort.current(Instrument.MNQ))
            .thenReturn(Optional.of(new LivePriceSnapshot(27478.25, NOW, "LIVE_PUSH")));

        List<ActivePositionView> result = service.listActive();

        assertThat(result).hasSize(1);
        ActivePositionView v = result.get(0);
        assertThat(v.instrument()).isEqualTo("MNQ");
        assertThat(v.direction()).isEqualTo("LONG");
        assertThat(v.currentPrice()).isEqualByComparingTo("27478.25");
        assertThat(v.pnlPoints()).isEqualByComparingTo("28.2500");
        assertThat(v.pnlDollars()).isEqualByComparingTo("56.50");
        assertThat(v.closable()).isTrue();
    }

    @Test
    void list_active_short_computes_positive_pnl_when_price_down() {
        // MGC SHORT entry 4650 → current 4642 → +8 pts.
        // MGC tick=0.10, tickValue=$1 → 80 ticks * $1 = $80
        TradeExecutionRecord record = makeRecord(2L, "MGC", "SELL", new BigDecimal("4650.00"));
        when(repo.findAllActive()).thenReturn(List.of(record));
        when(livePricePort.current(Instrument.MGC))
            .thenReturn(Optional.of(new LivePriceSnapshot(4642.0, NOW, "LIVE_PUSH")));

        List<ActivePositionView> result = service.listActive();

        ActivePositionView v = result.get(0);
        assertThat(v.direction()).isEqualTo("SHORT");
        assertThat(v.pnlPoints()).isEqualByComparingTo("8.0000");
        assertThat(v.pnlDollars()).isEqualByComparingTo("80.00");
    }

    @Test
    void list_active_marks_terminal_as_not_closable() {
        // The repo contract is supposed to filter terminal rows out, but the
        // closable flag must still be correct if one ever leaks through (e.g.
        // a transition mid-snapshot).
        TradeExecutionRecord closed = makeRecord(3L, "MCL", "BUY", new BigDecimal("80.00"));
        closed.setStatus(ExecutionStatus.CLOSED);
        when(repo.findAllActive()).thenReturn(List.of(closed));
        when(livePricePort.current(Instrument.MCL))
            .thenReturn(Optional.of(new LivePriceSnapshot(80.50, NOW, "LIVE_PUSH")));

        List<ActivePositionView> result = service.listActive();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).closable()).isFalse();
    }

    @Test
    void close_pending_transitions_to_cancelled_and_publishes_event() {
        TradeExecutionRecord pending = makeRecord(10L, "MNQ", "BUY", new BigDecimal("27000"));
        pending.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        when(repo.findByIdForUpdate(10L)).thenReturn(Optional.of(pending));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<ActivePositionView> result = service.closePosition(10L, "tester");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(pending.getStatusReason()).contains("Cancelled by tester");
        verify(eventPublisher, times(1)).publishEvent(any(ActivePositionChangedEvent.class));
    }

    @Test
    void close_active_transitions_to_exit_submitted() {
        TradeExecutionRecord active = makeRecord(11L, "MGC", "SELL", new BigDecimal("4650"));
        active.setStatus(ExecutionStatus.ACTIVE);
        when(repo.findByIdForUpdate(11L)).thenReturn(Optional.of(active));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<ActivePositionView> result = service.closePosition(11L, "operator");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.EXIT_SUBMITTED);
        assertThat(active.getExitSubmittedAt()).isEqualTo(NOW);
    }

    @Test
    void close_terminal_row_is_idempotent() {
        TradeExecutionRecord closed = makeRecord(12L, "MNQ", "BUY", new BigDecimal("27000"));
        closed.setStatus(ExecutionStatus.CLOSED);
        when(repo.findByIdForUpdate(12L)).thenReturn(Optional.of(closed));

        Optional<ActivePositionView> result = service.closePosition(12L, "tester");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.CLOSED);
    }

    @Test
    void close_unknown_id_returns_empty() {
        when(repo.findByIdForUpdate(999L)).thenReturn(Optional.empty());
        assertThat(service.closePosition(999L, "tester")).isEmpty();
    }

    private static TradeExecutionRecord makeRecord(long id, String instrument, String action, BigDecimal entry) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(id);
        r.setInstrument(instrument);
        r.setAction(action);
        r.setQuantity(1);
        r.setStatus(ExecutionStatus.ACTIVE);
        r.setNormalizedEntryPrice(entry);
        r.setVirtualStopLoss(entry.subtract(new BigDecimal("25")));
        r.setVirtualTakeProfit(entry.add(new BigDecimal("40")));
        r.setTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM);
        r.setCreatedAt(NOW);
        return r;
    }
}

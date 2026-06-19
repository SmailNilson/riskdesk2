package com.riskdesk.application.quant.positions;

import com.riskdesk.application.dto.ActivePositionView;
import com.riskdesk.application.execution.OrderRouter;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.IntentKind;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.positions.ActivePositionChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivePositionsServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-30T13:30:00Z");

    private TradeExecutionRepositoryPort repo;
    private LivePricePort livePricePort;
    private ApplicationEventPublisher eventPublisher;
    private OrderRouter orderRouter;
    private IbkrOrderService ibkrOrderService;
    private ActivePositionsService service;

    @BeforeEach
    void setUp() {
        repo = mock(TradeExecutionRepositoryPort.class);
        livePricePort = mock(LivePricePort.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        orderRouter = mock(OrderRouter.class);
        ibkrOrderService = mock(IbkrOrderService.class);
        service = new ActivePositionsService(repo, livePricePort, eventPublisher,
            orderRouter, ibkrOrderService, Clock.fixed(NOW, ZoneOffset.UTC));
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
        verify(orderRouter, never()).route(any());
    }

    @Test
    void close_active_routes_flatten_through_unified_router() {
        TradeExecutionRecord active = makeRecord(11L, "MGC", "SELL", new BigDecimal("4650"));
        active.setStatus(ExecutionStatus.ACTIVE);
        active.setBrokerAccountId("DU111");
        active.setTimeframe("5m");
        when(repo.findByIdForUpdate(11L)).thenReturn(Optional.of(active));
        when(livePricePort.current(Instrument.MGC))
            .thenReturn(Optional.of(new LivePriceSnapshot(4642.0, NOW, "LIVE_PUSH")));
        when(orderRouter.route(any())).thenAnswer(inv -> {
            // The router persists the EXIT_SUBMITTED transition itself.
            active.setStatus(ExecutionStatus.EXIT_SUBMITTED);
            active.setExitSubmittedAt(NOW);
            return RoutingResult.tracked(RoutingOutcome.ROUTED, 11L, 555L);
        });
        when(repo.findById(11L)).thenReturn(Optional.of(active));

        Optional<ActivePositionView> result = service.closePosition(11L, "operator");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.EXIT_SUBMITTED);

        ArgumentCaptor<TradeIntent> captor = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderRouter).route(captor.capture());
        TradeIntent intent = captor.getValue();
        assertThat(intent.kind()).isEqualTo(IntentKind.FLATTEN);
        assertThat(intent.instrument()).isEqualTo(Instrument.MGC);
        assertThat(intent.timeframe()).isEqualTo("5m");
        assertThat(intent.source()).isEqualTo(ExecutionTriggerSource.QUANT_AUTO_ARM);
        assertThat(intent.brokerAccountId()).isEqualTo("DU111");
        assertThat(intent.quantity()).isEqualTo(1);
        assertThat(intent.limitPrice()).isEqualByComparingTo("4642.0");
        verify(eventPublisher, times(1)).publishEvent(any(ActivePositionChangedEvent.class));
    }

    @Test
    void close_active_falls_back_to_local_mark_when_ibkr_disabled() {
        TradeExecutionRecord active = makeRecord(12L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        when(repo.findByIdForUpdate(12L)).thenReturn(Optional.of(active));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRouter.route(any()))
            .thenReturn(RoutingResult.of(RoutingOutcome.SKIPPED_IBKR_DISABLED, "IBKR off"));

        Optional<ActivePositionView> result = service.closePosition(12L, "operator");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.EXIT_SUBMITTED);
        assertThat(active.getExitSubmittedAt()).isEqualTo(NOW);
        assertThat(active.getStatusReason()).contains("IBKR disabled");
    }

    @Test
    void close_failed_routing_throws_conflict() {
        TradeExecutionRecord active = makeRecord(13L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        when(repo.findByIdForUpdate(13L)).thenReturn(Optional.of(active));
        when(orderRouter.route(any()))
            .thenReturn(RoutingResult.of(RoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE, "broker truth unavailable"));

        assertThatThrownBy(() -> service.closePosition(13L, "operator"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("broker truth unavailable");
    }

    @Test
    void close_unfilled_entry_submitted_delegates_to_broker_cancel() {
        TradeExecutionRecord resting = makeRecord(14L, "MNQ", "BUY", new BigDecimal("27000"));
        resting.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        resting.setIbkrOrderId(9001);
        when(repo.findByIdForUpdate(14L)).thenReturn(Optional.of(resting));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ibkrOrderService.cancelOrder(9001)).thenReturn("Cancelled");

        Optional<ActivePositionView> result = service.closePosition(14L, "operator");

        assertThat(result).isPresent();
        // The row is NOT finalized here — the broker's Cancelled callback owns that transition.
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
        assertThat(resting.getStatusReason()).contains("Cancel requested");
        verify(ibkrOrderService, times(1)).cancelOrder(9001);
        verify(orderRouter, never()).route(any());
    }

    @Test
    void cancel_entry_pending_cancels_locally() {
        TradeExecutionRecord pending = makeRecord(20L, "MNQ", "BUY", new BigDecimal("27000"));
        pending.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        when(repo.findByIdForUpdate(20L)).thenReturn(Optional.of(pending));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<ActivePositionView> result = service.cancelEntry(20L, "tester");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.CANCELLED);
        verify(ibkrOrderService, never()).cancelOrder(anyInt());
    }

    @Test
    void cancel_entry_with_fills_throws_conflict() {
        TradeExecutionRecord partial = makeRecord(21L, "MNQ", "BUY", new BigDecimal("27000"));
        partial.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        partial.setIbkrOrderId(9002);
        partial.setFilledQuantity(new BigDecimal("1"));
        when(repo.findByIdForUpdate(21L)).thenReturn(Optional.of(partial));

        assertThatThrownBy(() -> service.cancelEntry(21L, "tester"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("use close");
        verify(ibkrOrderService, never()).cancelOrder(anyInt());
    }

    @Test
    void cancel_entry_on_active_position_throws_conflict() {
        TradeExecutionRecord active = makeRecord(22L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        when(repo.findByIdForUpdate(22L)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.cancelEntry(22L, "tester"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("use close");
    }

    @Test
    void cancel_entry_without_broker_order_id_throws_conflict() {
        TradeExecutionRecord resting = makeRecord(23L, "MNQ", "BUY", new BigDecimal("27000"));
        resting.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        resting.setIbkrOrderId(null);
        when(repo.findByIdForUpdate(23L)).thenReturn(Optional.of(resting));

        assertThatThrownBy(() -> service.cancelEntry(23L, "tester"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no broker order id");
    }

    @Test
    void close_terminal_row_is_idempotent() {
        TradeExecutionRecord closed = makeRecord(12L, "MNQ", "BUY", new BigDecimal("27000"));
        closed.setStatus(ExecutionStatus.CLOSED);
        when(repo.findByIdForUpdate(12L)).thenReturn(Optional.of(closed));

        Optional<ActivePositionView> result = service.closePosition(12L, "tester");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.CLOSED);
        verify(orderRouter, never()).route(any());
    }

    @Test
    void close_unknown_id_returns_empty() {
        when(repo.findByIdForUpdate(999L)).thenReturn(Optional.empty());
        assertThat(service.closePosition(999L, "tester")).isEmpty();
        assertThat(service.cancelEntry(999L, "tester")).isEmpty();
    }

    @Test
    void reverse_active_long_routes_reverse_to_short_through_router() {
        TradeExecutionRecord active = makeRecord(30L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        active.setBrokerAccountId("DU111");
        active.setTimeframe("manual");
        when(repo.findByIdForUpdate(30L)).thenReturn(Optional.of(active));
        when(livePricePort.current(Instrument.MNQ))
            .thenReturn(Optional.of(new LivePriceSnapshot(27010.0, NOW, "LIVE_PUSH")));
        when(orderRouter.route(any())).thenReturn(RoutingResult.tracked(RoutingOutcome.ROUTED, 30L, 777L));
        when(repo.findById(30L)).thenReturn(Optional.of(active));

        Optional<ActivePositionView> result = service.reversePosition(30L, "operator");

        assertThat(result).isPresent();
        ArgumentCaptor<TradeIntent> captor = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderRouter).route(captor.capture());
        TradeIntent intent = captor.getValue();
        assertThat(intent.kind()).isEqualTo(IntentKind.REVERSE);
        assertThat(intent.side()).isEqualTo(Side.SHORT);
        assertThat(intent.instrument()).isEqualTo(Instrument.MNQ);
        assertThat(intent.timeframe()).isEqualTo("manual");
        assertThat(intent.brokerAccountId()).isEqualTo("DU111");
        assertThat(intent.quantity()).isEqualTo(1);
        assertThat(intent.limitPrice()).isEqualByComparingTo("27010.0");
        verify(eventPublisher, times(1)).publishEvent(any(ActivePositionChangedEvent.class));
    }

    @Test
    void reverse_active_short_targets_long_side() {
        TradeExecutionRecord active = makeRecord(31L, "MGC", "SELL", new BigDecimal("4650"));
        active.setStatus(ExecutionStatus.ACTIVE);
        when(repo.findByIdForUpdate(31L)).thenReturn(Optional.of(active));
        when(livePricePort.current(Instrument.MGC))
            .thenReturn(Optional.of(new LivePriceSnapshot(4648.0, NOW, "LIVE_PUSH")));
        when(orderRouter.route(any())).thenReturn(RoutingResult.tracked(RoutingOutcome.ROUTED, 31L, 778L));

        service.reversePosition(31L, "operator");

        ArgumentCaptor<TradeIntent> captor = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderRouter).route(captor.capture());
        assertThat(captor.getValue().kind()).isEqualTo(IntentKind.REVERSE);
        assertThat(captor.getValue().side()).isEqualTo(Side.LONG);
    }

    @Test
    void reverse_pending_throws_conflict_without_routing() {
        TradeExecutionRecord pending = makeRecord(32L, "MNQ", "BUY", new BigDecimal("27000"));
        pending.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        when(repo.findByIdForUpdate(32L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.reversePosition(32L, "operator"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no live position to reverse");
        verify(orderRouter, never()).route(any());
    }

    @Test
    void reverse_unfilled_resting_entry_throws_conflict() {
        TradeExecutionRecord resting = makeRecord(33L, "MNQ", "BUY", new BigDecimal("27000"));
        resting.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        when(repo.findByIdForUpdate(33L)).thenReturn(Optional.of(resting));

        assertThatThrownBy(() -> service.reversePosition(33L, "operator"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no live position to reverse");
        verify(orderRouter, never()).route(any());
    }

    @Test
    void reverse_close_in_flight_throws_conflict_without_routing() {
        // A close already resting at the broker (EXIT_SUBMITTED) must resolve before a reverse — the
        // router would otherwise open the opposite leg on top of the un-flattened position.
        TradeExecutionRecord exiting = makeRecord(36L, "MNQ", "BUY", new BigDecimal("27000"));
        exiting.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        when(repo.findByIdForUpdate(36L)).thenReturn(Optional.of(exiting));

        assertThatThrownBy(() -> service.reversePosition(36L, "operator"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("a close is already in progress");
        verify(orderRouter, never()).route(any());
    }

    @Test
    void reverse_terminal_row_is_idempotent() {
        TradeExecutionRecord closed = makeRecord(34L, "MNQ", "BUY", new BigDecimal("27000"));
        closed.setStatus(ExecutionStatus.CLOSED);
        when(repo.findByIdForUpdate(34L)).thenReturn(Optional.of(closed));

        Optional<ActivePositionView> result = service.reversePosition(34L, "operator");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.CLOSED);
        verify(orderRouter, never()).route(any());
    }

    @Test
    void reverse_failed_routing_throws_conflict() {
        TradeExecutionRecord active = makeRecord(35L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        when(repo.findByIdForUpdate(35L)).thenReturn(Optional.of(active));
        when(livePricePort.current(Instrument.MNQ))
            .thenReturn(Optional.of(new LivePriceSnapshot(27010.0, NOW, "LIVE_PUSH")));
        when(orderRouter.route(any()))
            .thenReturn(RoutingResult.of(RoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE, "bridge down"));

        assertThatThrownBy(() -> service.reversePosition(35L, "operator"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bridge down");
    }

    @Test
    void modify_protection_updates_both_levels_and_publishes() {
        TradeExecutionRecord active = makeRecord(40L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        active.setTriggerSource(ExecutionTriggerSource.MANUAL_QUANT_PANEL);
        when(repo.findByIdForUpdate(40L)).thenReturn(Optional.of(active));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<ActivePositionView> result =
            service.modifyProtection(40L, new BigDecimal("26950"), new BigDecimal("27100"), "operator");

        assertThat(result).isPresent();
        assertThat(active.getVirtualStopLoss()).isEqualByComparingTo("26950");
        assertThat(active.getVirtualTakeProfit()).isEqualByComparingTo("27100");
        verify(eventPublisher, times(1)).publishEvent(any(ActivePositionChangedEvent.class));
    }

    @Test
    void modify_protection_long_sl_above_entry_throws() {
        TradeExecutionRecord active = makeRecord(41L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        active.setTriggerSource(ExecutionTriggerSource.MANUAL_QUANT_PANEL);
        when(repo.findByIdForUpdate(41L)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.modifyProtection(41L, new BigDecimal("27050"), null, "operator"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LONG stopLoss must be below");
        verify(repo, never()).save(any());
    }

    @Test
    void modify_protection_updates_only_take_profit_when_sl_null() {
        TradeExecutionRecord active = makeRecord(42L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        active.setTriggerSource(ExecutionTriggerSource.MANUAL_QUANT_PANEL);
        when(repo.findByIdForUpdate(42L)).thenReturn(Optional.of(active));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.modifyProtection(42L, null, new BigDecimal("27120"), "operator");

        assertThat(active.getVirtualStopLoss()).isEqualByComparingTo("26975"); // unchanged (entry - 25)
        assertThat(active.getVirtualTakeProfit()).isEqualByComparingTo("27120");
    }

    @Test
    void modify_protection_terminal_row_throws_conflict() {
        TradeExecutionRecord closed = makeRecord(43L, "MNQ", "BUY", new BigDecimal("27000"));
        closed.setStatus(ExecutionStatus.CLOSED);
        when(repo.findByIdForUpdate(43L)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.modifyProtection(43L, new BigDecimal("26950"), null, "operator"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("terminal");
    }

    @Test
    void modify_protection_without_levels_throws() {
        assertThatThrownBy(() -> service.modifyProtection(44L, null, null, "operator"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one");
    }

    @Test
    void reduce_partial_routes_reduce_intent_for_held_side() {
        TradeExecutionRecord active = makeRecord(50L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        active.setTriggerSource(ExecutionTriggerSource.MANUAL_QUANT_PANEL);
        active.setQuantity(3);
        when(repo.findByIdForUpdate(50L)).thenReturn(Optional.of(active));
        when(livePricePort.current(Instrument.MNQ))
            .thenReturn(Optional.of(new LivePriceSnapshot(27010.0, NOW, "LIVE")));
        when(orderRouter.route(any())).thenReturn(RoutingResult.tracked(RoutingOutcome.ROUTED, 50L, 999L));
        when(repo.findById(50L)).thenReturn(Optional.of(active));

        Optional<ActivePositionView> result = service.reducePosition(50L, 1, "operator");

        assertThat(result).isPresent();
        ArgumentCaptor<TradeIntent> captor = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderRouter).route(captor.capture());
        TradeIntent intent = captor.getValue();
        assertThat(intent.kind()).isEqualTo(IntentKind.REDUCE);
        assertThat(intent.side()).isEqualTo(Side.LONG);   // reduce the HELD side (BUY = LONG)
        assertThat(intent.quantity()).isEqualTo(1);
        verify(eventPublisher, times(1)).publishEvent(any(ActivePositionChangedEvent.class));
    }

    @Test
    void reduce_whole_size_delegates_to_full_close() {
        TradeExecutionRecord active = makeRecord(51L, "MGC", "SELL", new BigDecimal("4650"));
        active.setStatus(ExecutionStatus.ACTIVE);
        active.setQuantity(2);
        when(repo.findByIdForUpdate(51L)).thenReturn(Optional.of(active));
        when(livePricePort.current(Instrument.MGC))
            .thenReturn(Optional.of(new LivePriceSnapshot(4648.0, NOW, "LIVE")));
        when(orderRouter.route(any())).thenReturn(RoutingResult.tracked(RoutingOutcome.ROUTED, 51L, 999L));
        when(repo.findById(51L)).thenReturn(Optional.of(active));

        service.reducePosition(51L, 2, "operator"); // qty == position → full close

        ArgumentCaptor<TradeIntent> captor = ArgumentCaptor.forClass(TradeIntent.class);
        verify(orderRouter).route(captor.capture());
        assertThat(captor.getValue().kind()).isEqualTo(IntentKind.FLATTEN); // delegated to closePosition
    }

    @Test
    void reduce_non_active_throws_conflict_without_routing() {
        TradeExecutionRecord resting = makeRecord(52L, "MNQ", "BUY", new BigDecimal("27000"));
        resting.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        resting.setQuantity(3);
        when(repo.findByIdForUpdate(52L)).thenReturn(Optional.of(resting));

        assertThatThrownBy(() -> service.reducePosition(52L, 1, "operator"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("only a live ACTIVE position");
        verify(orderRouter, never()).route(any());
    }

    @Test
    void reduce_non_positive_qty_throws_illegal_argument() {
        assertThatThrownBy(() -> service.reducePosition(53L, 0, "operator"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reduce quantity must be >= 1");
    }

    @Test
    void modify_protection_refuses_strategy_owned_row() {
        // A strategy (WTX/auto-arm/playbook) stores its OWN trailing SL/TP in these fields — the chart must
        // not overwrite them. makeRecord defaults to QUANT_AUTO_ARM (a strategy source).
        TradeExecutionRecord strategy = makeRecord(60L, "MNQ", "BUY", new BigDecimal("27000"));
        strategy.setStatus(ExecutionStatus.ACTIVE);
        when(repo.findByIdForUpdate(60L)).thenReturn(Optional.of(strategy));

        assertThatThrownBy(() -> service.modifyProtection(60L, new BigDecimal("26950"), null, "operator"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("manual chart position");
        verify(repo, never()).save(any());
    }

    @Test
    void modify_protection_rejects_stop_loss_that_rounds_onto_entry() {
        // MNQ tick 0.25, entry 27000.00. SL 26999.90 passes a raw "< entry" check but normalizes (HALF_UP) to
        // 27000.00 == entry — the geometry check must run on the NORMALIZED value and reject it.
        TradeExecutionRecord active = makeRecord(61L, "MNQ", "BUY", new BigDecimal("27000.00"));
        active.setStatus(ExecutionStatus.ACTIVE);
        active.setTriggerSource(ExecutionTriggerSource.MANUAL_QUANT_PANEL);
        when(repo.findByIdForUpdate(61L)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.modifyProtection(61L, new BigDecimal("26999.90"), null, "operator"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LONG stopLoss must be below");
        verify(repo, never()).save(any());
    }

    @Test
    void reduce_partial_refuses_strategy_owned_row() {
        // A partial scale-out on a strategy row would desync it — only a full close is allowed as an override.
        TradeExecutionRecord strategy = makeRecord(62L, "MNQ", "BUY", new BigDecimal("27000"));
        strategy.setStatus(ExecutionStatus.ACTIVE);
        strategy.setQuantity(3);
        when(repo.findByIdForUpdate(62L)).thenReturn(Optional.of(strategy));

        assertThatThrownBy(() -> service.reducePosition(62L, 1, "operator"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("manual chart position");
        verify(orderRouter, never()).route(any());
    }

    @Test
    void reverse_falls_back_to_local_mark_when_ibkr_disabled() {
        TradeExecutionRecord active = makeRecord(63L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        when(repo.findByIdForUpdate(63L)).thenReturn(Optional.of(active));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRouter.route(any()))
            .thenReturn(RoutingResult.of(RoutingOutcome.SKIPPED_IBKR_DISABLED, "IBKR off"));

        Optional<ActivePositionView> result = service.reversePosition(63L, "operator");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.EXIT_SUBMITTED);
        assertThat(active.getStatusReason()).contains("IBKR disabled");
    }

    @Test
    void reduce_partial_is_noop_when_ibkr_disabled() {
        TradeExecutionRecord active = makeRecord(64L, "MNQ", "BUY", new BigDecimal("27000"));
        active.setStatus(ExecutionStatus.ACTIVE);
        active.setTriggerSource(ExecutionTriggerSource.MANUAL_QUANT_PANEL);
        active.setQuantity(3);
        when(repo.findByIdForUpdate(64L)).thenReturn(Optional.of(active));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRouter.route(any()))
            .thenReturn(RoutingResult.of(RoutingOutcome.SKIPPED_IBKR_DISABLED, "IBKR off"));

        Optional<ActivePositionView> result = service.reducePosition(64L, 1, "operator");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(ExecutionStatus.ACTIVE); // unchanged — no broker, no fill
        assertThat(active.getQuantity()).isEqualTo(3);
        assertThat(active.getStatusReason()).contains("IBKR disabled");
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

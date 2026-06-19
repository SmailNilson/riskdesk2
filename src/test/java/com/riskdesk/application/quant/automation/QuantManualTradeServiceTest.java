package com.riskdesk.application.quant.automation;

import com.riskdesk.application.quant.automation.QuantManualTradeService.ManualDirection;
import com.riskdesk.application.quant.automation.QuantManualTradeService.ManualEntryType;
import com.riskdesk.application.quant.automation.QuantManualTradeService.ManualTradeRequest;
import com.riskdesk.application.quant.service.QuantGateService;
import com.riskdesk.application.service.ExecutionManagerService;
import com.riskdesk.application.service.SubmitEntryOrderCommand;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantManualTradeServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-30T13:30:00Z");

    private RecordingRepo repo;
    private ExecutionManagerService executionManager;
    private QuantGateService quantGateService;
    private QuantAutoArmProperties autoArmProps;
    private QuantManualTradeService service;

    @BeforeEach
    void setUp() {
        repo = new RecordingRepo();
        executionManager = mock(ExecutionManagerService.class);
        quantGateService = mock(QuantGateService.class);
        autoArmProps = new QuantAutoArmProperties();
        autoArmProps.setBrokerAccountId("DU1234567");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new QuantManualTradeService(repo, executionManager, quantGateService, autoArmProps, clock);
    }

    @Test
    void place_long_limit_creates_pending_execution_without_submitting() {
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.LIMIT,
            new BigDecimal("20000.00"), new BigDecimal("19975.00"),
            new BigDecimal("20040.00"), new BigDecimal("20070.00"), 1
        );

        TradeExecutionRecord placed = service.place(Instrument.MNQ, req, "operator");

        assertThat(placed.getId()).isNotNull();
        assertThat(placed.getAction()).isEqualTo("LONG");
        assertThat(placed.getInstrument()).isEqualTo("MNQ");
        assertThat(placed.getStatus()).isEqualTo(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        assertThat(placed.getTriggerSource()).isEqualTo(ExecutionTriggerSource.MANUAL_QUANT_PANEL);
        assertThat(placed.getMentorSignalReviewId()).isNull();
        assertThat(placed.getRequestedBy()).isEqualTo("operator");
        assertThat(placed.getNormalizedEntryPrice()).isEqualByComparingTo("20000.00");
        assertThat(placed.getVirtualStopLoss()).isEqualByComparingTo("19975.00");
        assertThat(placed.getVirtualTakeProfit()).isEqualByComparingTo("20040.00");
        verify(executionManager, never()).submitEntryOrder(any());
    }

    @Test
    void place_short_market_uses_live_price_and_submits_immediately() {
        when(quantGateService.latestSnapshot(Instrument.MNQ)).thenReturn(snapshotAt(20100.0));
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.SHORT, ManualEntryType.MARKET,
            null, new BigDecimal("20125.00"),
            new BigDecimal("20060.00"), null, 2
        );
        TradeExecutionRecord submitted = new TradeExecutionRecord();
        submitted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        when(executionManager.submitEntryOrder(any(SubmitEntryOrderCommand.class))).thenReturn(submitted);

        TradeExecutionRecord result = service.place(Instrument.MNQ, req, "operator");

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
        assertThat(repo.created).hasSize(1);
        TradeExecutionRecord created = repo.created.get(0);
        assertThat(created.getAction()).isEqualTo("SHORT");
        assertThat(created.getQuantity()).isEqualTo(2);
        assertThat(created.getNormalizedEntryPrice()).isEqualByComparingTo("20100.00");
        verify(executionManager, times(1)).submitEntryOrder(any(SubmitEntryOrderCommand.class));
    }

    @Test
    void place_limit_with_submit_immediately_submits_to_broker() {
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.LIMIT,
            new BigDecimal("20000.00"), new BigDecimal("19975.00"),
            new BigDecimal("20040.00"), null, 1, null, true
        );
        TradeExecutionRecord submitted = new TradeExecutionRecord();
        submitted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        when(executionManager.submitEntryOrder(any(SubmitEntryOrderCommand.class))).thenReturn(submitted);

        TradeExecutionRecord result = service.place(Instrument.MNQ, req, "chart");

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
        verify(executionManager, times(1)).submitEntryOrder(any(SubmitEntryOrderCommand.class));
    }

    @Test
    void place_uses_request_broker_account_over_auto_arm_config() {
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.SHORT, ManualEntryType.LIMIT,
            new BigDecimal("20100.00"), new BigDecimal("20125.00"),
            new BigDecimal("20060.00"), null, 1, "DU7654321", null
        );

        TradeExecutionRecord placed = service.place(Instrument.MNQ, req, "chart");

        assertThat(placed.getBrokerAccountId()).isEqualTo("DU7654321");
        assertThat(placed.getAction()).isEqualTo("SHORT");
        verify(executionManager, never()).submitEntryOrder(any());
    }

    @Test
    void place_missing_required_fields_throws_illegal_argument() {
        ManualTradeRequest noStop = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.LIMIT,
            new BigDecimal("20000"), null, new BigDecimal("20040"), null, 1
        );
        assertThatThrownBy(() -> service.place(Instrument.MNQ, noStop, "operator"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stopLoss");

        ManualTradeRequest noTp = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.LIMIT,
            new BigDecimal("20000"), new BigDecimal("19975"), null, null, 1
        );
        assertThatThrownBy(() -> service.place(Instrument.MNQ, noTp, "operator"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("takeProfit1");
    }

    @Test
    void place_invalid_geometry_long_with_sl_above_entry_throws() {
        ManualTradeRequest bad = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.LIMIT,
            new BigDecimal("20000"), new BigDecimal("20050"),
            new BigDecimal("20040"), null, 1
        );
        assertThatThrownBy(() -> service.place(Instrument.MNQ, bad, "operator"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LONG stopLoss must be below");
    }

    @Test
    void place_market_without_live_price_throws_illegal_state() {
        when(quantGateService.latestSnapshot(Instrument.MNQ)).thenReturn(null);
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.MARKET,
            null, new BigDecimal("19975"), new BigDecimal("20040"), null, 1
        );
        assertThatThrownBy(() -> service.place(Instrument.MNQ, req, "operator"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot resolve a live price");
    }

    @Test
    void place_long_stop_persists_stop_order_type_and_trigger() {
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.STOP,
            null, new BigDecimal("20025.00"), new BigDecimal("20090.00"), null,
            1, null, null, new BigDecimal("20050.00"));

        TradeExecutionRecord placed = service.place(Instrument.MNQ, req, "operator");

        assertThat(placed.getStatus()).isEqualTo(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        assertThat(placed.getOrderType()).isEqualTo("STOP");
        assertThat(placed.getTriggerPrice()).isEqualByComparingTo("20050.00");
        assertThat(placed.getNormalizedEntryPrice()).isEqualByComparingTo("20050.00");
        assertThat(placed.getVirtualStopLoss()).isEqualByComparingTo("20025.00");
        verify(executionManager, never()).submitEntryOrder(any());
    }

    @Test
    void place_long_stop_limit_persists_cap_as_entry_and_trigger() {
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.STOP_LIMIT,
            new BigDecimal("20055.00"), new BigDecimal("20025.00"), new BigDecimal("20090.00"), null,
            1, null, null, new BigDecimal("20050.00"));

        TradeExecutionRecord placed = service.place(Instrument.MNQ, req, "operator");

        assertThat(placed.getOrderType()).isEqualTo("STOP_LIMIT");
        assertThat(placed.getTriggerPrice()).isEqualByComparingTo("20050.00");
        assertThat(placed.getNormalizedEntryPrice()).isEqualByComparingTo("20055.00"); // limit cap
    }

    @Test
    void place_stop_with_submit_immediately_submits_to_broker() {
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.STOP,
            null, new BigDecimal("20025.00"), new BigDecimal("20090.00"), null,
            1, null, true, new BigDecimal("20050.00"));
        TradeExecutionRecord submitted = new TradeExecutionRecord();
        submitted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        when(executionManager.submitEntryOrder(any(SubmitEntryOrderCommand.class))).thenReturn(submitted);

        service.place(Instrument.MNQ, req, "chart");

        verify(executionManager, times(1)).submitEntryOrder(any(SubmitEntryOrderCommand.class));
        assertThat(repo.created.get(0).getOrderType()).isEqualTo("STOP");
    }

    @Test
    void place_long_stop_below_live_price_throws_breakout() {
        when(quantGateService.latestSnapshot(Instrument.MNQ)).thenReturn(snapshotAt(20100.0));
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.STOP,
            null, new BigDecimal("20025.00"), new BigDecimal("20090.00"), null,
            1, null, null, new BigDecimal("20050.00")); // trigger below live → not a breakout
        assertThatThrownBy(() -> service.place(Instrument.MNQ, req, "operator"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("above the live price");
    }

    @Test
    void place_stop_without_trigger_throws() {
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.STOP,
            null, new BigDecimal("20025.00"), new BigDecimal("20090.00"), null,
            1, null, null, null);
        assertThatThrownBy(() -> service.place(Instrument.MNQ, req, "operator"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("triggerPrice is required");
    }

    @Test
    void place_long_stop_limit_cap_below_trigger_throws() {
        ManualTradeRequest req = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.STOP_LIMIT,
            new BigDecimal("20040.00"), new BigDecimal("20025.00"), new BigDecimal("20090.00"), null,
            1, null, null, new BigDecimal("20050.00")); // cap 20040 < trigger 20050
        assertThatThrownBy(() -> service.place(Instrument.MNQ, req, "operator"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cap must be >= the trigger");
    }

    private static QuantSnapshot snapshotAt(double price) {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        for (Gate g : Gate.values()) {
            gates.put(g, new GateResult(true, "ok"));
        }
        return new QuantSnapshot(
            Instrument.MNQ, gates, 0, price, "test", 0.0,
            ZonedDateTime.now(),
            List.of(), List.of(), 0, false
        );
    }

    private static class RecordingRepo implements TradeExecutionRepositoryPort {
        final List<TradeExecutionRecord> created = new ArrayList<>();
        final Map<Long, TradeExecutionRecord> byId = new HashMap<>();
        long idSeq = 200;

        @Override public TradeExecutionRecord createIfAbsent(TradeExecutionRecord execution) {
            execution.setId(++idSeq);
            created.add(execution);
            byId.put(execution.getId(), execution);
            return execution;
        }
        @Override public TradeExecutionRecord save(TradeExecutionRecord execution) {
            byId.put(execution.getId(), execution);
            return execution;
        }
        @Override public Optional<TradeExecutionRecord> findById(Long id) { return Optional.ofNullable(byId.get(id)); }
        @Override public Optional<TradeExecutionRecord> findByIdForUpdate(Long id) { return findById(id); }
        @Override public Optional<TradeExecutionRecord> findByMentorSignalReviewId(Long id) { return Optional.empty(); }
        @Override public List<TradeExecutionRecord> findByMentorSignalReviewIds(Collection<Long> ids) { return List.of(); }
        @Override public Optional<TradeExecutionRecord> findByIbkrOrderId(Integer id) { return Optional.empty(); }
        @Override public Optional<TradeExecutionRecord> findByPermId(Long permId) { return Optional.empty(); }
        @Override public Optional<TradeExecutionRecord> findByExecutionKey(String key) { return Optional.empty(); }
        @Override public Optional<TradeExecutionRecord> findActiveByInstrument(String instrument) { return Optional.empty(); }
        @Override public Optional<TradeExecutionRecord> findActiveByInstrumentAndTriggerSource(String instrument, ExecutionTriggerSource src) { return Optional.empty(); }
        @Override public Optional<TradeExecutionRecord> findActiveByInstrumentAndTimeframeAndTriggerSource(String instrument, String timeframe, ExecutionTriggerSource src) { return Optional.empty(); }
        @Override public List<TradeExecutionRecord> findPendingByTriggerSource(ExecutionTriggerSource src) { return List.of(); }
        @Override public List<TradeExecutionRecord> findByTriggerSourceAndStatus(ExecutionTriggerSource src, ExecutionStatus status) {
            return byId.values().stream().filter(r -> r.getTriggerSource() == src && r.getStatus() == status).toList();
        }
        @Override public List<TradeExecutionRecord> findAllActive() { return List.of(); }
    }
}

package com.riskdesk.application.quant.simulation;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Auto-IBKR mirror of the Quant 7-Gates simulation. Covers
 * the entry gates (ibkr off / allowlist / toggle / dedupe / one-position /
 * exit-in-flight) and the flatten contract on close.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IbkrQuant7GatesExecutionBridgeTest {

    @Mock IbkrOrderService ibkrOrderService;
    @Mock TradeExecutionRepositoryPort repo;
    @Mock IbkrProperties ibkrProperties;

    private QuantSimExecutionProperties props;
    private QuantSimExecutionState toggle;
    private IbkrQuant7GatesExecutionBridge bridge;

    @BeforeEach
    void setUp() {
        props = new QuantSimExecutionProperties();
        props.setEnabled(true);
        props.setBrokerAccountId("DU-TEST");
        props.setInstruments(List.of("MNQ", "MCL"));
        props.setDefaultQuantity(1);
        toggle = new QuantSimExecutionState();

        when(ibkrProperties.isEnabled()).thenReturn(true);
        // Default: no existing rows.
        when(repo.findByExecutionKey(any())).thenReturn(Optional.empty());
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(repo.createIfAbsent(any())).thenAnswer(inv -> {
            TradeExecutionRecord r = inv.getArgument(0);
            r.setId(42L);
            return r;
        });
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        bridge = new IbkrQuant7GatesExecutionBridge(ibkrOrderService, repo, ibkrProperties, props, toggle);
    }

    // ── constructor fail-fast ────────────────────────────────────────────────

    @Test
    void constructorFailsWhenBrokerAccountBlank() {
        QuantSimExecutionProperties bad = new QuantSimExecutionProperties();
        bad.setEnabled(true);
        bad.setBrokerAccountId("  ");
        assertThatThrownBy(() ->
            new IbkrQuant7GatesExecutionBridge(ibkrOrderService, repo, ibkrProperties, bad, toggle))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("broker-account-id");
    }

    // ── submitOpen gates ─────────────────────────────────────────────────────

    @Test
    void openRoutesWhenAllowlistedAndArmed() {
        toggle.setEnabled(Instrument.MNQ, true);
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(777L, "Submitted", "ref", Instant.now()));

        RoutingResult r = bridge.submitOpen(open(Instrument.MNQ, Quant7GatesSimulation.Direction.LONG, 30000.0));

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        assertThat(r.executionId()).isEqualTo(42L);
        assertThat(r.brokerOrderId()).isEqualTo(777L);

        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        assertThat(req.getValue().action()).isEqualTo("LONG");
        assertThat(req.getValue().quantity()).isEqualTo(1);

        ArgumentCaptor<TradeExecutionRecord> saved = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
        assertThat(saved.getValue().getTriggerSource()).isEqualTo(ExecutionTriggerSource.QUANT_SIM_AUTO);
        assertThat(saved.getValue().getIbkrOrderId()).isEqualTo(777);
    }

    @Test
    void openSkippedWhenIbkrDisabled() {
        when(ibkrProperties.isEnabled()).thenReturn(false);
        toggle.setEnabled(Instrument.MNQ, true);
        RoutingResult r = bridge.submitOpen(open(Instrument.MNQ, Quant7GatesSimulation.Direction.LONG, 30000.0));
        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_IBKR_DISABLED);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void openSkippedWhenNotAllowlisted() {
        toggle.setEnabled(Instrument.MGC, true); // armed, but MGC is not on the allowlist
        RoutingResult r = bridge.submitOpen(open(Instrument.MGC, Quant7GatesSimulation.Direction.SHORT, 2100.0));
        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_AUTO_OFF);
        assertThat(r.message()).contains("allowlist");
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void openSkippedWhenToggleOff() {
        // allowlisted but toggle never armed
        RoutingResult r = bridge.submitOpen(open(Instrument.MNQ, Quant7GatesSimulation.Direction.LONG, 30000.0));
        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_AUTO_OFF);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void openSkippedWhenDuplicateKey() {
        toggle.setEnabled(Instrument.MNQ, true);
        when(repo.findByExecutionKey(any())).thenReturn(Optional.of(new TradeExecutionRecord()));
        RoutingResult r = bridge.submitOpen(open(Instrument.MNQ, Quant7GatesSimulation.Direction.LONG, 30000.0));
        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void openSkippedWhenPositionAlreadyOpen() {
        toggle.setEnabled(Instrument.MNQ, true);
        TradeExecutionRecord activeRow = new TradeExecutionRecord();
        activeRow.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any()))
            .thenReturn(Optional.of(activeRow));
        RoutingResult r = bridge.submitOpen(open(Instrument.MNQ, Quant7GatesSimulation.Direction.LONG, 30000.0));
        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void openSkippedWhenExitInFlight() {
        toggle.setEnabled(Instrument.MNQ, true);
        TradeExecutionRecord exiting = new TradeExecutionRecord();
        exiting.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any()))
            .thenReturn(Optional.of(exiting));
        RoutingResult r = bridge.submitOpen(open(Instrument.MNQ, Quant7GatesSimulation.Direction.LONG, 30000.0));
        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    // ── submitClose flatten ──────────────────────────────────────────────────

    @Test
    void closeFlattensExistingRow() {
        TradeExecutionRecord openRow = new TradeExecutionRecord();
        openRow.setId(42L);
        openRow.setStatus(ExecutionStatus.ACTIVE);
        openRow.setQuantity(1);
        openRow.setInstrument("MNQ");
        openRow.setExecutionKey("quant-sim:MNQ:LONG:1:OPEN");
        openRow.setBrokerAccountId("DU-TEST");
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any()))
            .thenReturn(Optional.of(openRow));
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(888L, "Submitted", "ref", Instant.now()));

        RoutingResult r = bridge.submitClose(
            close(Instrument.MNQ, Quant7GatesSimulation.Direction.LONG, 30050.0,
                Quant7GatesSimulationStatus.CLOSED_TP1));

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        ArgumentCaptor<BrokerEntryOrderRequest> req = ArgumentCaptor.forClass(BrokerEntryOrderRequest.class);
        verify(ibkrOrderService).submitEntryOrder(req.capture());
        // Flatten a LONG → send SHORT.
        assertThat(req.getValue().action()).isEqualTo("SHORT");
        assertThat(openRow.getStatus()).isEqualTo(ExecutionStatus.EXIT_SUBMITTED);
        assertThat(openRow.getIbkrOrderId()).isEqualTo(888);
    }

    @Test
    void closeSkippedWhenNoOpenRow() {
        RoutingResult r = bridge.submitClose(
            close(Instrument.MNQ, Quant7GatesSimulation.Direction.LONG, 30050.0,
                Quant7GatesSimulationStatus.CLOSED_TP1));
        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_NO_OPEN_ROW);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void closeSkippedWhenAlreadyExitSubmitted() {
        TradeExecutionRecord exiting = new TradeExecutionRecord();
        exiting.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(any(), any(), any()))
            .thenReturn(Optional.of(exiting));
        RoutingResult r = bridge.submitClose(
            close(Instrument.MNQ, Quant7GatesSimulation.Direction.LONG, 30050.0,
                Quant7GatesSimulationStatus.CLOSED_SL));
        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Quant7GatesSimulation open(Instrument instr, Quant7GatesSimulation.Direction dir, double entry) {
        double sl = dir == Quant7GatesSimulation.Direction.LONG ? entry - 25 : entry + 25;
        double tp1 = dir == Quant7GatesSimulation.Direction.LONG ? entry + 40 : entry - 40;
        double tp2 = dir == Quant7GatesSimulation.Direction.LONG ? entry + 80 : entry - 80;
        return new Quant7GatesSimulation(
            1L, instr, dir, entry, sl, tp1, tp2,
            Instant.parse("2026-06-03T13:00:00Z"), dir + " test", "LIVE_PUSH",
            Quant7GatesSimulationStatus.OPEN, null, "", null, null, 0.0, 0.0);
    }

    private static Quant7GatesSimulation close(Instrument instr, Quant7GatesSimulation.Direction dir,
                                               double exit, Quant7GatesSimulationStatus status) {
        return open(instr, dir, 30000.0)
            .close(exit, "LIVE_PUSH", Instant.parse("2026-06-03T13:02:00Z"), status.name(), status);
    }
}

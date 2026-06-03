package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingResult;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiRiskPlan;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignal;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P2 synchronous-fill regression for the WTX-RSI bridge: when the broker reports {@code Filled} at submit
 * return, the row goes terminal immediately (entry → ACTIVE, close → CLOSED) instead of waiting on an
 * orderStatus callback that root cause R2 can drop — which would leave a phantom row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IbkrWtxRsiExecutionBridgeSyncFillTest {

    @Mock IbkrOrderService ibkrOrderService;
    @Mock TradeExecutionRepositoryPort repo;
    @Mock IbkrProperties ibkrProperties;

    private IbkrWtxRsiExecutionBridge bridge;

    @BeforeEach
    void setUp() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(repo.findByExecutionKey(any())).thenReturn(Optional.empty());
        when(repo.createIfAbsent(any())).thenAnswer(inv -> {
            TradeExecutionRecord r = inv.getArgument(0);
            r.setId(42L);
            return r;
        });
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        bridge = new IbkrWtxRsiExecutionBridge(ibkrOrderService, repo, ibkrProperties);
    }

    @Test
    void openMarkedActiveWhenBrokerReportsFilled() {
        TradeExecutionRecord[] saved = new TradeExecutionRecord[1];
        when(repo.save(any())).thenAnswer(inv -> saved[0] = inv.getArgument(0));
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(777L, "Filled", "ref", Instant.now()));

        WtxRoutingResult r = bridge.submitOpen(longSignal(), longPlan(),
            WtxRsiStrategyState.initial("MNQ", "5m"), new BigDecimal("30000.00"));

        assertThat(r.outcome()).isEqualTo(WtxRoutingOutcome.ROUTED);
        assertThat(saved[0].getStatus()).isEqualTo(ExecutionStatus.ACTIVE);
        assertThat(saved[0].getEntryFilledAt()).isNotNull();
    }

    @Test
    void openStaysSubmittedWhenResting() {
        TradeExecutionRecord[] saved = new TradeExecutionRecord[1];
        when(repo.save(any())).thenAnswer(inv -> saved[0] = inv.getArgument(0));
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(777L, "Submitted", "ref", Instant.now()));

        bridge.submitOpen(longSignal(), longPlan(),
            WtxRsiStrategyState.initial("MNQ", "5m"), new BigDecimal("30000.00"));

        assertThat(saved[0].getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
        assertThat(saved[0].getEntryFilledAt()).isNull();
    }

    @Test
    void closeMarkedClosedWhenBrokerReportsFilled() {
        // The R2 killer: a marketable close fills at submit return → CLOSED now, not a phantom EXIT_SUBMITTED.
        TradeExecutionRecord openRow = activeLongRow();
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(
                "MNQ", "5m", ExecutionTriggerSource.WTXRSI_AUTO)).thenReturn(Optional.of(openRow));
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(888L, "Filled", "ref", Instant.now()));

        WtxRoutingResult r = bridge.submitClose(longStateWithPosition(),
            WtxRsiSignalRecord.Action.CLOSE_LONG, new BigDecimal("30050.00"));

        assertThat(r.outcome()).isEqualTo(WtxRoutingOutcome.ROUTED);
        assertThat(openRow.getStatus()).isEqualTo(ExecutionStatus.CLOSED);
        assertThat(openRow.getClosedAt()).isNotNull();
        assertThat(openRow.getIbkrOrderId()).isEqualTo(888);
    }

    @Test
    void closeStaysExitSubmittedWhenResting() {
        TradeExecutionRecord openRow = activeLongRow();
        when(repo.findActiveByInstrumentAndTimeframeAndTriggerSource(
                "MNQ", "5m", ExecutionTriggerSource.WTXRSI_AUTO)).thenReturn(Optional.of(openRow));
        when(ibkrOrderService.submitEntryOrder(any()))
            .thenReturn(new BrokerEntryOrderSubmission(888L, "Submitted", "ref", Instant.now()));

        bridge.submitClose(longStateWithPosition(),
            WtxRsiSignalRecord.Action.CLOSE_LONG, new BigDecimal("30050.00"));

        assertThat(openRow.getStatus()).isEqualTo(ExecutionStatus.EXIT_SUBMITTED);
        assertThat(openRow.getClosedAt()).isNull();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static WtxRsiSignal longSignal() {
        return new WtxRsiSignal(0, Instant.parse("2026-06-03T13:00:00Z"), WtxRsiSignal.Side.LONG, true,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("30000.00"));
    }

    private static WtxRsiRiskPlan longPlan() {
        return new WtxRsiRiskPlan(WtxRsiSignal.Side.LONG, 1, new BigDecimal("30000.00"),
            new BigDecimal("29950.00"), new BigDecimal("30100.00"), new BigDecimal("50.00"), new BigDecimal("29950.00"));
    }

    private static WtxRsiStrategyState longStateWithPosition() {
        return WtxRsiStrategyState.initial("MNQ", "5m").withPosition(
            WtxRsiPosition.LONG, new BigDecimal("30000.00"), BigDecimal.ONE,
            new BigDecimal("29950.00"), new BigDecimal("30100.00"));
    }

    private static TradeExecutionRecord activeLongRow() {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(42L);
        r.setStatus(ExecutionStatus.ACTIVE);
        r.setAction("LONG");
        r.setQuantity(1);
        r.setInstrument("MNQ");
        r.setTimeframe("5m");
        r.setExecutionKey("wtxrsi:MNQ:5m:1:OPEN_LONG");
        r.setBrokerAccountId("wtxrsi-default");
        r.setNormalizedEntryPrice(new BigDecimal("30000.00"));
        return r;
    }
}

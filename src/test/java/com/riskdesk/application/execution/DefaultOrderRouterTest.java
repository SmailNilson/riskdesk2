package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort.CreateOutcome;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOrderRouterTest {

    @Mock private IbkrOrderService ibkrOrderService;
    @Mock private TradeExecutionRepositoryPort repo;

    private IbkrProperties props;
    private DefaultOrderRouter router;

    @BeforeEach
    void setUp() {
        props = new IbkrProperties();
        props.setEnabled(true);
        router = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> true);
        // createIfAbsentTracked: we created the row (created=true) — it assigns the PK; save echoes it.
        lenient().when(repo.createIfAbsentTracked(any())).thenAnswer(inv -> {
            TradeExecutionRecord r = inv.getArgument(0);
            if (r != null) r.setId(1L); // null-safe: re-stubbing in a test re-invokes this with a null arg
            return new CreateOutcome(r, true);
        });
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private TradeIntent openLong() {
        return TradeIntent.open("wtx:MNQ:5m:1:OPEN_LONG", ExecutionTriggerSource.WTX_AUTO,
            Instrument.MNQ, "5m", Side.LONG, 2, new BigDecimal("18000.30"), "DU1");
    }

    private BrokerEntryOrderSubmission submission(Long brokerOrderId, String status) {
        return new BrokerEntryOrderSubmission(brokerOrderId, status, "wtx:MNQ:5m:1:OPEN_LONG", Instant.now());
    }

    @Test
    void routesOpen_persistsBothIds_roundsTick() {
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(12345L, "Submitted"));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ROUTED);
        assertThat(r.executionId()).isEqualTo(1L);
        assertThat(r.brokerOrderId()).isEqualTo(12345L);

        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        TradeExecutionRecord saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
        assertThat(saved.getEntryOrderId()).isEqualTo(12345L);
        assertThat(saved.getIbkrOrderId()).isEqualTo(12345);                 // Integer cast for fill tracker
        assertThat(saved.getAction()).isEqualTo("LONG");
        assertThat(saved.getNormalizedEntryPrice()).isEqualByComparingTo("18000.25"); // rounded to 0.25 tick
    }

    @Test
    void pendingSubmitMapsToAckPending() {
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(777L, "PendingSubmit"));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ACK_PENDING);
        assertThat(r.brokerOrderId()).isEqualTo(777L);
    }

    @Test
    void skipsDuplicateWhenRowAlreadyExisted_noDoubleSubmit() {
        // Race resolved by the DB unique constraint: createIfAbsentTracked reports created=false, so this
        // (loser) caller must NOT submit a second order.
        TradeExecutionRecord existing = new TradeExecutionRecord();
        existing.setId(1L);
        existing.setEntryOrderId(555L);
        when(repo.createIfAbsentTracked(any())).thenReturn(new CreateOutcome(existing, false));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
        assertThat(r.executionId()).isEqualTo(1L);
        assertThat(r.brokerOrderId()).isEqualTo(555L); // the winner's broker order id
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void mapsInsufficientMargin_terminalFailed() {
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN, 201, "margin", "insufficient", null));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_INSUFFICIENT_MARGIN);
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.FAILED); // terminal: broker rejected
    }

    @Test
    void readOnlyRejectMapsToFailedReadOnly() {
        // The kill-switch / TWS Read-Only surface as BROKER_REJECT carrying a read-only message.
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.BROKER_REJECT, null, "native-read-only kill-switch is ON",
            "Order NOT sent: riskdesk.ibkr.native-read-only is ON (software kill-switch).", null));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_READ_ONLY);
    }

    @Test
    void timeoutWithBrokerIdIsAckPending() {
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.TIMEOUT, null, "timeout", "no ack", 999L));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ACK_PENDING);
        assertThat(r.brokerOrderId()).isEqualTo(999L);
    }

    @Test
    void timeoutWithoutBrokerIdIsFailedTimeout_nonTerminal() {
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.TIMEOUT, null, "timeout", "no ack", null));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_TIMEOUT);
        assertThat(r.brokerOrderId()).isNull();
        // Broker state UNKNOWN on a no-id timeout — row MUST stay non-terminal for the reconciler.
        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
    }

    @Test
    void skipsWhenIbkrDisabled() {
        props.setEnabled(false);

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_IBKR_DISABLED);
        verifyNoInteractions(ibkrOrderService);
    }

    @Test
    void skipsWhenNotReady() {
        DefaultOrderRouter gated = new DefaultOrderRouter(ibkrOrderService, repo, props, () -> false);

        RoutingResult r = gated.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_RECONCILING);
        verifyNoInteractions(ibkrOrderService);
    }
}

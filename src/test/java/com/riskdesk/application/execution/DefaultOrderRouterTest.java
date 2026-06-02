package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        // createIfAbsent simulates persistence assigning the PK; save echoes the row.
        lenient().when(repo.createIfAbsent(any())).thenAnswer(inv -> {
            TradeExecutionRecord r = inv.getArgument(0);
            r.setId(1L);
            return r;
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
        when(repo.findByExecutionKey(anyString())).thenReturn(Optional.empty());
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
        when(repo.findByExecutionKey(anyString())).thenReturn(Optional.empty());
        when(ibkrOrderService.submitEntryOrder(any())).thenReturn(submission(777L, "PendingSubmit"));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ACK_PENDING);
        assertThat(r.brokerOrderId()).isEqualTo(777L);
    }

    @Test
    void skipsDuplicateWhenExecutionKeyExists() {
        when(repo.findByExecutionKey(anyString())).thenReturn(Optional.of(new TradeExecutionRecord()));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_DUPLICATE);
        verify(ibkrOrderService, never()).submitEntryOrder(any());
    }

    @Test
    void mapsInsufficientMargin() {
        when(repo.findByExecutionKey(anyString())).thenReturn(Optional.empty());
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.INSUFFICIENT_MARGIN, 201, "margin", "insufficient", null));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_INSUFFICIENT_MARGIN);
        assertThat(r.executionId()).isEqualTo(1L);

        ArgumentCaptor<TradeExecutionRecord> cap = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(ExecutionStatus.FAILED); // terminal: broker rejected, no position
    }

    @Test
    void timeoutWithBrokerIdIsAckPending() {
        when(repo.findByExecutionKey(anyString())).thenReturn(Optional.empty());
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.TIMEOUT, null, "timeout", "no ack", 999L));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.ACK_PENDING);
        assertThat(r.brokerOrderId()).isEqualTo(999L);
    }

    @Test
    void timeoutWithoutBrokerIdIsFailedTimeout() {
        when(repo.findByExecutionKey(anyString())).thenReturn(Optional.empty());
        when(ibkrOrderService.submitEntryOrder(any())).thenThrow(new IbkrOrderRejectionException(
            IbkrOrderRejectionException.Kind.TIMEOUT, null, "timeout", "no ack", null));

        RoutingResult r = router.route(openLong());

        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_TIMEOUT);
        assertThat(r.brokerOrderId()).isNull();

        // Broker state is UNKNOWN on a no-id timeout — the row MUST stay non-terminal so the
        // stale-entry reconciler / late callbacks can resolve it, and no retry double-submits.
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

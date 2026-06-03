package com.riskdesk.application.execution;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D2 — the deferred reverse-open scheduler: submits a deferred open once its close ROW is CLOSED, cancels it
 * when the close did not complete (revived ACTIVE / terminal-without-fill), and waits while the close rests.
 */
class ReverseDeferredOpenSchedulerTest {

    private TradeExecutionRepositoryPort repo;
    private DefaultOrderRouter router;
    private IbkrProperties props;
    private ReverseDeferredOpenScheduler scheduler;

    @BeforeEach
    void setUp() {
        repo = mock(TradeExecutionRepositoryPort.class);
        router = mock(DefaultOrderRouter.class);
        props = new IbkrProperties();
        props.setEnabled(true);
        scheduler = new ReverseDeferredOpenScheduler(repo, router, props);
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** A deferred open row linked to close row {@code closeRowId}. */
    private TradeExecutionRecord deferredOpen(long closeRowId) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(1L);
        r.setExecutionKey("wtx:MNQ:5m:2:REVERSE_SHORT");
        r.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        r.setAction("SHORT");
        r.setQuantity(2);
        r.setDeferredReverseCloseRowId(closeRowId);
        return r;
    }

    private TradeExecutionRecord closeRow(ExecutionStatus status) {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(99L);
        r.setStatus(status);
        return r;
    }

    @Test
    void closeRowClosed_submitsDeferredOpen() {
        TradeExecutionRecord open = deferredOpen(99L);
        when(repo.findPendingDeferredReverseOpens()).thenReturn(List.of(open));
        when(repo.findById(99L)).thenReturn(Optional.of(closeRow(ExecutionStatus.CLOSED)));

        scheduler.submitReadyDeferredOpens();

        verify(router).submitDeferredReverseOpen(open); // close filled (broker flat) → open submitted
    }

    @Test
    void closeRowRevivedActive_cancelsDeferredOpen() {
        // Close cancelled without a fill → fill tracker revived the close row to ACTIVE → position still live.
        TradeExecutionRecord open = deferredOpen(99L);
        when(repo.findPendingDeferredReverseOpens()).thenReturn(List.of(open));
        when(repo.findById(99L)).thenReturn(Optional.of(closeRow(ExecutionStatus.ACTIVE)));

        scheduler.submitReadyDeferredOpens();

        verify(router, never()).submitDeferredReverseOpen(any()); // must NOT open onto a still-open position
        assertThat(open.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(open.getDeferredReverseCloseRowId()).isNull();
        verify(repo).save(open);
    }

    @Test
    void closeRowStillResting_waits() {
        TradeExecutionRecord open = deferredOpen(99L);
        when(repo.findPendingDeferredReverseOpens()).thenReturn(List.of(open));
        when(repo.findById(99L)).thenReturn(Optional.of(closeRow(ExecutionStatus.EXIT_SUBMITTED)));

        scheduler.submitReadyDeferredOpens();

        verify(router, never()).submitDeferredReverseOpen(any());
        verify(repo, never()).save(any()); // not submitted, not cancelled — just waiting
    }

    @Test
    void closeStuckExitSubmittedWithTerminalOrderStatus_cancelsDeferredOpen() {
        // Partial fill then cancel: the fill tracker leaves the close row EXIT_SUBMITTED (filledQty>0) while
        // its raw orderStatus is Cancelled. The scheduler must NOT wait forever → cancel the deferred open.
        TradeExecutionRecord open = deferredOpen(99L);
        TradeExecutionRecord close = closeRow(ExecutionStatus.EXIT_SUBMITTED);
        close.setOrderStatus("Cancelled");
        when(repo.findPendingDeferredReverseOpens()).thenReturn(List.of(open));
        when(repo.findById(99L)).thenReturn(Optional.of(close));

        scheduler.submitReadyDeferredOpens();

        verify(router, never()).submitDeferredReverseOpen(any());
        assertThat(open.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(open.getDeferredReverseCloseRowId()).isNull();
        verify(repo).save(open);
    }

    @Test
    void closeRowMissing_waits() {
        TradeExecutionRecord open = deferredOpen(99L);
        when(repo.findPendingDeferredReverseOpens()).thenReturn(List.of(open));
        when(repo.findById(99L)).thenReturn(Optional.empty());

        scheduler.submitReadyDeferredOpens();

        verify(router, never()).submitDeferredReverseOpen(any());
        verify(repo, never()).save(any());
    }

    @Test
    void ibkrDisabled_isNoOp() {
        props.setEnabled(false);

        scheduler.submitReadyDeferredOpens();

        verify(repo, never()).findPendingDeferredReverseOpens();
        verify(router, never()).submitDeferredReverseOpen(any());
    }

    @Test
    void oneRowFailing_doesNotBlockTheRest() {
        TradeExecutionRecord bad = deferredOpen(98L);
        TradeExecutionRecord good = deferredOpen(99L);
        good.setId(2L);
        when(repo.findPendingDeferredReverseOpens()).thenReturn(List.of(bad, good));
        when(repo.findById(98L)).thenThrow(new RuntimeException("lookup blip"));
        when(repo.findById(99L)).thenReturn(Optional.of(closeRow(ExecutionStatus.CLOSED)));

        scheduler.submitReadyDeferredOpens();

        verify(router).submitDeferredReverseOpen(good); // the bad row's exception did not abort the loop
    }
}

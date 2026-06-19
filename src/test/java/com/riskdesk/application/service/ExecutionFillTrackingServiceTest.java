package com.riskdesk.application.service;

import com.riskdesk.application.execution.ExecutionTopicPublisher;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slice 3a — unit tests for {@link ExecutionFillTrackingService}.
 *
 * <p>Verifies that IBKR {@code execDetails} and {@code orderStatus} callbacks
 * mutate the {@link TradeExecutionRecord} correctly, are deduplicated, and
 * produce exactly one WebSocket publish per state-changing update.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionFillTrackingServiceTest {

    private static final int ORDER_ID = 7421;
    private static final String EXEC_ID_1 = "000111.63a4fe8a.01.01";
    private static final String EXEC_ID_2 = "000111.63a4fe8a.01.02";
    private static final String ORDER_REF = "exec:mentor-review:42";

    @Mock
    private TradeExecutionRepositoryPort repository;

    @Mock
    private ExecutionTopicPublisher executionTopicPublisher;

    private ExecutionFillTrackingService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionFillTrackingService(repository, executionTopicPublisher);
    }

    @Test
    void locatesByPermIdFirstAndCapturesIt() {
        // permId is durable + unique; orderId is reused after reconnect (collisions). The Filled close
        // must reconcile the RIGHT row via permId, never findByIbkrOrderId (which would be ambiguous).
        TradeExecutionRecord row = new TradeExecutionRecord();
        row.setId(99L);
        row.setStatus(com.riskdesk.domain.model.ExecutionStatus.EXIT_SUBMITTED);
        row.setExecutionKey(ORDER_REF);
        when(repository.findByPermId(555_000_111L)).thenReturn(Optional.of(row));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onOrderStatus(ORDER_ID, 555_000_111L, "Filled",
            new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("72.50"), Instant.parse("2026-06-03T15:30:00Z"));

        verify(repository).findByPermId(555_000_111L);
        verify(repository, never()).findByIbkrOrderId(any());
        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        assertEquals(com.riskdesk.domain.model.ExecutionStatus.CLOSED, captor.getValue().getStatus());
        assertEquals(Long.valueOf(555_000_111L), captor.getValue().getPermId());
    }

    @Test
    void execDetailsUpdatesFillFieldsAndPublishesOnce() {
        TradeExecutionRecord stored = baseExecution();
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onExecDetails(
            ORDER_ID,
            0L,
            EXEC_ID_1,
            ORDER_REF,
            new BigDecimal("1"),
            new BigDecimal("72.50"),
            new BigDecimal("72.50"),
            "BOT",
            Instant.parse("2026-04-23T15:30:00Z")
        );

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository, times(1)).save(captor.capture());
        TradeExecutionRecord saved = captor.getValue();
        assertEquals(Integer.valueOf(ORDER_ID), saved.getIbkrOrderId());
        assertEquals(new BigDecimal("1"), saved.getFilledQuantity());
        assertEquals(new BigDecimal("72.50"), saved.getAvgFillPrice());
        assertEquals(EXEC_ID_1, saved.getLastExecId());
        assertEquals(Instant.parse("2026-04-23T15:30:00Z"), saved.getLastFillTime());

        verify(executionTopicPublisher, times(1)).publish(any(TradeExecutionRecord.class));
    }

    @Test
    void execDetailsWithSameExecIdIsIdempotent() {
        TradeExecutionRecord stored = baseExecution();
        stored.setIbkrOrderId(ORDER_ID);
        stored.setLastExecId(EXEC_ID_1);
        stored.setFilledQuantity(new BigDecimal("1"));
        stored.setAvgFillPrice(new BigDecimal("72.50"));
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.of(stored));

        service.onExecDetails(
            ORDER_ID,
            0L,
            EXEC_ID_1,
            ORDER_REF,
            new BigDecimal("1"),
            new BigDecimal("72.50"),
            new BigDecimal("72.50"),
            "BOT",
            Instant.parse("2026-04-23T15:30:00Z")
        );
        // Replay — same execId.
        service.onExecDetails(
            ORDER_ID,
            0L,
            EXEC_ID_1,
            ORDER_REF,
            new BigDecimal("1"),
            new BigDecimal("72.50"),
            new BigDecimal("72.50"),
            "BOT",
            Instant.parse("2026-04-23T15:30:00Z")
        );

        verify(repository, never()).save(any());
        verify(executionTopicPublisher, never()).publish(any(TradeExecutionRecord.class));
    }

    @Test
    void execDetailsFallsBackToOrderRefWhenOrderIdMissing() {
        TradeExecutionRecord stored = baseExecution();
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(repository.findByExecutionKey(ORDER_REF)).thenReturn(Optional.of(stored));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onExecDetails(
            ORDER_ID,
            0L,
            EXEC_ID_1,
            ORDER_REF,
            new BigDecimal("1"),
            new BigDecimal("72.50"),
            new BigDecimal("72.50"),
            "BOT",
            Instant.parse("2026-04-23T15:30:00Z")
        );

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        assertEquals(Integer.valueOf(ORDER_ID), captor.getValue().getIbkrOrderId());
    }

    @Test
    void execDetailsMapsSuffixedExitRefBackToBaseExecutionKey() {
        // Exit legs submit under "<executionKey>:exit" (distinct from the entry ref). The row's key is
        // unsuffixed, so a close callback arriving before the close orderId is persisted must still locate
        // the row by stripping the ":exit" suffix — otherwise the fill is dropped and the row stays
        // EXIT_SUBMITTED after the broker is flat.
        TradeExecutionRecord stored = baseExecution();
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(repository.findByExecutionKey(ORDER_REF + ":exit")).thenReturn(Optional.empty());
        when(repository.findByExecutionKey(ORDER_REF)).thenReturn(Optional.of(stored));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onExecDetails(
            ORDER_ID,
            0L,
            EXEC_ID_1,
            ORDER_REF + ":exit",
            new BigDecimal("1"),
            new BigDecimal("72.50"),
            new BigDecimal("72.50"),
            "SLD",
            Instant.parse("2026-04-23T15:30:00Z")
        );

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        assertEquals(Integer.valueOf(ORDER_ID), captor.getValue().getIbkrOrderId());
    }

    @Test
    void execDetailsMapsDiscriminatedExitRefBackToBaseExecutionKey() {
        // Retry-safe exit refs carry a per-attempt discriminator: "<executionKey>:exit:<bar ts>". The fill
        // tracker must still map it back to the row by the base key (everything before ":exit").
        TradeExecutionRecord stored = baseExecution();
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(repository.findByExecutionKey(ORDER_REF + ":exit:1717350000000")).thenReturn(Optional.empty());
        when(repository.findByExecutionKey(ORDER_REF)).thenReturn(Optional.of(stored));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onExecDetails(
            ORDER_ID,
            0L,
            EXEC_ID_1,
            ORDER_REF + ":exit:1717350000000",
            new BigDecimal("1"),
            new BigDecimal("72.50"),
            new BigDecimal("72.50"),
            "SLD",
            Instant.parse("2026-04-23T15:30:00Z")
        );

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        assertEquals(Integer.valueOf(ORDER_ID), captor.getValue().getIbkrOrderId());
    }

    @Test
    void orderStatusSubmittedTransitionsPendingEntryToEntrySubmitted() {
        TradeExecutionRecord stored = baseExecution();
        stored.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        stored.setStatusReason("WTX OPEN_LONG sent to IBKR; acknowledgement pending");
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onOrderStatus(
            ORDER_ID,
            0L,
            "Submitted",
            BigDecimal.ZERO,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            Instant.parse("2026-04-23T15:30:00Z")
        );

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        TradeExecutionRecord saved = captor.getValue();
        assertEquals(ExecutionStatus.ENTRY_SUBMITTED, saved.getStatus());
        assertEquals("IBKR entry order acknowledged: Submitted", saved.getStatusReason());
        verify(executionTopicPublisher, times(1)).publish(any(TradeExecutionRecord.class));
    }

    @Test
    void orderStatusFilledTransitionsDomainStateToActive() {
        TradeExecutionRecord stored = baseExecution();
        stored.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onOrderStatus(
            ORDER_ID,
            0L,
            "Filled",
            new BigDecimal("1"),
            BigDecimal.ZERO,
            new BigDecimal("72.50"),
            Instant.parse("2026-04-23T15:30:00Z")
        );

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        TradeExecutionRecord saved = captor.getValue();
        assertEquals(ExecutionStatus.ACTIVE, saved.getStatus());
        assertEquals("Filled", saved.getOrderStatus());
        assertNotNull(saved.getEntryFilledAt());
        verify(executionTopicPublisher, times(1)).publish(any(TradeExecutionRecord.class));
    }

    @Test
    void orderStatusFilledOnExitSubmittedRowTransitionsToClosed() {
        // A submitted exit/close order (e.g. a WTX auto-routed close) filling at the broker
        // must move the row to the terminal CLOSED state — otherwise an EXIT_SUBMITTED row
        // would stay non-terminal forever and the position would look open.
        TradeExecutionRecord stored = baseExecution();
        stored.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onOrderStatus(
            ORDER_ID,
            0L,
            "Filled",
            new BigDecimal("1"),
            BigDecimal.ZERO,
            new BigDecimal("72.50"),
            Instant.parse("2026-04-23T15:30:00Z")
        );

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        TradeExecutionRecord saved = captor.getValue();
        assertEquals(ExecutionStatus.CLOSED, saved.getStatus());
        assertNotNull(saved.getClosedAt());
        verify(executionTopicPublisher, times(1)).publish(any(TradeExecutionRecord.class));
    }

    @Test
    void orderStatusCancelledWithZeroFillsTransitionsToCancelled() {
        TradeExecutionRecord stored = baseExecution();
        stored.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onOrderStatus(
            ORDER_ID,
            0L,
            "Cancelled",
            BigDecimal.ZERO,
            new BigDecimal("1"),
            BigDecimal.ZERO,
            Instant.parse("2026-04-23T15:30:00Z")
        );

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        assertEquals(ExecutionStatus.CANCELLED, captor.getValue().getStatus());
    }

    @Test
    void orderStatusCancelledOnExitSubmittedWithoutFill_revivesToActive_andDetachesOrderId() {
        // A CLOSE order that cancelled WITHOUT a fill did not flatten — the position is still live, so the
        // row must be revived to ACTIVE (not CANCELLED) and the dead close order id detached. This is what
        // lets the WTX close settler distinguish a cancelled close (rollback) from a filled one (finalize).
        TradeExecutionRecord stored = baseExecution();
        stored.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onOrderStatus(
            ORDER_ID,
            0L,
            "Cancelled",
            BigDecimal.ZERO,
            new BigDecimal("1"),
            BigDecimal.ZERO,
            Instant.parse("2026-04-23T15:30:00Z")
        );

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        assertEquals(ExecutionStatus.ACTIVE, captor.getValue().getStatus());
        assertNull(captor.getValue().getIbkrOrderId(), "dead close order id must be detached on revive");
    }

    @Test
    void replayedCancelledAfterReviveByPermId_keepsRowActive_doesNotReCancel() {
        // bug_007 regression: permId-first locate() means the revive branch must detach BOTH the dead
        // close orderId AND its permId. Otherwise a replayed Cancelled callback (IBKR re-delivers them)
        // re-locates the revived ACTIVE row by the close's still-attached permId and wrongly CANCELS a
        // position that is still live at the broker — orphaning it as a phantom. With permId detached the
        // replay matches nothing (its permId and orderId are both gone from the row) and is ignored.
        final long permId = 555_000_222L;
        TradeExecutionRecord stored = baseExecution();
        stored.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        stored.setIbkrOrderId(ORDER_ID);
        stored.setPermId(permId);
        // Faithful DB behaviour: the row is found by a key only while it still carries that key.
        when(repository.findByPermId(permId)).thenAnswer(inv ->
            Long.valueOf(permId).equals(stored.getPermId()) ? Optional.of(stored) : Optional.empty());
        when(repository.findByIbkrOrderId(ORDER_ID)).thenAnswer(inv ->
            Integer.valueOf(ORDER_ID).equals(stored.getIbkrOrderId()) ? Optional.of(stored) : Optional.empty());
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        // First Cancelled (no fill) on the EXIT order → revive to ACTIVE, detach orderId + permId.
        service.onOrderStatus(ORDER_ID, permId, "Cancelled",
            BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, Instant.parse("2026-06-03T15:30:00Z"));

        assertEquals(ExecutionStatus.ACTIVE, stored.getStatus());
        assertNull(stored.getIbkrOrderId(), "dead close order id must be detached on revive");
        assertNull(stored.getPermId(), "dead close permId must be detached so a replayed cancel can't re-target it");

        // Replay the SAME Cancelled callback — it must NOT re-locate and re-cancel the live position.
        service.onOrderStatus(ORDER_ID, permId, "Cancelled",
            BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, Instant.parse("2026-06-03T15:30:00Z"));

        assertEquals(ExecutionStatus.ACTIVE, stored.getStatus(), "replayed cancel must leave the revived row ACTIVE");
        // Exactly one save — the revive. The replay locates nothing and is ignored before any write.
        verify(repository, times(1)).save(any(TradeExecutionRecord.class));
    }

    @Test
    void orderStatusUnchangedDoesNotPublish() {
        TradeExecutionRecord stored = baseExecution();
        stored.setIbkrOrderId(ORDER_ID);
        stored.setStatus(ExecutionStatus.ACTIVE);
        stored.setOrderStatus("Filled");
        stored.setFilledQuantity(new BigDecimal("1"));
        stored.setAvgFillPrice(new BigDecimal("72.50"));
        stored.setLastFillTime(Instant.parse("2026-04-23T15:30:00Z"));
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.of(stored));

        service.onOrderStatus(
            ORDER_ID,
            0L,
            "Filled",
            new BigDecimal("1"),
            BigDecimal.ZERO,
            new BigDecimal("72.50"),
            Instant.parse("2026-04-23T15:30:00Z")
        );

        verify(repository, never()).save(any());
        verify(executionTopicPublisher, never()).publish(any(TradeExecutionRecord.class));
    }

    @Test
    void unknownOrderIdIsIgnored() {
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(repository.findByExecutionKey(ORDER_REF)).thenReturn(Optional.empty());

        service.onExecDetails(
            ORDER_ID,
            0L,
            EXEC_ID_1,
            ORDER_REF,
            new BigDecimal("1"),
            new BigDecimal("72.50"),
            new BigDecimal("72.50"),
            "BOT",
            Instant.parse("2026-04-23T15:30:00Z")
        );

        verify(repository, never()).save(any());
        verify(executionTopicPublisher, never()).publish(any(TradeExecutionRecord.class));
    }

    @Test
    void secondExecDetailsUpdatesCumulativeAndPublishesAgain() {
        TradeExecutionRecord stored = baseExecution();
        stored.setIbkrOrderId(ORDER_ID);
        when(repository.findByIbkrOrderId(ORDER_ID)).thenReturn(Optional.of(stored));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        // First partial fill
        service.onExecDetails(
            ORDER_ID,
            0L,
            EXEC_ID_1,
            ORDER_REF,
            new BigDecimal("1"),
            new BigDecimal("72.50"),
            new BigDecimal("72.50"),
            "BOT",
            Instant.parse("2026-04-23T15:30:00Z")
        );
        // Second fill — different execId, cumulative qty grows
        service.onExecDetails(
            ORDER_ID,
            0L,
            EXEC_ID_2,
            ORDER_REF,
            new BigDecimal("2"),
            new BigDecimal("72.55"),
            new BigDecimal("72.60"),
            "BOT",
            Instant.parse("2026-04-23T15:30:05Z")
        );

        verify(repository, times(2)).save(any(TradeExecutionRecord.class));
        verify(executionTopicPublisher, times(2)).publish(any(TradeExecutionRecord.class));
        assertEquals(new BigDecimal("2"), stored.getFilledQuantity());
        assertEquals(EXEC_ID_2, stored.getLastExecId());
    }

    @Test
    void partialReduceFill_decrementsQuantityAndStaysActive() {
        // A resting partial close (REDUCE) of 1 on a position of 3 fills → quantity 2, row stays ACTIVE.
        TradeExecutionRecord row = new TradeExecutionRecord();
        row.setId(50L);
        row.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        row.setExecutionKey(ORDER_REF);
        row.setQuantity(3);
        row.setClosingQuantity(1);
        when(repository.findByPermId(600_000_001L)).thenReturn(Optional.of(row));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onOrderStatus(ORDER_ID, 600_000_001L, "Filled",
            new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("18010.00"), Instant.parse("2026-06-03T15:30:00Z"));

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        TradeExecutionRecord saved = captor.getValue();
        assertEquals(ExecutionStatus.ACTIVE, saved.getStatus());
        assertEquals(Integer.valueOf(2), saved.getQuantity());
        assertNull(saved.getClosingQuantity());
        assertNull(saved.getClosedAt());
    }

    @Test
    void partialReduceCancelledWithoutFill_revivesActiveAndClearsClosingQuantity() {
        // A resting reduce that cancels without a fill leaves the position at its FULL size.
        TradeExecutionRecord row = new TradeExecutionRecord();
        row.setId(51L);
        row.setStatus(ExecutionStatus.EXIT_SUBMITTED);
        row.setExecutionKey(ORDER_REF);
        row.setQuantity(3);
        row.setClosingQuantity(1);
        when(repository.findByPermId(600_000_002L)).thenReturn(Optional.of(row));
        when(repository.save(any(TradeExecutionRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onOrderStatus(ORDER_ID, 600_000_002L, "Cancelled",
            BigDecimal.ZERO, new BigDecimal("1"), null, Instant.parse("2026-06-03T15:30:00Z"));

        ArgumentCaptor<TradeExecutionRecord> captor = ArgumentCaptor.forClass(TradeExecutionRecord.class);
        verify(repository).save(captor.capture());
        TradeExecutionRecord saved = captor.getValue();
        assertEquals(ExecutionStatus.ACTIVE, saved.getStatus());
        assertEquals(Integer.valueOf(3), saved.getQuantity());
        assertNull(saved.getClosingQuantity());
    }

    private static TradeExecutionRecord baseExecution() {
        TradeExecutionRecord execution = new TradeExecutionRecord();
        execution.setId(42L);
        execution.setExecutionKey(ORDER_REF);
        execution.setMentorSignalReviewId(42L);
        execution.setReviewAlertKey("alert:mcl:10m");
        execution.setReviewRevision(1);
        execution.setBrokerAccountId("DU123");
        execution.setInstrument("MCL");
        execution.setTimeframe("10m");
        execution.setAction("BUY");
        execution.setQuantity(1);
        execution.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        execution.setNormalizedEntryPrice(new BigDecimal("72.50"));
        execution.setVirtualStopLoss(new BigDecimal("72.20"));
        execution.setVirtualTakeProfit(new BigDecimal("73.20"));
        execution.setCreatedAt(Instant.parse("2026-04-23T15:00:00Z"));
        execution.setUpdatedAt(Instant.parse("2026-04-23T15:00:00Z"));
        // Sanity defaults — no fills yet.
        assertNull(execution.getFilledQuantity());
        assertNull(execution.getLastExecId());
        return execution;
    }
}

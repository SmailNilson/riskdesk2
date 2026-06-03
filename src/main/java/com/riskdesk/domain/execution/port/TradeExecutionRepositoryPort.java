package com.riskdesk.domain.execution.port;

import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeExecutionRepositoryPort {

    TradeExecutionRecord createIfAbsent(TradeExecutionRecord execution);

    /** Result of {@link #createIfAbsentTracked}: the row, and whether THIS call created it. */
    record CreateOutcome(TradeExecutionRecord record, boolean created) {}

    /**
     * Like {@link #createIfAbsent} but reports whether THIS call <b>created</b> the row (so the caller
     * may submit a broker order) or it <b>already existed</b> (the caller must NOT submit a second
     * order). The DB unique constraint on {@code executionKey} / {@code mentorSignalReviewId} is the
     * serialization point for concurrent callers — no pessimistic row lock is needed, so the caller
     * never has to hold a transaction across the broker network call.
     *
     * <p>The JPA adapter overrides this to report created-vs-existing precisely from the
     * unique-constraint outcome. The default (for simple in-memory test fakes) just delegates to
     * {@link #createIfAbsent} and reports {@code created=true}.</p>
     */
    default CreateOutcome createIfAbsentTracked(TradeExecutionRecord execution) {
        return new CreateOutcome(createIfAbsent(execution), true);
    }

    /** All executions for a trigger source currently in the given status (e.g. WTX_AUTO + ENTRY_SUBMITTED). */
    List<TradeExecutionRecord> findByTriggerSourceAndStatus(ExecutionTriggerSource triggerSource, ExecutionStatus status);

    TradeExecutionRecord save(TradeExecutionRecord execution);

    Optional<TradeExecutionRecord> findById(Long id);

    Optional<TradeExecutionRecord> findByIdForUpdate(Long id);

    Optional<TradeExecutionRecord> findByMentorSignalReviewId(Long mentorSignalReviewId);

    List<TradeExecutionRecord> findByMentorSignalReviewIds(Collection<Long> mentorSignalReviewIds);

    /**
     * Slice 3a — IBKR fill tracking.
     * Lookup executions by the TWS {@code orderId} assigned when the entry order was placed.
     */
    Optional<TradeExecutionRecord> findByIbkrOrderId(Integer ibkrOrderId);

    /**
     * Lookup by the IBKR {@code permId} — the DURABLE, never-reused broker order id. Preferred over
     * {@link #findByIbkrOrderId} for reconciliation: {@code ibkrOrderId} is reused after a gateway
     * reconnect, so multiple rows can collide on it; {@code permId} is unique for the life of the order.
     */
    Optional<TradeExecutionRecord> findByPermId(Long permId);

    /**
     * Slice 3a — IBKR fill tracking.
     * Fallback lookup by {@code orderRef} (which equals the {@code executionKey}) when
     * the IBKR order id has not yet been persisted on the execution row.
     */
    Optional<TradeExecutionRecord> findByExecutionKey(String executionKey);

    /**
     * PR #303 — auto-arm pipeline.
     * Returns the most recent non-terminal execution for the given instrument
     * (any trigger source, any non-terminal status). Used by the auto-arm
     * evaluator's "no active execution" gate so we never auto-arm on top of an
     * existing position.
     */
    Optional<TradeExecutionRecord> findActiveByInstrument(String instrument);

    /**
     * WTX auto-execution — most recent non-terminal execution for the given instrument
     * filtered to a single trigger source. Lets the WTX bridge locate its own open row
     * (the one it must close/reverse) without ever touching a mentor or quant execution
     * for the same symbol.
     */
    Optional<TradeExecutionRecord> findActiveByInstrumentAndTriggerSource(String instrument,
                                                                          ExecutionTriggerSource triggerSource);

    /**
     * WTX auto-execution — most recent non-terminal execution for the given
     * (instrument, timeframe) filtered to a single trigger source. WTX state is
     * per-timeframe, so the bridge must scope its open-row lookup to the timeframe
     * too — otherwise a 10m reverse/close could target a 5m execution row.
     */
    Optional<TradeExecutionRecord> findActiveByInstrumentAndTimeframeAndTriggerSource(
            String instrument, String timeframe, ExecutionTriggerSource triggerSource);

    /**
     * Account-scoped variant of {@link #findActiveByInstrumentAndTimeframeAndTriggerSource}. The unified
     * OrderRouter reconciles broker position truth per IBKR account, so it must locate ONLY its own
     * account's open row — never another account's. Returning a different account's row would let a
     * CLOSE/FLATTEN submit a reducing order on the wrong account (it closes using the row's own
     * {@code brokerAccountId}), flattening a position the intent never meant to touch.
     *
     * <p>{@code brokerAccountId} is the resolved (non-null) account the row was persisted with. The JPA
     * adapter overrides this to push the account filter into the query, so the right account's row is
     * returned even when several accounts each hold one for the same (instrument, timeframe, source).
     * This default (for in-memory test fakes) merely filters the unscoped single-row result by account
     * — adequate for single-account fakes, not a substitute for the account-in-query adapter.</p>
     */
    default Optional<TradeExecutionRecord> findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccount(
            String instrument, String timeframe, ExecutionTriggerSource triggerSource, String brokerAccountId) {
        return findActiveByInstrumentAndTimeframeAndTriggerSource(instrument, timeframe, triggerSource)
            .filter(r -> brokerAccountId != null && brokerAccountId.equals(r.getBrokerAccountId()));
    }

    /**
     * PR #303 — auto-arm pipeline.
     * Returns all currently-armed (PENDING_ENTRY_SUBMISSION) executions whose
     * {@code triggerSource} matches the supplied source. Used by the
     * auto-submit scheduler and by the {@code GET /api/quant/auto-arm/active}
     * endpoint that lists pending arms for the UI.
     */
    List<TradeExecutionRecord> findPendingByTriggerSource(ExecutionTriggerSource triggerSource);

    /**
     * Active Positions Panel — return all executions whose status is non-terminal
     * (i.e. anything except CLOSED, CANCELLED, REJECTED, FAILED). Used by the
     * Active Positions panel's REST snapshot endpoint and the periodic WS
     * publisher to populate "currently in flight" trades across all instruments
     * and all trigger sources.
     */
    List<TradeExecutionRecord> findAllActive();

    /**
     * Slice D — D2 (reverse deferred-open). All deferred REVERSE open legs awaiting their close fill: rows
     * still in {@code PENDING_ENTRY_SUBMISSION} that carry a non-null
     * {@link TradeExecutionRecord#getDeferredReverseCloseRowId()}. {@code ReverseDeferredOpenScheduler}
     * polls these and submits each once its linked close row is confirmed flat. The default returns empty
     * (in-memory fakes never defer); the JPA adapter overrides it with a query.
     */
    default List<TradeExecutionRecord> findPendingDeferredReverseOpens() {
        return List.of();
    }
}

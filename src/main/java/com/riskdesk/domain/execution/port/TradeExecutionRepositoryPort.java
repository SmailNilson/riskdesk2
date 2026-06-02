package com.riskdesk.domain.execution.port;

import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeExecutionRepositoryPort {

    TradeExecutionRecord createIfAbsent(TradeExecutionRecord execution);

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
}

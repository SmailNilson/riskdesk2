package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Settles a WTX close whose P&L was booked <b>optimistically</b> on submission against the broker's actual
 * outcome — the real-money accounting prerequisite for the position reconciler.
 *
 * <p><b>Why.</b> {@code WtxStrategyService} books a close's realized P&L the instant it decides to close
 * (synchronous, optimistic) and routes a {@code LMT DAY} close order that may rest then cancel/expire
 * unfilled. If the position is later re-adopted (because the close never completed), that optimistic P&L
 * stays booked and the eventual real close double-books it — corrupting daily P&L and max-loss decisions.
 * {@code closePosition} therefore marks the booked amount as {@link WtxStrategyState#pendingClosePnl()
 * pending}; this settler resolves it each bar against execution-row truth:</p>
 *
 * <ul>
 *   <li>no non-terminal row (the close FILLED → row {@code CLOSED}/gone, position flat) → <b>finalize</b>
 *       (the P&L is real, just clear the marker);</li>
 *   <li>row {@code ACTIVE} (the close cancelled/expired WITHOUT a fill — the fill tracker revives such a
 *       close row to {@code ACTIVE} — or no close ever went out) → the position is still live →
 *       <b>roll back</b> the optimistic P&L;</li>
 *   <li>row {@code EXIT_SUBMITTED} / {@code VIRTUAL_EXIT_TRIGGERED} (the close is still in flight) →
 *       <b>wait</b> — a later bar resolves it.</li>
 * </ul>
 *
 * <p>This handles only the <b>P&L</b>. The position SIDE is corrected separately (by the position
 * reconciler), so this settler is deliberately side-agnostic — it just makes the booked P&L final only
 * once the broker confirms the close. Because both the legacy bridge and the unified router persist these
 * {@code WTX_AUTO} rows, it corrects legacy and unified alike.</p>
 */
@Component
@ConditionalOnProperty(name = "riskdesk.wtx.enabled", havingValue = "true")
public class WtxClosePnlSettler {

    private static final Logger log = LoggerFactory.getLogger(WtxClosePnlSettler.class);

    private final TradeExecutionRepositoryPort executionRepository;

    public WtxClosePnlSettler(TradeExecutionRepositoryPort executionRepository) {
        this.executionRepository = executionRepository;
    }

    /** Resolve a pending optimistic-close P&L against execution-row truth. No-op when nothing is pending. */
    public WtxStrategyState settle(WtxStrategyState state) {
        if (state == null || !state.hasPendingClose()) {
            return state;
        }

        Optional<TradeExecutionRecord> row = executionRepository.findActiveByInstrumentAndTimeframeAndTriggerSource(
            state.instrument(), state.timeframe(), ExecutionTriggerSource.WTX_AUTO);

        if (row.isEmpty()) {
            // No non-terminal row — the close FILLED (row CLOSED/gone), the position is flat. P&L is final.
            return state.withPendingClosePnlFinalized();
        }

        ExecutionStatus status = row.get().getStatus();
        if (status == ExecutionStatus.ACTIVE) {
            // The close did NOT complete (cancelled/expired without a fill → fill tracker revived the row to
            // ACTIVE, or no close order ever went out): the position is still live, so the optimistic close
            // never happened — roll its P&L back. (The reconciler re-adopts the side separately.)
            log.warn("WTX [{} {}] close P&L rolled back — close did not complete (row ACTIVE), pending {} un-booked",
                state.instrument(), state.timeframe(), state.pendingClosePnl());
            return state.withClosePnlRolledBack();
        }

        // EXIT_SUBMITTED / VIRTUAL_EXIT_TRIGGERED — the close is still in flight; wait for it to resolve.
        return state;
    }
}

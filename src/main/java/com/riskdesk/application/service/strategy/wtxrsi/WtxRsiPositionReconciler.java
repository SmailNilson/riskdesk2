package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * P2 part 2 (root cause R3) for WTX-RSI — re-syncs the {@code (instrument, timeframe)} virtual position
 * SIDE against the EXECUTION ROW truth at the start of each bar. The WTX-RSI counterpart of
 * {@link com.riskdesk.application.service.strategy.WtxPositionReconciler}.
 *
 * <p><b>Why.</b> {@code WtxRsiStrategyService} books {@code currentPosition} optimistically from the
 * synchronous routing outcome and never re-checks it. An async divergence — a close that cancelled/expired,
 * a missed fill, a restart, a manual/external close — therefore strands the strategy on a phantom side, so
 * its next signal reads "already LONG" and emits {@code NONE} / {@code ENTRY-IN-FLIGHT} while IBKR is flat
 * (the exact symptom that cost money). Reconciling each bar lets the strategy self-heal.</p>
 *
 * <p><b>Why the execution row, not the broker net.</b> The per-{@code (instrument, timeframe, WTXRSI_AUTO)}
 * row is the timeframe-correct truth, and the broker-truth reconciler (P1) already keeps that row aligned
 * with IBKR (a phantom ACTIVE over a flat broker is closed there). So row truth = broker truth, per
 * timeframe. An in-flight ENTRY is left on the optimistic side (no confirmed position yet to adopt).</p>
 */
@Component
@ConditionalOnProperty(name = "riskdesk.wtxrsi.enabled", havingValue = "true")
public class WtxRsiPositionReconciler {

    private static final Logger log = LoggerFactory.getLogger(WtxRsiPositionReconciler.class);

    private final TradeExecutionRepositoryPort executionRepository;

    public WtxRsiPositionReconciler(TradeExecutionRepositoryPort executionRepository) {
        this.executionRepository = executionRepository;
    }

    /**
     * Returns {@code state} re-synced to the execution-row truth, or unchanged when paper (auto off), while
     * an entry is in flight, or when already aligned. {@code currentClose} realises a diverging missed-close
     * at best effort so the realized-P&L figure (and the daily loss cap) reflects it.
     */
    public WtxRsiStrategyState reconcile(WtxRsiStrategyState state, Instrument instrument, BigDecimal currentClose) {
        if (state == null || !state.autoExecutionEnabled()) {
            return state; // paper timeframe — no broker truth to reconcile against
        }

        TradeExecutionRecord row = executionRepository
            .findActiveByInstrumentAndTimeframeAndTriggerSource(
                state.instrument(), state.timeframe(), ExecutionTriggerSource.WTXRSI_AUTO)
            .orElse(null);

        WtxRsiPosition rowSide;
        if (row == null) {
            rowSide = WtxRsiPosition.FLAT; // no non-terminal row → the position is closed
        } else {
            switch (row.getStatus()) {
                case ACTIVE, EXIT_SUBMITTED, VIRTUAL_EXIT_TRIGGERED -> rowSide = sideOf(row);
                case PENDING_ENTRY_SUBMISSION, ENTRY_SUBMITTED, ENTRY_PARTIALLY_FILLED -> {
                    return state; // entry resting / partial — keep the optimistic side
                }
                default -> rowSide = WtxRsiPosition.FLAT; // terminal (findActive excludes these) — be defensive
            }
        }

        if (rowSide == state.currentPosition()) {
            return state; // already aligned
        }

        log.warn("WTX-RSI [{} {}] position reconcile: strategy={} but execution-row truth={} — correcting to row",
            state.instrument(), state.timeframe(), state.currentPosition(), rowSide);

        WtxRsiStrategyState corrected = state;
        if (state.currentPosition() != WtxRsiPosition.FLAT) {
            // The position the strategy still believed it held is gone (a missed close) or flipped — realise
            // it at the current close (best-effort) so the realized-P&L day figure reflects it.
            corrected = corrected.withFlat(bestEffortRealizedPnl(state, instrument, currentClose));
        }
        if (rowSide != WtxRsiPosition.FLAT) {
            // Re-adopt the live position the row records, with the row's stored SL/TP (set at OPEN).
            corrected = corrected.withPosition(rowSide, adoptEntryPrice(row), adoptQty(row),
                row.getVirtualStopLoss(), row.getVirtualTakeProfit());
        }
        return corrected;
    }

    private static WtxRsiPosition sideOf(TradeExecutionRecord row) {
        return "SHORT".equalsIgnoreCase(row.getAction()) ? WtxRsiPosition.SHORT : WtxRsiPosition.LONG;
    }

    private static BigDecimal bestEffortRealizedPnl(WtxRsiStrategyState state, Instrument instrument, BigDecimal close) {
        if (state.entryPrice() == null || close == null || state.entryQty() == null) {
            return BigDecimal.ZERO;
        }
        int qty = Math.max(0, state.entryQty().intValue());
        if (qty == 0) {
            return BigDecimal.ZERO;
        }
        Side side = state.currentPosition() == WtxRsiPosition.LONG ? Side.LONG : Side.SHORT;
        return instrument.calculatePnL(state.entryPrice(), close, qty, side);
    }

    /** Adopt the row's actual fill price when known, else its limit (normalized entry) price. */
    private static BigDecimal adoptEntryPrice(TradeExecutionRecord row) {
        BigDecimal avg = row.getAvgFillPrice();
        return avg != null && avg.signum() > 0 ? avg : row.getNormalizedEntryPrice();
    }

    /** Adopt the row's actual filled quantity when known, else its requested quantity. */
    private static BigDecimal adoptQty(TradeExecutionRecord row) {
        BigDecimal filled = row.getFilledQuantity();
        if (filled != null && filled.signum() > 0) {
            return filled;
        }
        return BigDecimal.valueOf(row.getQuantity() != null ? row.getQuantity() : 0);
    }
}

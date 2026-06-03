package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Re-syncs a WTX {@code (instrument, timeframe)} virtual position SIDE against the EXECUTION ROW truth at
 * the start of each bar.
 *
 * <p><b>Why.</b> {@code WtxStrategyService} commits {@code currentPosition} optimistically from the
 * <i>synchronous</i> routing outcome and never re-checks it. An async divergence — a reverse whose close
 * cancels/expires, a missed fill, a restart, a manual close — therefore leaves the strategy permanently on
 * a phantom side, so its reverse / close decisions and the 17:00 force-close fire from the wrong side.
 * Reconciling each bar lets the strategy self-heal.</p>
 *
 * <p><b>Why the execution row, not the broker net.</b> WTX runs SEVERAL timeframes per instrument, so the
 * broker net conflates them; the per-{@code (instrument, timeframe, WTX_AUTO)} execution row is the
 * timeframe-correct truth. Both the legacy bridge and the unified router persist these rows, so this
 * corrects legacy and unified alike.</p>
 *
 * <p><b>Close-P&L is NOT this component's job.</b> {@code WtxClosePnlSettler} owns close-P&L (it books it
 * only once the broker confirms the close). This reconciler runs AFTER the settler and <b>defers entirely
 * while a close is pending</b> ({@link WtxStrategyState#hasPendingClose()}) — re-adopting a position whose
 * close is still resting would clear the settler's pending marker and re-introduce the double-count. Once
 * the settler resolves (finalize → flat / roll back → still live), this reconciler corrects the side.</p>
 *
 * <p><b>Scope.</b> Corrects the SIDE (+ entry price/qty, adopted from the row's actual fill). A diverging
 * <i>outgoing</i> position the strategy still believed it held — a close it MISSED (no pending was booked)
 * — is realized at the current close (best-effort). Runs only when auto-execution is on; never disturbs an
 * in-flight ENTRY.</p>
 */
@Component
@ConditionalOnProperty(name = "riskdesk.wtx.enabled", havingValue = "true")
public class WtxPositionReconciler {

    private static final Logger log = LoggerFactory.getLogger(WtxPositionReconciler.class);

    private final TradeExecutionRepositoryPort executionRepository;

    /**
     * Resolves a stuck {@code ENTRY_SUBMITTED} row against broker truth on demand. Nullable — in the
     * test-only single-arg constructor it is absent, and an in-flight entry then keeps the optimistic
     * side (the legacy "defer" behaviour). When wired (production), a row the live fill tracker never
     * resolved is checked against IBKR <i>this bar</i>: a confirmed phantom (no broker order, IBKR flat)
     * flips the strategy to FLAT so a same-side signal stops evaluating to {@code NONE}.
     */
    @Nullable
    private final WtxStaleEntryReconciler staleEntryReconciler;

    /** Test-only constructor — no broker resolution; an in-flight entry keeps the optimistic side. */
    public WtxPositionReconciler(TradeExecutionRepositoryPort executionRepository) {
        this(executionRepository, null);
    }

    @Autowired
    public WtxPositionReconciler(TradeExecutionRepositoryPort executionRepository,
                                 @Nullable WtxStaleEntryReconciler staleEntryReconciler) {
        this.executionRepository = executionRepository;
        this.staleEntryReconciler = staleEntryReconciler;
    }

    /**
     * Returns {@code state} re-synced to the execution row's truth, or unchanged when paper, while a close
     * is pending (the settler owns that window), when an entry is in flight, or when already aligned.
     * {@code currentClose} books a diverging missed-close at best effort; {@code barAtr} gives a re-adopted
     * position an ATR basis (best-effort proxy for the lost original entry ATR) so ATR-exit profiles keep a
     * protective trailing stop.
     */
    public WtxStrategyState reconcile(WtxStrategyState state, Instrument instrument, BigDecimal currentClose,
                                      BigDecimal barAtr) {
        if (state == null || !state.autoExecutionEnabled()) {
            return state; // paper timeframe — no broker truth to reconcile against
        }
        if (state.hasPendingClose()) {
            // A close is mid-settlement (WtxClosePnlSettler waiting on a resting close). Defer — re-adopting
            // now would clear the pending P&L marker before the settler can finalize/roll it back.
            return state;
        }

        TradeExecutionRecord row = executionRepository
            .findActiveByInstrumentAndTimeframeAndTriggerSource(
                state.instrument(), state.timeframe(), ExecutionTriggerSource.WTX_AUTO)
            .orElse(null);

        WtxPosition rowSide = resolveRowSide(row);
        if (rowSide == null) {
            return state; // entry resting / uncertain — keep the optimistic side
        }

        if (rowSide == state.currentPosition()) {
            // Side aligned — but correct a stale entry BASIS: applyAction left the candle close + configured
            // qty; the fill tracker later writes the broker's actual fill. Adopt it (only once a real fill
            // exists) so P&L / trailing track execution truth; preserve the side + trailing state.
            if (rowSide != WtxPosition.FLAT && hasRealFill(row)) {
                BigDecimal fillPrice = row.getAvgFillPrice();
                BigDecimal fillQty = adoptQty(row);
                if (!entryMatches(state, fillPrice, fillQty)) {
                    log.info("WTX [{} {}] entry-basis reconcile: {} -> fill {} x{} from execution row",
                        state.instrument(), state.timeframe(), state.entryPrice(), fillPrice, fillQty);
                    return state.withEntryDetails(fillPrice, fillQty);
                }
            }
            return state;
        }

        log.warn("WTX [{} {}] position reconcile: strategy={} but execution-row truth={} — correcting to row",
            state.instrument(), state.timeframe(), state.currentPosition(), rowSide);

        WtxStrategyState corrected = state;
        if (state.currentPosition() != WtxPosition.FLAT) {
            // The position the strategy still believed it held is gone (a MISSED close — no pending was
            // booked) or flipped — realize it at the current close (best-effort) so the loss cap reflects it.
            corrected = corrected.withFlat(bestEffortRealizedPnl(state, instrument, currentClose));
        }
        if (rowSide != WtxPosition.FLAT) {
            // 4-arg withPosition so the re-adopted position carries an ATR basis. The prior withFlat cleared
            // entryAtr; without one, an ATR-exit profile's WtxTrailingExitEvaluator returns no-exit and the
            // re-adopted real position has NO protective trailing stop. barAtr is a best-effort proxy for the
            // (lost) original entry ATR.
            corrected = corrected.withPosition(rowSide, adoptEntryPrice(row), adoptQty(row), barAtr);
        }
        return corrected;
    }

    /**
     * The position SIDE implied by the execution-row truth, or {@code null} when the row is an in-flight
     * entry whose side must stay optimistic (genuinely resting, partial, or unconfirmable). A {@code null}
     * row means no non-terminal row exists → the position is FLAT.
     */
    @Nullable
    private WtxPosition resolveRowSide(@Nullable TradeExecutionRecord row) {
        if (row == null) {
            return WtxPosition.FLAT; // no non-terminal row → the position is closed
        }
        return switch (row.getStatus()) {
            case ACTIVE, EXIT_SUBMITTED, VIRTUAL_EXIT_TRIGGERED -> sideOf(row);
            case ENTRY_SUBMITTED -> resolveInFlightSide(row);
            // PENDING (not yet at broker) / PARTIAL (a real position partly exists) — always keep optimistic.
            case PENDING_ENTRY_SUBMISSION, ENTRY_PARTIALLY_FILLED -> null;
            default -> WtxPosition.FLAT; // terminal (findActive excludes these) — be defensive
        };
    }

    /**
     * Resolves an {@code ENTRY_SUBMITTED} row against broker truth. Normally the side stays optimistic
     * (return {@code null} → keep waiting for the fill). But a PHANTOM row — one the live fill tracker
     * never resolved while there is no order resting at the broker and IBKR is flat — would otherwise
     * freeze the side forever and make a same-side signal evaluate to {@code NONE}. Confirming that
     * phantom (the stale-entry reconciler flips it to CANCELLED) returns {@code FLAT} so the optimistic
     * side is corrected this bar. Without a wired reconciler (tests) the legacy defer behaviour stands.
     */
    @Nullable
    private WtxPosition resolveInFlightSide(TradeExecutionRecord row) {
        if (staleEntryReconciler != null
                && staleEntryReconciler.reconcileOne(row, Instant.now()) == WtxStaleEntryReconciler.Resolution.CANCELLED) {
            return WtxPosition.FLAT; // phantom cleared → correct the optimistic side to flat
        }
        return null; // genuinely resting / uncertain → keep the optimistic side
    }

    private static WtxPosition sideOf(TradeExecutionRecord row) {
        return "SHORT".equalsIgnoreCase(row.getAction()) ? WtxPosition.SHORT : WtxPosition.LONG;
    }

    private static BigDecimal bestEffortRealizedPnl(WtxStrategyState state, Instrument instrument, BigDecimal close) {
        if (state.entryPrice() == null || close == null) {
            return BigDecimal.ZERO;
        }
        int qty = state.entryQty() != null ? Math.max(0, state.entryQty().intValue()) : 0;
        if (qty == 0) {
            return BigDecimal.ZERO;
        }
        Side side = state.currentPosition() == WtxPosition.LONG ? Side.LONG : Side.SHORT;
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

    /** True once the broker has reported a real average fill price on the row (vs only the limit price). */
    private static boolean hasRealFill(TradeExecutionRecord row) {
        return row != null && row.getAvgFillPrice() != null && row.getAvgFillPrice().signum() > 0;
    }

    /** True when the strategy's entry basis already equals the given fill price + quantity. */
    private static boolean entryMatches(WtxStrategyState state, BigDecimal fillPrice, BigDecimal fillQty) {
        return state.entryPrice() != null && state.entryPrice().compareTo(fillPrice) == 0
            && state.entryQty() != null && state.entryQty().compareTo(fillQty) == 0;
    }
}

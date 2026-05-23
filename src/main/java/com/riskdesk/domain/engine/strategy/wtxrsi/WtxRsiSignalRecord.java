package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persisted view of a fired WTX+RSI signal — the live counterpart of
 * {@link WtxRsiSignal} enriched with the realised risk plan and routing
 * outcome. The history table is append-only; one row per signal.
 *
 * <p>Action describes what the live engine actually did with the signal:
 * <ul>
 *   <li>{@code OPEN_LONG / OPEN_SHORT} — fresh position</li>
 *   <li>{@code CLOSE_LONG / CLOSE_SHORT} — exit on reversal signal</li>
 *   <li>{@code NONE} — signal observed but suppressed (e.g. position already open)</li>
 * </ul>
 */
public record WtxRsiSignalRecord(
        String instrument,
        String timeframe,
        Instant signalTs,
        WtxRsiSignal.Side side,
        Action action,
        BigDecimal wt1,
        BigDecimal wt2,
        BigDecimal rsi,
        BigDecimal rsiSma,
        BigDecimal chaikin,
        boolean chaikinConfirmed,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,         // nullable
        int contracts,
        WtxRoutingOutcome routingOutcome,
        String routingErrorMessage
) {

    public enum Action {
        OPEN_LONG,
        OPEN_SHORT,
        CLOSE_LONG,
        CLOSE_SHORT,
        NONE
    }
}

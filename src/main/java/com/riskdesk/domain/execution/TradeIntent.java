package com.riskdesk.domain.execution;

import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;

import java.math.BigDecimal;

/**
 * Strategy-neutral execution intent — the single thing a strategy hands to the execution core.
 *
 * <p>Exit-agnostic by design: there is NO stop-loss / take-profit. A position is exited with an
 * opposite-side {@link IntentKind#REVERSE} / {@link IntentKind#CLOSE} or a {@link IntentKind#FLATTEN};
 * orders are entry-only Limit orders. The core owns everything else — idempotence, broker-truth
 * reconciliation (permId), minTick rounding, typed error mapping — so the strategy only builds this.</p>
 *
 * <p>Contract defined by {@code docs/ADR_UNIFIED_EXECUTION_CORE.md} (Phase 2).</p>
 *
 * @param idempotencyKey  strong uniqueness key, e.g. {@code "wtx:MNQ:5m:<signalTs>:OPEN_LONG"}.
 * @param source          which strategy / pipeline produced this intent.
 * @param instrument      target instrument.
 * @param timeframe       strategy timeframe, e.g. {@code "5m"}.
 * @param kind            OPEN / REVERSE / CLOSE / FLATTEN.
 * @param side            target / closing position side; required except for FLATTEN (nullable there).
 * @param quantity        contracts ({@code >= 1}); already sized by the strategy.
 * @param limitPrice      Limit price ({@code > 0}); rounded to the contract minTick by the core, not here.
 * @param brokerAccountId target account; {@code null} lets the gateway resolve the default account.
 */
public record TradeIntent(
    String idempotencyKey,
    ExecutionTriggerSource source,
    Instrument instrument,
    String timeframe,
    IntentKind kind,
    Side side,
    int quantity,
    BigDecimal limitPrice,
    String brokerAccountId
) {
    public TradeIntent {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new IllegalArgumentException("idempotencyKey is required");
        if (source == null) throw new IllegalArgumentException("source is required");
        if (instrument == null) throw new IllegalArgumentException("instrument is required");
        if (timeframe == null || timeframe.isBlank())
            throw new IllegalArgumentException("timeframe is required");
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be >= 1");
        if (limitPrice == null || limitPrice.signum() <= 0)
            throw new IllegalArgumentException("limitPrice must be > 0");
        if (kind != IntentKind.FLATTEN && side == null)
            throw new IllegalArgumentException("side is required for kind " + kind);
    }

    // --- ergonomic factories (what strategies call) -----------------------------------------

    public static TradeIntent open(String idempotencyKey, ExecutionTriggerSource source, Instrument instrument,
                                   String timeframe, Side side, int quantity, BigDecimal limitPrice,
                                   String brokerAccountId) {
        return new TradeIntent(idempotencyKey, source, instrument, timeframe,
            IntentKind.OPEN, side, quantity, limitPrice, brokerAccountId);
    }

    public static TradeIntent reverse(String idempotencyKey, ExecutionTriggerSource source, Instrument instrument,
                                      String timeframe, Side toSide, int quantity, BigDecimal limitPrice,
                                      String brokerAccountId) {
        return new TradeIntent(idempotencyKey, source, instrument, timeframe,
            IntentKind.REVERSE, toSide, quantity, limitPrice, brokerAccountId);
    }

    public static TradeIntent close(String idempotencyKey, ExecutionTriggerSource source, Instrument instrument,
                                    String timeframe, Side side, int quantity, BigDecimal limitPrice,
                                    String brokerAccountId) {
        return new TradeIntent(idempotencyKey, source, instrument, timeframe,
            IntentKind.CLOSE, side, quantity, limitPrice, brokerAccountId);
    }

    public static TradeIntent flatten(String idempotencyKey, ExecutionTriggerSource source, Instrument instrument,
                                      String timeframe, int quantity, BigDecimal limitPrice,
                                      String brokerAccountId) {
        return new TradeIntent(idempotencyKey, source, instrument, timeframe,
            IntentKind.FLATTEN, null, quantity, limitPrice, brokerAccountId);
    }
}

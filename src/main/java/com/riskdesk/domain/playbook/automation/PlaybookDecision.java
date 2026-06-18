package com.riskdesk.domain.playbook.automation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record PlaybookDecision(
    Long id,
    String decisionKey,
    String instrument,
    String timeframe,
    String setupIdentity,
    String setupType,
    String zoneName,
    String direction,
    int checklistScore,
    String verdict,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    BigDecimal rrRatio,
    BigDecimal riskPercent,
    boolean lateEntry,
    String priceSource,
    Instant priceTimestamp,
    Instant evaluatedCandleTs,
    Instant createdAt,
    PlaybookRoutingOutcome routingOutcome,
    String routingErrorMessage,
    Long executionId,
    String entryType,
    BigDecimal invalidationPrice,
    BigDecimal marketPrice
) {
    /** Limit-style entry: rests at the plan price and fills when price trades back through it. */
    public static final String ENTRY_TYPE_LIMIT = "LIMIT";
    /** Stop-style entry: triggers when price breaks through the plan price (confirmation entries). */
    public static final String ENTRY_TYPE_STOP = "STOP";

    public PlaybookDecision {
        Objects.requireNonNull(decisionKey, "decisionKey");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(evaluatedCandleTs, "evaluatedCandleTs");
    }

    /** Legacy-arity constructor: null entryType (LIMIT semantics), no invalidation level. */
    public PlaybookDecision(Long id, String decisionKey, String instrument, String timeframe,
                            String setupIdentity, String setupType, String zoneName, String direction,
                            int checklistScore, String verdict, BigDecimal entryPrice, BigDecimal stopLoss,
                            BigDecimal takeProfit1, BigDecimal takeProfit2, BigDecimal rrRatio,
                            BigDecimal riskPercent, boolean lateEntry, String priceSource,
                            Instant priceTimestamp, Instant evaluatedCandleTs, Instant createdAt,
                            PlaybookRoutingOutcome routingOutcome, String routingErrorMessage, Long executionId) {
        this(id, decisionKey, instrument, timeframe, setupIdentity, setupType, zoneName, direction,
            checklistScore, verdict, entryPrice, stopLoss, takeProfit1, takeProfit2, rrRatio, riskPercent,
            lateEntry, priceSource, priceTimestamp, evaluatedCandleTs, createdAt, routingOutcome,
            routingErrorMessage, executionId, null, null);
    }

    /** Compatibility constructor without {@code marketPrice} (defaults to {@code null}). */
    public PlaybookDecision(Long id, String decisionKey, String instrument, String timeframe,
                            String setupIdentity, String setupType, String zoneName, String direction,
                            int checklistScore, String verdict, BigDecimal entryPrice, BigDecimal stopLoss,
                            BigDecimal takeProfit1, BigDecimal takeProfit2, BigDecimal rrRatio,
                            BigDecimal riskPercent, boolean lateEntry, String priceSource,
                            Instant priceTimestamp, Instant evaluatedCandleTs, Instant createdAt,
                            PlaybookRoutingOutcome routingOutcome, String routingErrorMessage, Long executionId,
                            String entryType, BigDecimal invalidationPrice) {
        this(id, decisionKey, instrument, timeframe, setupIdentity, setupType, zoneName, direction,
            checklistScore, verdict, entryPrice, stopLoss, takeProfit1, takeProfit2, rrRatio, riskPercent,
            lateEntry, priceSource, priceTimestamp, evaluatedCandleTs, createdAt, routingOutcome,
            routingErrorMessage, executionId, entryType, invalidationPrice, null);
    }

    public boolean isStopEntry() {
        return ENTRY_TYPE_STOP.equalsIgnoreCase(entryType);
    }

    /**
     * The price the live broker would realistically fill at. For a {@link #lateEntry()}
     * the planned {@link #entryPrice()} is already breached, so the live order chases to
     * the market price observed at decision time ({@link #marketPrice()}); otherwise the
     * STOP/LIMIT fills at the plan. Falls back to {@link #entryPrice()} when the market
     * price is unavailable (degraded feed, legacy rows persisted before this field).
     */
    public BigDecimal realisticEntryPrice() {
        return (lateEntry && marketPrice != null) ? marketPrice : entryPrice;
    }

    public PlaybookDecision withRouting(PlaybookRoutingOutcome outcome, String errorMessage, Long executionId) {
        return new PlaybookDecision(
            id,
            decisionKey,
            instrument,
            timeframe,
            setupIdentity,
            setupType,
            zoneName,
            direction,
            checklistScore,
            verdict,
            entryPrice,
            stopLoss,
            takeProfit1,
            takeProfit2,
            rrRatio,
            riskPercent,
            lateEntry,
            priceSource,
            priceTimestamp,
            evaluatedCandleTs,
            createdAt,
            outcome,
            errorMessage,
            executionId,
            entryType,
            invalidationPrice,
            marketPrice
        );
    }
}

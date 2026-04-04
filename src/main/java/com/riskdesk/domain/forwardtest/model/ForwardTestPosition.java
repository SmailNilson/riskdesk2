package com.riskdesk.domain.forwardtest.model;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * A forward-test virtual position with dual-leg scaling-in support.
 * Immutable value object — state transitions return new instances.
 */
public record ForwardTestPosition(
        Long id,
        long accountId,
        Long mentorSignalReviewId,
        Instrument instrument,
        Side side,
        String timeframe,
        ForwardTestStatus status,
        // Pricing from Mentor
        BigDecimal entryStandard,
        BigDecimal safeDeepEntry,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        // Legs
        PositionLeg leg1,
        PositionLeg leg2,
        // Resolution
        BigDecimal exitPrice,
        BigDecimal realizedPnl,
        BigDecimal commissionTotal,
        BigDecimal netPnl,
        BigDecimal maxDrawdownPoints,
        BigDecimal maxFavorablePoints,
        // Timestamps
        Instant createdAt,
        Instant activatedAt,
        Instant resolvedAt,
        Instant expiresAt
) {

    /** Total filled quantity across both legs. */
    public int filledQuantity() {
        int qty = 0;
        if (leg1 != null && leg1.isFilled()) qty += leg1.quantity();
        if (leg2 != null && leg2.isFilled()) qty += leg2.quantity();
        return qty;
    }

    /** Weighted average entry price across filled legs. */
    public BigDecimal averageEntry() {
        if (leg1 == null || !leg1.isFilled()) return null;
        if (leg2 == null || !leg2.isFilled()) return leg1.fillPrice();
        BigDecimal total = leg1.fillPrice().multiply(BigDecimal.valueOf(leg1.quantity()))
                .add(leg2.fillPrice().multiply(BigDecimal.valueOf(leg2.quantity())));
        return total.divide(BigDecimal.valueOf(filledQuantity()), 10, RoundingMode.HALF_UP);
    }

    /** True if Leg 2 target exists (Mentor provided safe_deep_entry). */
    public boolean hasDualLegs() {
        return safeDeepEntry != null && leg2 != null;
    }

    // ── State transition builders ────────────────────────────────────────────

    public ForwardTestPosition withStatus(ForwardTestStatus newStatus) {
        return new ForwardTestPosition(id, accountId, mentorSignalReviewId, instrument, side,
                timeframe, newStatus, entryStandard, safeDeepEntry, stopLoss, takeProfit,
                leg1, leg2, exitPrice, realizedPnl, commissionTotal, netPnl,
                maxDrawdownPoints, maxFavorablePoints, createdAt, activatedAt, resolvedAt, expiresAt);
    }

    public ForwardTestPosition withLeg1(PositionLeg filled) {
        return new ForwardTestPosition(id, accountId, mentorSignalReviewId, instrument, side,
                timeframe, status, entryStandard, safeDeepEntry, stopLoss, takeProfit,
                filled, leg2, exitPrice, realizedPnl, commissionTotal, netPnl,
                maxDrawdownPoints, maxFavorablePoints, createdAt, activatedAt, resolvedAt, expiresAt);
    }

    public ForwardTestPosition withLeg2(PositionLeg filled) {
        return new ForwardTestPosition(id, accountId, mentorSignalReviewId, instrument, side,
                timeframe, status, entryStandard, safeDeepEntry, stopLoss, takeProfit,
                leg1, filled, exitPrice, realizedPnl, commissionTotal, netPnl,
                maxDrawdownPoints, maxFavorablePoints, createdAt, activatedAt, resolvedAt, expiresAt);
    }

    public ForwardTestPosition withActivation(Instant time, ForwardTestStatus newStatus) {
        return new ForwardTestPosition(id, accountId, mentorSignalReviewId, instrument, side,
                timeframe, newStatus, entryStandard, safeDeepEntry, stopLoss, takeProfit,
                leg1, leg2, exitPrice, realizedPnl, commissionTotal, netPnl,
                maxDrawdownPoints, maxFavorablePoints, createdAt, time, resolvedAt, expiresAt);
    }

    public ForwardTestPosition withResolution(ForwardTestStatus newStatus, BigDecimal exit,
                                              BigDecimal pnl, BigDecimal commission, BigDecimal net,
                                              Instant time) {
        return new ForwardTestPosition(id, accountId, mentorSignalReviewId, instrument, side,
                timeframe, newStatus, entryStandard, safeDeepEntry, stopLoss, takeProfit,
                leg1, leg2, exit, pnl, commission, net,
                maxDrawdownPoints, maxFavorablePoints, createdAt, activatedAt, time, expiresAt);
    }

    public ForwardTestPosition withDrawdown(BigDecimal drawdown, BigDecimal favorable) {
        BigDecimal newDd = maxDrawdownPoints == null ? drawdown
                : drawdown.compareTo(maxDrawdownPoints) > 0 ? drawdown : maxDrawdownPoints;
        BigDecimal newFav = maxFavorablePoints == null ? favorable
                : favorable.compareTo(maxFavorablePoints) > 0 ? favorable : maxFavorablePoints;
        return new ForwardTestPosition(id, accountId, mentorSignalReviewId, instrument, side,
                timeframe, status, entryStandard, safeDeepEntry, stopLoss, takeProfit,
                leg1, leg2, exitPrice, realizedPnl, commissionTotal, netPnl,
                newDd, newFav, createdAt, activatedAt, resolvedAt, expiresAt);
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    public static ForwardTestPosition create(
            long accountId, Long reviewId, Instrument instrument, Side side, String timeframe,
            BigDecimal entryStandard, BigDecimal safeDeepEntry, BigDecimal stopLoss, BigDecimal takeProfit,
            PositionLeg leg1, PositionLeg leg2, Instant createdAt, Instant expiresAt) {
        return new ForwardTestPosition(
                null, accountId, reviewId, instrument, side, timeframe,
                ForwardTestStatus.PENDING_LEG1,
                entryStandard, safeDeepEntry, stopLoss, takeProfit,
                leg1, leg2,
                null, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO,
                createdAt, null, null, expiresAt);
    }
}

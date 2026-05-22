package com.riskdesk.domain.quant.simulation;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Immutable per-trade record produced by the Quant 7-Gates simulation harness.
 *
 * <p>One instance is created when the live snapshot stream emits a setup that
 * satisfies the harness entry rule:
 * <ul>
 *   <li>LONG  — pattern label "Absorption haussière" (or "Vrai achat"), HIGH
 *       confidence, reason carries {@code [Δ CONFIRMED]} AND
 *       {@code [ABS BULL ACTIVE]}, pattern action seen from LONG side is
 *       {@code TRADE}.</li>
 *   <li>SHORT — pattern label "Distribution silencieuse" (or "Vraie vente"),
 *       HIGH confidence, reason carries {@code [Δ CONFIRMED]} AND
 *       {@code [ABS BEAR ACTIVE]}, pattern action seen from SHORT side is
 *       {@code TRADE}.</li>
 * </ul>
 *
 * <p>The position closes — {@link Quant7GatesSimulationStatus#CLOSED_FLOW_AVOID}
 * — when a subsequent snapshot shows the pattern action flipping to
 * {@code AVOID} for the trade direction. SL/TP exits are also supported when
 * the live price crosses the configured offsets (mirroring the suggested plan
 * in {@code QuantSnapshot}).
 *
 * <p>{@link #priceSource} preserves the {@code QuantSnapshot.priceSource()}
 * value at entry time so operators can tell whether the row was driven by
 * live IBKR ticks ({@code LIVE_PUSH}) or by a DB fallback during a feed
 * outage. {@link #exitPriceSource} tracks the source of the LATEST priced
 * value — i.e. the mark-to-market reading on an OPEN row and the close
 * price on a CLOSED row — so a trade entered on {@code LIVE_PUSH} but
 * marked / closed on fallback prices renders its exit pill in degraded
 * colour. Without this split, the source label always mirrored entry and
 * silently misrepresented the source actually driving the displayed
 * exit price / P&amp;L.
 *
 * <p>This aggregate intentionally lives outside {@code trade_simulations} —
 * it's a quant-evaluator validation harness, NOT a Mentor outcome tracker,
 * so it does not pollute the {@code MentorSignalReviewRecord} schema (see
 * "Simulation Decoupling Rule" in CLAUDE.md).
 */
public record Quant7GatesSimulation(
    long id,
    Instrument instrument,
    Direction direction,
    double entryPrice,
    double stopLoss,
    double takeProfit1,
    double takeProfit2,
    Instant openedAt,
    String entryReason,
    /** Origin of {@code entryPrice} (e.g. {@code LIVE_PUSH}, {@code DB_FALLBACK}). Never null — empty string if unknown. */
    String priceSource,
    Quant7GatesSimulationStatus status,
    Double exitPrice,
    /**
     * Origin of the latest {@code exitPrice} reading — the mark-to-market
     * snapshot while OPEN and the close price once resolved. Lets the UI
     * surface a degraded pill on trades whose exit data fell back even
     * though entry was live (or vice-versa).
     */
    String exitPriceSource,
    Instant closedAt,
    String exitReason,
    Double pnlPoints,
    Double pnlUsd
) {

    public enum Direction { LONG, SHORT }

    public Quant7GatesSimulation {
        priceSource = priceSource == null ? "" : priceSource;
        exitPriceSource = exitPriceSource == null ? "" : exitPriceSource;
    }

    public boolean isOpen() {
        return status == Quant7GatesSimulationStatus.OPEN;
    }

    /**
     * Returns a copy of this simulation closed at {@code now} for {@code reason},
     * with the resulting P&amp;L computed from {@code exitPrice} and the
     * instrument contract multiplier.
     */
    public Quant7GatesSimulation close(double exitPrice,
                                       String exitPriceSource,
                                       Instant now,
                                       String reason,
                                       Quant7GatesSimulationStatus newStatus) {
        double signedPts = direction == Direction.LONG
            ? (exitPrice - entryPrice)
            : (entryPrice - exitPrice);
        double mult = instrument.getContractMultiplier().doubleValue();
        return new Quant7GatesSimulation(
            id, instrument, direction, entryPrice, stopLoss, takeProfit1, takeProfit2,
            openedAt, entryReason, priceSource, newStatus, exitPrice,
            exitPriceSource == null ? "" : exitPriceSource,
            now, reason, signedPts, signedPts * mult);
    }

    /** Marks-to-market against {@code livePrice} without changing status (read-only view). */
    public Quant7GatesSimulation markToMarket(double livePrice, String livePriceSource) {
        if (!isOpen()) return this;
        double signedPts = direction == Direction.LONG
            ? (livePrice - entryPrice)
            : (entryPrice - livePrice);
        double mult = instrument.getContractMultiplier().doubleValue();
        return new Quant7GatesSimulation(
            id, instrument, direction, entryPrice, stopLoss, takeProfit1, takeProfit2,
            openedAt, entryReason, priceSource, status, livePrice,
            livePriceSource == null ? "" : livePriceSource,
            closedAt, exitReason, signedPts, signedPts * mult);
    }
}

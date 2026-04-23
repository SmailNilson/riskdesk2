package com.riskdesk.domain.simulation;

import com.riskdesk.domain.model.TradeSimulationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain aggregate for a simulated trade outcome.
 *
 * <p>Phase 1a foundation (see {@code docs/ARCHITECTURE_PRINCIPLES.md} § "Simulation
 * Decoupling Rule"). This aggregate will eventually replace the simulation-related
 * fields currently co-located on {@code MentorSignalReviewRecord} and
 * {@code MentorAudit}. It is not yet wired into any write path — a follow-up PR
 * will enable dual-write from {@code TradeSimulationService}.
 *
 * <p>Pure Java record: no Spring, no JPA, no Lombok.
 *
 * @param id                  row id, {@code null} until persisted
 * @param reviewId            id of the backing review (signal or audit)
 * @param reviewType          which review table this row refers to — must not be null
 * @param instrument          trading symbol (e.g. {@code "MNQ"}), must not be blank
 * @param action              {@code "LONG"} or {@code "SHORT"}, must not be blank
 * @param simulationStatus    current state in the simulation lifecycle, must not be null
 * @param activationTime      when the simulation transitioned to {@code ACTIVE}
 * @param resolutionTime      when the simulation resolved (WIN/LOSS/MISSED/CANCELLED)
 * @param maxDrawdownPoints   worst adverse excursion before resolution, in price points
 * @param trailingStopResult  dual-track trailing stop outcome (raw string per
 *                            {@link com.riskdesk.domain.model.TrailingStopResult})
 * @param trailingExitPrice   dynamic exit price computed by the trailing stop
 * @param bestFavorablePrice  Maximum Favorable Excursion (MFE)
 * @param createdAt           row creation timestamp, must not be null
 */
public record TradeSimulation(
    Long id,
    long reviewId,
    ReviewType reviewType,
    String instrument,
    String action,
    TradeSimulationStatus simulationStatus,
    Instant activationTime,
    Instant resolutionTime,
    BigDecimal maxDrawdownPoints,
    String trailingStopResult,
    BigDecimal trailingExitPrice,
    BigDecimal bestFavorablePrice,
    Instant createdAt
) {

    public TradeSimulation {
        Objects.requireNonNull(reviewType, "reviewType");
        Objects.requireNonNull(simulationStatus, "simulationStatus");
        Objects.requireNonNull(createdAt, "createdAt");
        if (instrument == null || instrument.isBlank()) {
            throw new IllegalArgumentException("instrument must not be blank");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
    }

    /** Convenience copy: same identity, updated status + resolutionTime. */
    public TradeSimulation withStatus(TradeSimulationStatus newStatus, Instant resolvedAt) {
        return new TradeSimulation(
            id,
            reviewId,
            reviewType,
            instrument,
            action,
            newStatus,
            activationTime,
            resolvedAt,
            maxDrawdownPoints,
            trailingStopResult,
            trailingExitPrice,
            bestFavorablePrice,
            createdAt
        );
    }
}

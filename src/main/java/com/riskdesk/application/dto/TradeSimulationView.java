package com.riskdesk.application.dto;

import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Application-layer view of a {@link TradeSimulation} aggregate — Phase 1b.
 *
 * <p>Stable JSON contract exposed via {@code /api/simulations/*}. Field names
 * are part of the public API — do not rename without a frontend update.
 *
 * @param id                 persisted row id
 * @param reviewId           backing review id (SIGNAL or AUDIT)
 * @param reviewType         which review table the row is attached to
 * @param instrument         futures symbol (e.g. {@code "MNQ"})
 * @param action             {@code "LONG"} or {@code "SHORT"}
 * @param simulationStatus   current state (raw enum name)
 * @param activationTime     ISO-8601 UTC instant of transition to {@code ACTIVE}, or null
 * @param resolutionTime     ISO-8601 UTC instant of terminal resolution, or null
 * @param maxDrawdownPoints  worst adverse excursion before resolution, in price points
 * @param trailingStopResult dual-track trailing stop outcome or null
 * @param trailingExitPrice  trailing stop exit price or null
 * @param bestFavorablePrice Maximum Favorable Excursion (MFE) or null
 * @param createdAt          row creation instant (UTC)
 */
public record TradeSimulationView(
    Long id,
    long reviewId,
    ReviewType reviewType,
    String instrument,
    String action,
    String simulationStatus,
    Instant activationTime,
    Instant resolutionTime,
    BigDecimal maxDrawdownPoints,
    String trailingStopResult,
    BigDecimal trailingExitPrice,
    BigDecimal bestFavorablePrice,
    Instant createdAt
) {
    public static TradeSimulationView from(TradeSimulation sim) {
        return new TradeSimulationView(
            sim.id(),
            sim.reviewId(),
            sim.reviewType(),
            sim.instrument(),
            sim.action(),
            sim.simulationStatus() != null ? sim.simulationStatus().name() : null,
            sim.activationTime(),
            sim.resolutionTime(),
            sim.maxDrawdownPoints(),
            sim.trailingStopResult(),
            sim.trailingExitPrice(),
            sim.bestFavorablePrice(),
            sim.createdAt()
        );
    }
}

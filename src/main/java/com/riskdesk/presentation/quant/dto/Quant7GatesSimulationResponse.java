package com.riskdesk.presentation.quant.dto;

import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;

import java.time.Instant;

/**
 * Wire DTO for {@link Quant7GatesSimulation}. Matches the
 * {@code Quant7GatesSimulationView} TypeScript interface on the frontend.
 *
 * <p>Doubles are wrapped so the JSON serialiser emits {@code null} for unset
 * exit fields rather than {@code 0.0} (which the UI would render as a real
 * value).
 */
public record Quant7GatesSimulationResponse(
    long id,
    String instrument,
    String direction,
    double entryPrice,
    double stopLoss,
    double takeProfit1,
    double takeProfit2,
    Instant openedAt,
    String entryReason,
    /** Origin of {@code entryPrice} — e.g. {@code LIVE_PUSH} (real IBKR tick) or {@code DB_FALLBACK}. */
    String priceSource,
    String status,
    Double exitPrice,
    /** Origin of the latest priced reading (mark-to-market while OPEN, close price once resolved). */
    String exitPriceSource,
    Instant closedAt,
    String exitReason,
    Double pnlPoints,
    Double pnlUsd
) {
    public static Quant7GatesSimulationResponse from(Quant7GatesSimulation s) {
        return new Quant7GatesSimulationResponse(
            s.id(),
            s.instrument().name(),
            s.direction().name(),
            s.entryPrice(),
            s.stopLoss(),
            s.takeProfit1(),
            s.takeProfit2(),
            s.openedAt(),
            s.entryReason(),
            s.priceSource(),
            s.status().name(),
            s.exitPrice(),
            s.exitPriceSource(),
            s.closedAt(),
            s.exitReason(),
            s.pnlPoints(),
            s.pnlUsd()
        );
    }
}

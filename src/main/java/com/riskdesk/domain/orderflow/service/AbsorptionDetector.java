package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionSide;

import java.time.Instant;
import java.util.Optional;

/**
 * Pure domain service for absorption detection (UC-OF-004).
 * <p>
 * Absorption occurs when aggressive orders in one direction are matched by passive limit orders
 * on the other side, resulting in high delta + high volume but minimal price movement.
 * This reveals institutional participants absorbing retail flow.
 * <p>
 * Stateless — each call is independent. No Spring, no I/O.
 */
public final class AbsorptionDetector {

    private static final double SCORE_THRESHOLD = 2.0;

    /**
     * Evaluate whether the current market snapshot constitutes an absorption event.
     * <p>
     * Score formula: (|delta| / deltaThreshold) * (1 - priceMoveTicks / atr) * (volume / avgVolume)
     * <p>
     * A score above 2.0 indicates absorption: large directional delta, stable price, elevated volume.
     *
     * @param instrument     the futures instrument
     * @param delta          net aggressive delta (positive = buyers aggressive, negative = sellers aggressive)
     * @param priceMoveTicks price movement in ticks during the detection window
     * @param volume         total volume during the detection window
     * @param atr            current ATR in ticks (used to normalize price movement)
     * @param deltaThreshold baseline delta level for this instrument (normalizer)
     * @param avgVolume      average volume per window (normalizer)
     * @param timestamp      when this snapshot was taken
     * @return absorption signal if score exceeds threshold, empty otherwise
     */
    public Optional<AbsorptionSignal> evaluate(
            Instrument instrument,
            long delta,
            double priceMoveTicks,
            long volume,
            double atr,
            double deltaThreshold,
            double avgVolume,
            Instant timestamp) {

        if (deltaThreshold == 0.0 || atr == 0.0 || avgVolume == 0.0) {
            return Optional.empty();
        }

        double deltaComponent = Math.abs(delta) / deltaThreshold;
        double priceStabilityComponent = 1.0 - (priceMoveTicks / atr);
        double volumeComponent = volume / avgVolume;

        // Price stability must be positive — if price moved more than 1 ATR, no absorption
        if (priceStabilityComponent <= 0.0) {
            return Optional.empty();
        }

        double score = deltaComponent * priceStabilityComponent * volumeComponent;

        if (score <= SCORE_THRESHOLD) {
            return Optional.empty();
        }

        // Determine side:
        // - Negative delta + stable price = sellers are aggressive but price doesn't fall = BULLISH (passive buyers absorbing)
        // - Positive delta + stable price = buyers are aggressive but price doesn't rise = BEARISH (passive sellers absorbing)
        AbsorptionSide side = delta < 0
                ? AbsorptionSide.BULLISH_ABSORPTION
                : AbsorptionSide.BEARISH_ABSORPTION;

        return Optional.of(new AbsorptionSignal(
                instrument,
                side,
                score,
                delta,
                priceMoveTicks,
                volume,
                timestamp
        ));
    }
}

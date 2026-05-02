package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionSide;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal.AbsorptionType;

import java.time.Instant;
import java.util.Optional;

/**
 * Pure domain service for absorption detection (UC-OF-004).
 * <p>
 * Direction is decided by the (delta sign × signed-price-move sign) rule:
 * <ul>
 *   <li>delta&lt;0 + price↑ → BULL DIVERGENCE — buyers absorbing sell pressure (strongest BULL)</li>
 *   <li>delta&gt;0 + price↓ → BEAR DIVERGENCE — sellers absorbing buy pressure (strongest BEAR)</li>
 *   <li>delta&lt;0 + price↓ → BEAR CLASSIC — confirmation</li>
 *   <li>delta&gt;0 + price↑ → BULL CLASSIC — confirmation</li>
 * </ul>
 * Score formulas (asymmetric thresholds reflect rarity vs strength):
 * <ul>
 *   <li>CLASSIC: {@code (|delta|/threshold) * (vol/avgVol)} — gated above {@value #CLASSIC_SCORE_THRESHOLD}</li>
 *   <li>DIVERGENCE: {@code (|delta|/threshold) * (|signedMove|/atr) * (vol/avgVol)} — priceMove/atr is an
 *       amplifier (bigger counter-move ⇒ stronger score), gated above {@value #DIVERGENCE_SCORE_THRESHOLD}</li>
 * </ul>
 * Stateless — each call is independent. No Spring, no I/O.
 */
public final class AbsorptionDetector {

    /** Score gate for CLASSIC absorption (delta and price agree). */
    public static final double CLASSIC_SCORE_THRESHOLD = 2.0;
    /** Score gate for DIVERGENCE absorption (delta and price oppose) — lower because divergence is rarer + stronger. */
    public static final double DIVERGENCE_SCORE_THRESHOLD = 1.5;

    /**
     * Evaluate whether the current market snapshot constitutes an absorption event.
     *
     * @param instrument             the futures instrument
     * @param delta                  net aggressive delta (positive = buyers aggressive, negative = sellers aggressive)
     * @param signedPriceMoveTicks   SIGNED price movement in ticks (last - first within window). Positive = up, negative = down.
     * @param volume                 total volume during the detection window
     * @param atr                    current ATR in ticks (used to normalize price movement amplifier)
     * @param deltaThreshold         baseline delta level for this instrument (normalizer)
     * @param avgVolume              average volume per window (normalizer)
     * @param timestamp              when this snapshot was taken
     * @return absorption signal if the appropriate type-specific score gate is exceeded, empty otherwise
     */
    public Optional<AbsorptionSignal> evaluate(
            Instrument instrument,
            long delta,
            double signedPriceMoveTicks,
            long volume,
            double atr,
            double deltaThreshold,
            double avgVolume,
            Instant timestamp) {

        if (deltaThreshold == 0.0 || atr == 0.0 || avgVolume == 0.0) {
            return Optional.empty();
        }
        // NEUTRAL: cannot classify direction without a sign on either side.
        if (delta == 0L || signedPriceMoveTicks == 0.0) {
            return Optional.empty();
        }
        if (Double.isNaN(signedPriceMoveTicks)) {
            return Optional.empty();
        }

        boolean deltaPositive = delta > 0;
        boolean priceUp = signedPriceMoveTicks > 0.0;

        AbsorptionSide side;
        AbsorptionType type;
        String explanation;

        if (deltaPositive && priceUp) {
            side = AbsorptionSide.BULLISH_ABSORPTION;
            type = AbsorptionType.CLASSIC;
            explanation = "Classic bull confirmation";
        } else if (!deltaPositive && !priceUp) {
            side = AbsorptionSide.BEARISH_ABSORPTION;
            type = AbsorptionType.CLASSIC;
            explanation = "Classic bear confirmation";
        } else if (!deltaPositive && priceUp) {
            // delta < 0 + price ↑ → buyers absorbing sell pressure
            side = AbsorptionSide.BULLISH_ABSORPTION;
            type = AbsorptionType.DIVERGENCE;
            explanation = "Buyers absorbing sell pressure";
        } else {
            // delta > 0 + price ↓ → sellers absorbing buy pressure
            side = AbsorptionSide.BEARISH_ABSORPTION;
            type = AbsorptionType.DIVERGENCE;
            explanation = "Sellers absorbing buy pressure";
        }

        double deltaComponent = Math.abs(delta) / deltaThreshold;
        double volumeComponent = volume / avgVolume;
        double score;
        double gate;
        if (type == AbsorptionType.DIVERGENCE) {
            double moveAmplifier = Math.abs(signedPriceMoveTicks) / atr;
            score = deltaComponent * moveAmplifier * volumeComponent;
            gate = DIVERGENCE_SCORE_THRESHOLD;
        } else {
            score = deltaComponent * volumeComponent;
            gate = CLASSIC_SCORE_THRESHOLD;
        }

        if (score <= gate) {
            return Optional.empty();
        }

        return Optional.of(new AbsorptionSignal(
                instrument,
                side,
                score,
                delta,
                Math.abs(signedPriceMoveTicks),
                volume,
                timestamp,
                type,
                explanation
        ));
    }
}

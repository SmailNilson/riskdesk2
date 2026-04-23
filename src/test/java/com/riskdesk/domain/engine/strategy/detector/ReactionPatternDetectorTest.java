package com.riskdesk.domain.engine.strategy.detector;

import com.riskdesk.domain.engine.strategy.model.ReactionPattern;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactionPatternDetectorTest {

    private static final Instant T = Instant.parse("2026-04-17T10:00:00Z");

    @Test
    void bullish_rejection_pin_bar_classified_as_rejection() {
        // Open 100, high 100.5, low 95, close 100 → body ~0, large lower wick
        Candle c = candle(100.0, 100.5, 95.0, 100.0);
        assertThat(ReactionPatternDetector.classifyLatest(List.of(c)))
            .isEqualTo(ReactionPattern.REJECTION);
    }

    @Test
    void bearish_rejection_pin_bar_classified_as_rejection() {
        // Open 100, high 105, low 99.5, close 100 → body ~0, large upper wick
        Candle c = candle(100.0, 105.0, 99.5, 100.0);
        assertThat(ReactionPatternDetector.classifyLatest(List.of(c)))
            .isEqualTo(ReactionPattern.REJECTION);
    }

    @Test
    void doji_classified_as_indecision() {
        // Open 100, high 100.4, low 99.6, close 100.01 → body tiny, both wicks present
        Candle c = candle(100.0, 100.4, 99.6, 100.01);
        assertThat(ReactionPatternDetector.classifyLatest(List.of(c)))
            .isEqualTo(ReactionPattern.INDECISION);
    }

    @Test
    void marubozu_body_dominant_classified_as_acceptance() {
        // Body covers 90% of range — committed candle
        Candle c = candle(100.0, 110.0, 99.9, 109.5);
        assertThat(ReactionPatternDetector.classifyLatest(List.of(c)))
            .isEqualTo(ReactionPattern.ACCEPTANCE);
    }

    @Test
    void ordinary_candle_classified_as_none() {
        // Body ~40% of range, balanced wicks — nothing distinctive
        Candle c = candle(100.0, 103.0, 99.0, 101.6);
        assertThat(ReactionPatternDetector.classifyLatest(List.of(c)))
            .isEqualTo(ReactionPattern.NONE);
    }

    @Test
    void zero_range_candle_classified_as_none() {
        // Degenerate: no range at all
        Candle c = candle(100.0, 100.0, 100.0, 100.0);
        assertThat(ReactionPatternDetector.classifyLatest(List.of(c)))
            .isEqualTo(ReactionPattern.NONE);
    }

    @Test
    void empty_or_null_list_classified_as_none() {
        assertThat(ReactionPatternDetector.classifyLatest(null))
            .isEqualTo(ReactionPattern.NONE);
        assertThat(ReactionPatternDetector.classifyLatest(List.of()))
            .isEqualTo(ReactionPattern.NONE);
    }

    @Test
    void uses_latest_candle_not_first() {
        Candle old = candle(100.0, 110.0, 90.0, 105.0);           // NONE
        Candle latest = candle(100.0, 100.5, 95.0, 100.0);          // REJECTION
        assertThat(ReactionPatternDetector.classifyLatest(List.of(old, latest)))
            .isEqualTo(ReactionPattern.REJECTION);
    }

    private static Candle candle(double open, double high, double low, double close) {
        return new Candle(Instrument.MGC, "1h", T,
            BigDecimal.valueOf(open), BigDecimal.valueOf(high),
            BigDecimal.valueOf(low),  BigDecimal.valueOf(close), 1000L);
    }
}

package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.domain.engine.indicators.MarketRegimeDetector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data-derived adaptive profile: RIDE only for 5m + TENDANCE; TRAIL otherwise; gated by config for the
 * live RIDE exit. Encodes the backtest finding (5m-tendance rides, 10m/range/choppy trail).
 */
class WtxAdaptiveProfileTest {

    @Test
    void recommend_ride_only_for_5m_trending() {
        assertThat(WtxAdaptiveProfile.recommend("5m", MarketRegimeDetector.TRENDING_UP)).isEqualTo("RIDE");
        assertThat(WtxAdaptiveProfile.recommend("5m", MarketRegimeDetector.TRENDING_DOWN)).isEqualTo("RIDE");
    }

    @Test
    void recommend_trail_for_10m_even_when_trending() {
        assertThat(WtxAdaptiveProfile.recommend("10m", MarketRegimeDetector.TRENDING_UP)).isEqualTo("TRAIL");
        assertThat(WtxAdaptiveProfile.recommend("10m", MarketRegimeDetector.TRENDING_DOWN)).isEqualTo("TRAIL");
    }

    @Test
    void recommend_trail_for_range_and_choppy() {
        assertThat(WtxAdaptiveProfile.recommend("5m", MarketRegimeDetector.RANGING)).isEqualTo("TRAIL");
        assertThat(WtxAdaptiveProfile.recommend("5m", MarketRegimeDetector.CHOPPY)).isEqualTo("TRAIL");
        assertThat(WtxAdaptiveProfile.recommend("10m", MarketRegimeDetector.RANGING)).isEqualTo("TRAIL");
    }

    @Test
    void recommend_null_when_regime_unknown() {
        assertThat(WtxAdaptiveProfile.recommend("5m", null)).isNull();
        assertThat(WtxAdaptiveProfile.recommend("10m", null)).isNull();
    }

    @Test
    void shouldRide_true_only_when_enabled_in_scope_and_5m_trending() {
        List<String> mnq = List.of("MNQ");
        assertThat(WtxAdaptiveProfile.shouldRide(true, mnq, "MNQ", "5m", MarketRegimeDetector.TRENDING_UP)).isTrue();
        assertThat(WtxAdaptiveProfile.shouldRide(true, mnq, "MNQ", "5m", MarketRegimeDetector.TRENDING_DOWN)).isTrue();
    }

    @Test
    void shouldRide_false_when_disabled() {
        assertThat(WtxAdaptiveProfile.shouldRide(false, List.of("MNQ"), "MNQ", "5m", MarketRegimeDetector.TRENDING_UP)).isFalse();
    }

    @Test
    void shouldRide_false_off_timeframe_or_regime() {
        List<String> mnq = List.of("MNQ");
        assertThat(WtxAdaptiveProfile.shouldRide(true, mnq, "MNQ", "10m", MarketRegimeDetector.TRENDING_UP)).isFalse();
        assertThat(WtxAdaptiveProfile.shouldRide(true, mnq, "MNQ", "5m", MarketRegimeDetector.RANGING)).isFalse();
        assertThat(WtxAdaptiveProfile.shouldRide(true, mnq, "MNQ", "5m", MarketRegimeDetector.CHOPPY)).isFalse();
        assertThat(WtxAdaptiveProfile.shouldRide(true, mnq, "MNQ", "5m", null)).isFalse();
    }

    @Test
    void shouldRide_respects_instrument_scope() {
        List<String> mnq = List.of("MNQ");
        assertThat(WtxAdaptiveProfile.shouldRide(true, mnq, "MCL", "5m", MarketRegimeDetector.TRENDING_UP)).isFalse();
        // empty / null scope = all instruments
        assertThat(WtxAdaptiveProfile.shouldRide(true, List.of(), "MCL", "5m", MarketRegimeDetector.TRENDING_UP)).isTrue();
        assertThat(WtxAdaptiveProfile.shouldRide(true, null, "MGC", "5m", MarketRegimeDetector.TRENDING_DOWN)).isTrue();
    }

    @Test
    void recommendGated_matches_actual_engine_behaviour() {
        List<String> mnq = List.of("MNQ");
        // RIDE only when it would truly engage (enabled + in scope + 5m-trending)
        assertThat(WtxAdaptiveProfile.recommendGated(true, mnq, "MNQ", "5m", MarketRegimeDetector.TRENDING_UP)).isEqualTo("RIDE");
        // disabled → badge must NOT claim RIDE
        assertThat(WtxAdaptiveProfile.recommendGated(false, mnq, "MNQ", "5m", MarketRegimeDetector.TRENDING_UP)).isEqualTo("TRAIL");
        // out-of-scope instrument → TRAIL (the bug the review caught)
        assertThat(WtxAdaptiveProfile.recommendGated(true, mnq, "MCL", "5m", MarketRegimeDetector.TRENDING_UP)).isEqualTo("TRAIL");
        // off-timeframe / non-trending → TRAIL
        assertThat(WtxAdaptiveProfile.recommendGated(true, mnq, "MNQ", "10m", MarketRegimeDetector.TRENDING_UP)).isEqualTo("TRAIL");
        assertThat(WtxAdaptiveProfile.recommendGated(true, mnq, "MNQ", "5m", MarketRegimeDetector.RANGING)).isEqualTo("TRAIL");
        // unknown regime → null (hide badge)
        assertThat(WtxAdaptiveProfile.recommendGated(true, mnq, "MNQ", "5m", null)).isNull();
    }
}

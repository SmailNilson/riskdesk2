package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.model.DeltaSignature;
import com.riskdesk.domain.engine.strategy.model.ReactionPattern;
import com.riskdesk.domain.engine.strategy.model.TickDataQuality;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TriggerContextBuilder} — in particular the
 * interaction with {@link CandleRepositoryPort} which returns candles in
 * DESCENDING timestamp order. These tests guard against a real bug spotted in
 * PR review: naively passing the port output to
 * {@link com.riskdesk.domain.engine.strategy.detector.ReactionPatternDetector}
 * causes the detector to classify the oldest candle in the window instead of
 * the newest.
 */
class TriggerContextBuilderTest {

    private static final Instant NOW = Instant.parse("2026-04-17T12:00:00Z");

    @Test
    void classifies_newest_candle_when_repository_returns_descending_order() {
        // Simulate the production adapter: newest first.
        // Newest = strong bullish rejection pin bar (REJECTION)
        Candle newest = candle(NOW,                    100.0, 100.4, 95.0, 100.0);
        // One bar older = ordinary candle (NONE)
        Candle older  = candle(NOW.minusSeconds(60),   100.0, 103.0, 99.0, 101.6);
        // Oldest = acceptance marubozu (ACCEPTANCE) — deliberately the "wrong"
        // answer if ordering is mis-handled.
        Candle oldest = candle(NOW.minusSeconds(120),  100.0, 110.0, 99.9, 109.5);

        DescendingOrderCandleRepo repo = new DescendingOrderCandleRepo(
            List.of(newest, older, oldest)); // desc order, matches JPA adapter

        TriggerContextBuilder builder = new TriggerContextBuilder(repo);
        TriggerContext trig = builder.build(Instrument.MGC, "1h", emptySnapshot());

        // Must pick the NEWEST candle's classification. If this assertion fails
        // with ACCEPTANCE, the builder is reading the oldest candle in the window.
        assertThat(trig.reaction()).isEqualTo(ReactionPattern.REJECTION);
    }

    @Test
    void returns_none_when_repository_empty() {
        TriggerContextBuilder builder = new TriggerContextBuilder(new DescendingOrderCandleRepo(List.of()));
        TriggerContext trig = builder.build(Instrument.MGC, "1h", emptySnapshot());
        assertThat(trig.reaction()).isEqualTo(ReactionPattern.NONE);
    }

    @Test
    void swallows_repository_failure_and_reports_none() {
        TriggerContextBuilder builder = new TriggerContextBuilder(new ThrowingCandleRepo());
        TriggerContext trig = builder.build(Instrument.MGC, "1h", emptySnapshot());
        assertThat(trig.reaction()).isEqualTo(ReactionPattern.NONE);
    }

    @Test
    void back_compat_build_without_candles_reports_none_without_db_call() {
        // The 1-arg variant is used by callers that don't wire candle data.
        // It must not fail, must not touch the repo, and reports NONE.
        ThrowingCandleRepo tripwire = new ThrowingCandleRepo();
        TriggerContextBuilder builder = new TriggerContextBuilder(tripwire);

        TriggerContext trig = builder.build(emptySnapshot());

        assertThat(trig.reaction()).isEqualTo(ReactionPattern.NONE);
        assertThat(tripwire.called).isFalse();
    }

    // ── Real-tick path ─────────────────────────────────────────────────────

    @Test
    void prefers_real_ticks_over_snapshot_when_aggregation_present() {
        TickAggregation agg = aggregation(700, 300, 70.0, false, null);
        StubTickDataPort port = new StubTickDataPort(Instrument.MGC, agg);
        TriggerContextBuilder builder = new TriggerContextBuilder(
            new DescendingOrderCandleRepo(List.of()), port);

        TriggerContext trig = builder.build(Instrument.MGC, "1h", emptySnapshot());

        assertThat(trig.quality()).isEqualTo(TickDataQuality.REAL_TICKS);
        assertThat(trig.qualityMultiplier()).isEqualTo(1.0);
        assertThat(trig.deltaSignature()).isEqualTo(DeltaSignature.FLOW);
        assertThat(trig.buyRatio()).isEqualByComparingTo("0.7");
    }

    @Test
    void real_ticks_with_divergence_classify_as_absorption() {
        // Heavy buying but price-vs-delta divergence → buyers absorbing the move.
        TickAggregation agg = aggregation(800, 200, 80.0, true,
            TickAggregation.DIVERGENCE_BEARISH);
        StubTickDataPort port = new StubTickDataPort(Instrument.MGC, agg);
        TriggerContextBuilder builder = new TriggerContextBuilder(
            new DescendingOrderCandleRepo(List.of()), port);

        TriggerContext trig = builder.build(Instrument.MGC, "1h", emptySnapshot());

        assertThat(trig.quality()).isEqualTo(TickDataQuality.REAL_TICKS);
        assertThat(trig.deltaSignature()).isEqualTo(DeltaSignature.ABSORPTION);
    }

    @Test
    void real_ticks_balanced_buy_ratio_classifies_as_neutral() {
        TickAggregation agg = aggregation(520, 480, 52.0, false, null);
        StubTickDataPort port = new StubTickDataPort(Instrument.MGC, agg);
        TriggerContextBuilder builder = new TriggerContextBuilder(
            new DescendingOrderCandleRepo(List.of()), port);

        TriggerContext trig = builder.build(Instrument.MGC, "1h", emptySnapshot());

        assertThat(trig.quality()).isEqualTo(TickDataQuality.REAL_TICKS);
        assertThat(trig.deltaSignature()).isEqualTo(DeltaSignature.NEUTRAL);
    }

    @Test
    void falls_back_to_snapshot_when_port_returns_empty() {
        StubTickDataPort port = new StubTickDataPort(Instrument.MGC, null);
        TriggerContextBuilder builder = new TriggerContextBuilder(
            new DescendingOrderCandleRepo(List.of()), port);

        TriggerContext trig = builder.build(Instrument.MGC, "1h", emptySnapshot());

        // No real ticks, snapshot has no delta → UNAVAILABLE (CLV path returns
        // CLV_ESTIMATED only when buyRatio/cumulativeDelta is present).
        assertThat(trig.quality()).isEqualTo(TickDataQuality.UNAVAILABLE);
    }

    @Test
    void falls_back_to_snapshot_when_aggregation_source_is_clv() {
        // The port might return a CLV-flagged aggregation (degraded mode); the
        // builder must NOT promote it to REAL_TICKS.
        TickAggregation clvAgg = new TickAggregation(
            Instrument.MGC, 0, 0, 0L, 0L, 50.0,
            TickAggregation.TREND_FLAT, false, null,
            Instant.parse("2026-04-17T11:55:00Z"), Instant.parse("2026-04-17T12:00:00Z"),
            TickAggregation.SOURCE_CLV_ESTIMATED, Double.NaN, Double.NaN);
        StubTickDataPort port = new StubTickDataPort(Instrument.MGC, clvAgg);
        TriggerContextBuilder builder = new TriggerContextBuilder(
            new DescendingOrderCandleRepo(List.of()), port);

        TriggerContext trig = builder.build(Instrument.MGC, "1h", emptySnapshot());

        assertThat(trig.quality()).isNotEqualTo(TickDataQuality.REAL_TICKS);
    }

    @Test
    void degrades_silently_when_port_throws() {
        TickDataPort port = new TickDataPort() {
            @Override public Optional<TickAggregation> currentAggregation(Instrument i) {
                throw new RuntimeException("simulated infra hiccup");
            }
            @Override public boolean isRealTickDataAvailable(Instrument i) { return false; }
        };
        TriggerContextBuilder builder = new TriggerContextBuilder(
            new DescendingOrderCandleRepo(List.of()), port);

        TriggerContext trig = builder.build(Instrument.MGC, "1h", emptySnapshot());

        // Falls through to CLV path; UNAVAILABLE because the empty snapshot has no delta.
        assertThat(trig.quality()).isEqualTo(TickDataQuality.UNAVAILABLE);
    }

    private static TickAggregation aggregation(long buy, long sell, double pct,
                                                boolean divergence, String divergenceType) {
        return new TickAggregation(
            Instrument.MGC,
            buy, sell, buy - sell, buy - sell,
            pct,
            buy >= sell ? TickAggregation.TREND_RISING : TickAggregation.TREND_FALLING,
            divergence, divergenceType,
            Instant.parse("2026-04-17T11:55:00Z"),
            Instant.parse("2026-04-17T12:00:00Z"),
            TickAggregation.SOURCE_REAL_TICKS,
            100.5, 99.5
        );
    }

    private static final class StubTickDataPort implements TickDataPort {
        private final Instrument key;
        private final TickAggregation agg;

        StubTickDataPort(Instrument key, TickAggregation agg) {
            this.key = key;
            this.agg = agg;
        }

        @Override public Optional<TickAggregation> currentAggregation(Instrument i) {
            return i == key ? Optional.ofNullable(agg) : Optional.empty();
        }

        @Override public boolean isRealTickDataAvailable(Instrument i) {
            return agg != null && i == key;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Candle candle(Instant ts, double o, double h, double l, double c) {
        return new Candle(Instrument.MGC, "1h", ts,
            BigDecimal.valueOf(o), BigDecimal.valueOf(h),
            BigDecimal.valueOf(l), BigDecimal.valueOf(c), 1000L);
    }

    private static IndicatorSnapshot emptySnapshot() {
        // Matches the field layout of IndicatorSnapshot; all nulls are acceptable
        // because TriggerContextBuilder only reads buyRatio, cumulativeDelta,
        // ema9, ema50 — and null-safe code paths collapse to DeltaSignature.NEUTRAL.
        // Structure copied verbatim from AlertServiceTest to stay in lock-step with
        // the record layout. Any future field addition to IndicatorSnapshot will
        // fail this constructor at compile-time and force the test to be updated.
        return new IndicatorSnapshot(
            "MGC", "1h",
            null, null, null, null,   // ema9/50/200, emaCrossover
            null, null,               // rsi, rsiSignal
            null, null, null, null,   // macdLine, macdSignal, macdHistogram, macdCrossover
            null, false,              // supertrendValue, supertrendBullish
            null, null, null,         // vwap, upper/lower
            null, null,               // chaikin, cmf
            null, null, null, null, null, null, false, null,  // bollinger bands family
            null, null, null, null,   // deltaFlow, cumulativeDelta, buyRatio, deltaFlowBias
            null, null, null, null, null,  // waveTrend family
            null, null, null, null,   // stoch K/D, signal, crossover
            null, null, null, null, null, null,  // SMC internal bias family
            null, null, null, null, null, null,  // SMC swing bias family
            false,                    // internalConfluenceFilterEnabled
            null,                     // multiResolutionBias
            null, null, null, null, null, null,  // legacy marketStructure + strong/weak
            null, null, null, null,   // strong/weak times
            List.of(), List.of(),     // equalHighs, equalLows
            null, null, null, null,   // premium/equilibrium/discount/currentZone
            List.of(), List.of(), List.of(), List.of(), List.of(),  // zones + fvg + breaks
            null,                     // MTF levels view
            null, null, null, null,   // session PD array
            null, null, null,         // Volume Profile (poc/vah/val)
            null,                     // sessionPhase
            null,                     // lastCandleTimestamp
            null                      // lastPrice
        );
    }

    /** Fake adapter that mirrors {@code JpaCandleRepositoryAdapter.findRecentCandles}' DESC order. */
    private static final class DescendingOrderCandleRepo implements CandleRepositoryPort {
        private final List<Candle> descending;

        DescendingOrderCandleRepo(List<Candle> descending) {
            this.descending = new ArrayList<>(descending);
        }

        @Override public List<Candle> findRecentCandles(Instrument i, String tf, int limit) {
            return descending.subList(0, Math.min(limit, descending.size()));
        }

        // Unused stubs — fail loudly if the builder ever touches them unexpectedly.
        @Override public List<Candle> findCandles(Instrument i, String tf, Instant from) { throw new AssertionError("unused"); }
        @Override public List<Candle> findRecentCandlesByContractMonth(Instrument i, String tf, String c, int limit) { throw new AssertionError("unused"); }
        @Override public Optional<Instant> findLatestTimestamp(Instrument i, String tf) { throw new AssertionError("unused"); }
        @Override public List<Candle> findCandlesBetween(Instrument i, String tf, Instant from, Instant to) { throw new AssertionError("unused"); }
        @Override public Candle save(Candle c) { throw new AssertionError("unused"); }
        @Override public List<Candle> saveAll(List<Candle> c) { throw new AssertionError("unused"); }
        @Override public void deleteAll() { throw new AssertionError("unused"); }
        @Override public void deleteByInstrumentAndTimeframe(Instrument i, String tf) { throw new AssertionError("unused"); }
        @Override public long count() { throw new AssertionError("unused"); }
    }

    /** Repo that throws on {@code findRecentCandles} — verifies the builder swallows failures. */
    private static final class ThrowingCandleRepo implements CandleRepositoryPort {
        boolean called = false;

        @Override public List<Candle> findRecentCandles(Instrument i, String tf, int limit) {
            called = true;
            throw new RuntimeException("simulated DB outage");
        }

        @Override public List<Candle> findCandles(Instrument i, String tf, Instant from) { throw new AssertionError("unused"); }
        @Override public List<Candle> findRecentCandlesByContractMonth(Instrument i, String tf, String c, int limit) { throw new AssertionError("unused"); }
        @Override public Optional<Instant> findLatestTimestamp(Instrument i, String tf) { throw new AssertionError("unused"); }
        @Override public List<Candle> findCandlesBetween(Instrument i, String tf, Instant from, Instant to) { throw new AssertionError("unused"); }
        @Override public Candle save(Candle c) { throw new AssertionError("unused"); }
        @Override public List<Candle> saveAll(List<Candle> c) { throw new AssertionError("unused"); }
        @Override public void deleteAll() { throw new AssertionError("unused"); }
        @Override public void deleteByInstrumentAndTimeframe(Instrument i, String tf) { throw new AssertionError("unused"); }
        @Override public long count() { throw new AssertionError("unused"); }
    }
}

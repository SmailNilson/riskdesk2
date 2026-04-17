package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.model.ReactionPattern;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
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

package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.advisor.AdvisorPort;
import com.riskdesk.domain.quant.advisor.AiAdvice;
import com.riskdesk.domain.quant.advisor.MultiInstrumentContext;
import com.riskdesk.domain.quant.memory.MemoryRecord;
import com.riskdesk.domain.quant.memory.QuantMemoryPort;
import com.riskdesk.domain.quant.memory.SessionMemory;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QuantAiAdvisorServiceTest {

    private QuantSessionMemoryService sessionMemory;
    private QuantSnapshotHistoryStore historyStore;
    private CountingAdvisor advisor;
    private CountingMemory memory;
    private CountingEmbedder embedder;

    @BeforeEach
    void setUp() {
        sessionMemory = new QuantSessionMemoryService();
        historyStore = new QuantSnapshotHistoryStore();
        advisor = new CountingAdvisor();
        memory = new CountingMemory();
        embedder = new CountingEmbedder();
    }

    @Test
    @DisplayName("Returns UNAVAILABLE when score is below trigger threshold")
    void belowTrigger_returnsUnavailable() {
        QuantAiAdvisorService service = build(/*triggerScore*/ 6, /*cacheSeconds*/ 30);
        QuantSnapshot lowScore = snapshot(Instrument.MNQ, 4);

        AiAdvice advice = service.requestAdviceIfQualified(Instrument.MNQ, lowScore, samplePattern());

        assertThat(advice.verdict()).isEqualTo(AiAdvice.Verdict.UNAVAILABLE);
        assertThat(advisor.calls.get()).isZero();
    }

    @Test
    @DisplayName("Cache: two requests within TTL → 1 advisor call")
    void cacheWithinTtl_dedupes() {
        QuantAiAdvisorService service = build(6, 30);
        QuantSnapshot snap = snapshot(Instrument.MNQ, 7);

        AiAdvice first = service.requestAdvice(Instrument.MNQ, snap, samplePattern());
        AiAdvice second = service.requestAdvice(Instrument.MNQ, snap, samplePattern());

        assertThat(first.verdict()).isEqualTo(AiAdvice.Verdict.TRADE);
        assertThat(second).isSameAs(first);
        assertThat(advisor.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Advisor receives the same-session memory and the embedded similar situations")
    void contextWiring_passesAllInputs() {
        QuantAiAdvisorService service = build(6, 30);
        sessionMemory.recordScan(Instrument.MNQ, OrderFlowPattern.DISTRIBUTION_SILENCIEUSE);
        memory.records.add(new MemoryRecord(1L, Instant.now(), Instrument.MNQ, 7,
            OrderFlowPattern.DISTRIBUTION_SILENCIEUSE, "WIN", 60.0, "n=1", "txt", new float[]{0.1f}));
        QuantSnapshot snap = snapshot(Instrument.MNQ, 7);

        service.requestAdvice(Instrument.MNQ, snap, samplePattern());

        assertThat(advisor.lastSession).isNotNull();
        assertThat(advisor.lastSession.scansCount()).isEqualTo(1);
        assertThat(advisor.lastSimilar).hasSize(1);
        assertThat(advisor.lastContext.instruments()).isEmpty(); // history empty in this test
        assertThat(embedder.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Advisor unavailable → service returns UNAVAILABLE without crashing")
    void advisorUnavailable_failsGracefully() {
        QuantAiAdvisorService service = new QuantAiAdvisorService(
            EmptyProvider.of(), EmptyProvider.of(), EmptyProvider.of(),
            sessionMemory, historyStore, 6, 30, 5);
        QuantSnapshot snap = snapshot(Instrument.MNQ, 7);

        AiAdvice advice = service.requestAdvice(Instrument.MNQ, snap, samplePattern());

        assertThat(advice.verdict()).isEqualTo(AiAdvice.Verdict.UNAVAILABLE);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private QuantAiAdvisorService build(int triggerScore, int cacheSeconds) {
        return new QuantAiAdvisorService(
            FixedProvider.of(advisor),
            FixedProvider.of(memory),
            FixedProvider.of(embedder),
            sessionMemory,
            historyStore,
            triggerScore, cacheSeconds, 5
        );
    }

    private static QuantSnapshot snapshot(Instrument instr, int score) {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        for (Gate g : Gate.values()) gates.put(g, score >= 7 ? GateResult.pass("ok") : GateResult.fail("ko"));
        return new QuantSnapshot(instr, gates, score, 20_000.0, "LIVE_PUSH",
            10.0, ZonedDateTime.now(ZoneId.of("America/New_York")));
    }

    private static PatternAnalysis samplePattern() {
        return new PatternAnalysis(OrderFlowPattern.DISTRIBUTION_SILENCIEUSE, "Distribution silencieuse",
            "test", PatternAnalysis.Confidence.HIGH, PatternAnalysis.Action.TRADE);
    }

    private static class CountingAdvisor implements AdvisorPort {
        final AtomicInteger calls = new AtomicInteger();
        SessionMemory lastSession;
        List<MemoryRecord> lastSimilar;
        MultiInstrumentContext lastContext;

        @Override
        public AiAdvice askAdvice(QuantSnapshot quant, PatternAnalysis pattern, SessionMemory memory,
                                   List<MemoryRecord> similar, MultiInstrumentContext ctx) {
            calls.incrementAndGet();
            lastSession = memory;
            lastSimilar = similar;
            lastContext = ctx;
            return new AiAdvice(AiAdvice.Verdict.TRADE, "test reasoning", "test risk", 0.85,
                "test-model", Instant.now());
        }
    }

    private static class CountingMemory implements QuantMemoryPort {
        final List<MemoryRecord> records = new java.util.ArrayList<>();

        @Override
        public void save(MemoryRecord record) {}

        @Override
        public List<MemoryRecord> findSimilar(Instrument instrument, float[] queryEmbedding, int topK) {
            return records;
        }
    }

    private static class CountingEmbedder implements QuantAiAdvisorService.EmbeddingPort {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            return new float[]{0.1f, 0.2f, 0.3f};
        }
    }

    private static final class FixedProvider<T> implements ObjectProvider<T> {
        private final T value;
        private FixedProvider(T value) { this.value = value; }
        static <T> FixedProvider<T> of(T value) { return new FixedProvider<>(value); }

        @Override public T getObject(Object... args) { return value; }
        @Override public T getObject() { return value; }
        @Override public T getIfAvailable() { return value; }
        @Override public T getIfUnique() { return value; }
    }

    private static final class EmptyProvider<T> implements ObjectProvider<T> {
        static <T> EmptyProvider<T> of() { return new EmptyProvider<>(); }
        @Override public T getObject(Object... args) { return null; }
        @Override public T getObject() { return null; }
        @Override public T getIfAvailable() { return null; }
        @Override public T getIfUnique() { return null; }
    }
}

package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.advisor.AdvisorPort;
import com.riskdesk.domain.quant.advisor.AiAdvice;
import com.riskdesk.domain.quant.advisor.MultiInstrumentContext;
import com.riskdesk.domain.quant.memory.MemoryRecord;
import com.riskdesk.domain.quant.memory.QuantMemoryPort;
import com.riskdesk.domain.quant.memory.SessionMemory;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 2 — AI advisor orchestration.
 *
 * <p>Cheap and rare on purpose: this is only called when the deterministic
 * tier 1 (gates) reaches a meaningful score (≥ 6 by default), so the model
 * runs ~10–20× per day instead of 1×/min. Responses are cached for 30 s per
 * instrument so a manual "Ask AI" double-click does not double-bill.</p>
 *
 * <p>Failure-mode: every external dependency is best-effort. If the advisor
 * adapter, the memory store or the embedding client is unavailable, the
 * service returns {@link AiAdvice#unavailable(String)} — the gate panel
 * keeps working unaffected.</p>
 */
@Service
public class QuantAiAdvisorService {

    private static final Logger log = LoggerFactory.getLogger(QuantAiAdvisorService.class);

    private final ObjectProvider<AdvisorPort> advisorProvider;
    private final ObjectProvider<QuantMemoryPort> memoryProvider;
    private final ObjectProvider<EmbeddingPort> embeddingProvider;
    private final QuantSessionMemoryService sessionMemoryService;
    private final QuantSnapshotHistoryStore historyStore;

    private final int triggerScore;
    private final Duration cacheTtl;
    private final int memoryTopK;

    private final Map<Instrument, CachedAdvice> cache = new EnumMap<>(Instrument.class);

    public QuantAiAdvisorService(ObjectProvider<AdvisorPort> advisorProvider,
                                 ObjectProvider<QuantMemoryPort> memoryProvider,
                                 ObjectProvider<EmbeddingPort> embeddingProvider,
                                 QuantSessionMemoryService sessionMemoryService,
                                 QuantSnapshotHistoryStore historyStore,
                                 @Value("${riskdesk.quant.ai-advice-trigger-score:6}") int triggerScore,
                                 @Value("${riskdesk.quant.ai-advice-cache-seconds:30}") int cacheSeconds,
                                 @Value("${riskdesk.quant.memory-rag-top-k:5}") int memoryTopK) {
        this.advisorProvider = advisorProvider;
        this.memoryProvider = memoryProvider;
        this.embeddingProvider = embeddingProvider;
        this.sessionMemoryService = sessionMemoryService;
        this.historyStore = historyStore;
        this.triggerScore = triggerScore;
        this.cacheTtl = Duration.ofSeconds(Math.max(0, cacheSeconds));
        this.memoryTopK = Math.max(1, memoryTopK);
    }

    /** Trigger score above which auto-advice is allowed. */
    public int getTriggerScore() {
        return triggerScore;
    }

    /**
     * Returns an advisory verdict for {@code snapshot}. Falls back to a cached
     * value within the TTL window. Always returns a valid {@link AiAdvice} —
     * never throws.
     */
    public AiAdvice requestAdvice(Instrument instrument,
                                  QuantSnapshot snapshot,
                                  PatternAnalysis pattern) {
        if (snapshot == null) {
            return AiAdvice.unavailable("no snapshot");
        }
        AdvisorPort advisor = advisorProvider.getIfAvailable();
        if (advisor == null) {
            return AiAdvice.unavailable("advisor adapter not configured");
        }

        synchronized (cache) {
            CachedAdvice cached = cache.get(instrument);
            if (cached != null && !cached.expired(cacheTtl)) {
                return cached.advice;
            }
        }

        SessionMemory session = sessionMemoryService.getCurrentSession(instrument);
        List<MemoryRecord> similar = findSimilarSituations(instrument, snapshot, pattern);
        MultiInstrumentContext multi = buildMultiInstrumentContext(instrument);

        AiAdvice advice;
        try {
            advice = advisor.askAdvice(snapshot, pattern, session, similar, multi);
        } catch (RuntimeException ex) {
            log.warn("quant advisor call failed for {}: {}", instrument, ex.toString());
            advice = AiAdvice.unavailable("advisor error: " + ex.getClass().getSimpleName());
        }
        if (advice == null) advice = AiAdvice.unavailable("advisor returned null");

        synchronized (cache) {
            cache.put(instrument, new CachedAdvice(advice, Instant.now()));
        }
        return advice;
    }

    /** Convenience: only fires when the score reaches the trigger threshold. */
    public AiAdvice requestAdviceIfQualified(Instrument instrument,
                                             QuantSnapshot snapshot,
                                             PatternAnalysis pattern) {
        if (snapshot == null || snapshot.score() < triggerScore) {
            return AiAdvice.unavailable("score below trigger (" + (snapshot == null ? "?" : snapshot.score()) + ")");
        }
        return requestAdvice(instrument, snapshot, pattern);
    }

    private List<MemoryRecord> findSimilarSituations(Instrument instrument,
                                                      QuantSnapshot snapshot,
                                                      PatternAnalysis pattern) {
        QuantMemoryPort memory = memoryProvider.getIfAvailable();
        EmbeddingPort embedding = embeddingProvider.getIfAvailable();
        if (memory == null || embedding == null) return List.of();

        String text = semanticTextFor(instrument, snapshot, pattern);
        try {
            float[] queryEmbedding = embedding.embed(text);
            if (queryEmbedding == null || queryEmbedding.length == 0) return List.of();
            return memory.findSimilar(instrument, queryEmbedding, memoryTopK);
        } catch (RuntimeException ex) {
            log.warn("memory RAG lookup failed for {}: {}", instrument, ex.toString());
            return List.of();
        }
    }

    private MultiInstrumentContext buildMultiInstrumentContext(Instrument focus) {
        List<MultiInstrumentContext.InstrumentSnapshot> entries = new ArrayList<>();
        for (Instrument other : Instrument.exchangeTradedFutures()) {
            QuantSnapshot snap = historyStore.recent(other, Duration.ofMinutes(10)).stream()
                .reduce((first, second) -> second).orElse(null);
            if (snap == null) continue;
            entries.add(new MultiInstrumentContext.InstrumentSnapshot(
                other,
                "",
                null,
                snap.dayMove(),
                snap.price(),
                snap.score()
            ));
        }
        return new MultiInstrumentContext(entries);
    }

    private static String semanticTextFor(Instrument instrument,
                                          QuantSnapshot snapshot,
                                          PatternAnalysis pattern) {
        StringBuilder sb = new StringBuilder();
        sb.append("instrument=").append(instrument.name())
          .append(" score=").append(snapshot.score()).append("/7")
          .append(" price=").append(snapshot.price())
          .append(" dayMove=").append(String.format("%.0f", snapshot.dayMove()));
        if (pattern != null) {
            sb.append(" pattern=").append(pattern.type().name())
              .append(" patternConfidence=").append(pattern.confidence().name());
        }
        snapshot.gates().forEach((gate, result) ->
            sb.append(' ').append(gate.name()).append('=').append(result.ok() ? "OK" : "KO"));
        return sb.toString();
    }

    /**
     * Lightweight embedding port re-declared here to keep the application
     * service free of a hard dependency on a specific Gemini implementation.
     * The infrastructure layer wires this to {@code GeminiEmbeddingClient}.
     */
    public interface EmbeddingPort {
        float[] embed(String text);
    }

    private record CachedAdvice(AiAdvice advice, Instant ts) {
        boolean expired(Duration ttl) {
            return Instant.now().isAfter(ts.plus(ttl));
        }
    }
}

package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.decision.model.TradeDecision;
import com.riskdesk.domain.decision.port.DecisionNarratorPort;
import com.riskdesk.domain.decision.port.NarratorRequest;
import com.riskdesk.domain.decision.port.NarratorResponse;
import com.riskdesk.domain.decision.port.TradeDecisionRepositoryPort;
import com.riskdesk.domain.engine.playbook.agent.AgentAdjustments;
import com.riskdesk.domain.engine.playbook.agent.AgentVerdict;
import com.riskdesk.domain.engine.playbook.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TradeDecisionService}. Uses hand-rolled in-memory fakes for the
 * repository port and the narrator port so the test is fast and independent of Spring.
 */
class TradeDecisionServiceTest {

    private InMemoryRepo repo;
    private RecordingNarrator narrator;
    private TradeDecisionService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRepo();
        narrator = new RecordingNarrator();
        // SimpMessagingTemplate is null — service tolerates this and logs a warning.
        service = new TradeDecisionService(repo, narrator, null, new ObjectMapper());
    }

    @Test
    void record_persistsDecision_withNarrationAndDoneStatus() {
        FinalVerdict verdict = verdict("ELIGIBLE", 0.01,
            List.of(new AgentVerdict("MTF", Confidence.HIGH, Direction.LONG, "h1+h4 bullish", AgentAdjustments.none())));
        narrator.nextResponse = new NarratorResponse("Narratif OK", "gemini-3.1-pro-preview", 320L, true);

        Optional<TradeDecision> saved = service.record(verdict, playbook(Direction.LONG, 6), "MCL", "10m");

        assertTrue(saved.isPresent());
        TradeDecision d = saved.get();
        assertEquals("MCL", d.instrument());
        assertEquals("10m", d.timeframe());
        assertEquals("LONG", d.direction());
        assertEquals("ELIGIBLE", d.eligibility());
        assertEquals(TradeDecision.STATUS_DONE, d.status());
        assertEquals("Narratif OK", d.narrative());
        assertEquals("gemini-3.1-pro-preview", d.narrativeModel());
        assertEquals(320L, d.narrativeLatencyMs());
        assertNotNull(d.id());
        assertEquals(1, d.revision());
        // Two saves: initial (NARRATING) + post-narration (DONE)
        assertEquals(2, repo.saveCount);
    }

    @Test
    void record_fallbackNarration_persistsDecisionWithFallbackText() {
        FinalVerdict verdict = verdict("ELIGIBLE", 0.01, List.of());
        narrator.nextResponse = NarratorResponse.fallback("narrator disabled");

        Optional<TradeDecision> saved = service.record(verdict, playbook(Direction.LONG, 5), "MGC", "1h");

        assertTrue(saved.isPresent());
        TradeDecision d = saved.get();
        // Narrative is still set (to the fallback string) so the UI has something to show
        assertEquals("narrator disabled", d.narrative());
        assertNull(d.narrativeModel());
        // Status is DONE because we successfully persisted; fallback text is not an error
        assertEquals(TradeDecision.STATUS_DONE, d.status());
    }

    @Test
    void record_capturesAgentVerdictsAsJson() {
        FinalVerdict verdict = verdict("ELIGIBLE", 0.005, List.of(
            new AgentVerdict("MTF", Confidence.HIGH, Direction.LONG, "triple confluence", AgentAdjustments.flags(Map.of("align", 3))),
            new AgentVerdict("OrderFlow", Confidence.MEDIUM, Direction.LONG, "CVD rising", AgentAdjustments.none())
        ));
        narrator.nextResponse = new NarratorResponse("ok", "model", 10L, true);

        TradeDecision d = service.record(verdict, playbook(Direction.LONG, 6), "MCL", "10m").orElseThrow();

        String json = d.agentVerdictsJson();
        assertNotNull(json);
        assertTrue(json.contains("MTF"));
        assertTrue(json.contains("OrderFlow"));
        assertTrue(json.contains("triple confluence"));
    }

    @Test
    void record_capturesPlanFromAdjustedPlanWhenPresent() {
        PlaybookEvaluation pb = playbook(Direction.LONG, 6);
        PlaybookPlan adjusted = pb.plan().withAdjustedSize(0.005);
        FinalVerdict verdict = new FinalVerdict(
            "LONG — ELIGIBLE", adjusted, 0.005,
            List.of(), List.of(), "ELIGIBLE"
        );
        narrator.nextResponse = new NarratorResponse("ok", "m", 10L, true);

        TradeDecision d = service.record(verdict, pb, "MCL", "10m").orElseThrow();

        assertEquals(adjusted.entryPrice(), d.entryPrice());
        assertEquals(adjusted.stopLoss(), d.stopLoss());
        assertEquals(adjusted.takeProfit1(), d.takeProfit1());
        assertEquals(adjusted.rrRatio(), d.rrRatio());
    }

    @Test
    void record_returnsEmpty_whenRepoThrows() {
        repo.throwOnSave = true;
        FinalVerdict verdict = verdict("ELIGIBLE", 0.01, List.of());
        narrator.nextResponse = new NarratorResponse("ok", "m", 10L, true);

        Optional<TradeDecision> saved = service.record(verdict, playbook(Direction.LONG, 6), "MCL", "10m");

        assertTrue(saved.isEmpty(), "service must swallow persistence errors");
    }

    @Test
    void recordRevision_incrementsRevisionForSameZoneThread() {
        FinalVerdict v = verdict("ELIGIBLE", 0.01, List.of());
        narrator.nextResponse = new NarratorResponse("ok", "m", 10L, true);
        service.record(v, playbook(Direction.LONG, 6), "MCL", "10m");

        narrator.nextResponse = new NarratorResponse("ok rev 2", "m", 10L, true);
        Optional<TradeDecision> rev2 = service.recordRevision(v, playbook(Direction.LONG, 6), "MCL", "10m");

        assertTrue(rev2.isPresent());
        assertEquals(2, rev2.get().revision());
    }

    @Test
    void recent_respectsLimit() {
        narrator.nextResponse = new NarratorResponse("ok", "m", 10L, true);
        for (int i = 0; i < 5; i++) {
            service.record(verdict("ELIGIBLE", 0.01, List.of()),
                playbook(Direction.LONG, 6), "MCL", "10m");
        }
        assertEquals(3, service.recent(3).size());
        assertEquals(5, service.recent(100).size());
    }

    @Test
    void thread_returnsAllRevisionsOldestFirst() {
        narrator.nextResponse = new NarratorResponse("ok", "m", 10L, true);
        service.record(verdict("ELIGIBLE", 0.01, List.of()),
            playbook(Direction.LONG, 6), "MCL", "10m");
        service.recordRevision(verdict("ELIGIBLE", 0.01, List.of()),
            playbook(Direction.LONG, 6), "MCL", "10m");
        service.recordRevision(verdict("ELIGIBLE", 0.01, List.of()),
            playbook(Direction.LONG, 6), "MCL", "10m");

        List<TradeDecision> thread = service.thread("MCL", "10m", "LONG", "OB BULLISH 91.03-94.71");

        assertEquals(3, thread.size());
        assertEquals(1, thread.get(0).revision());
        assertEquals(3, thread.get(2).revision());
    }

    @Test
    void narrator_receivesAgentLinesInRequest() {
        AgentVerdict mtf = new AgentVerdict("MTF Confluence", Confidence.HIGH,
            Direction.LONG, "h1 bullish + BOS confirmed", AgentAdjustments.none());
        FinalVerdict verdict = verdict("ELIGIBLE", 0.01, List.of(mtf));
        narrator.nextResponse = new NarratorResponse("ok", "m", 10L, true);

        service.record(verdict, playbook(Direction.LONG, 6), "MCL", "10m");

        assertNotNull(narrator.lastRequest);
        assertEquals("MCL", narrator.lastRequest.instrument());
        assertEquals(1, narrator.lastRequest.agentVerdicts().size());
        var line = narrator.lastRequest.agentVerdicts().get(0);
        assertEquals("MTF Confluence", line.agentName());
        assertEquals("HIGH", line.confidence());
        assertEquals("h1 bullish + BOS confirmed", line.reasoning());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private static FinalVerdict verdict(String eligibility, double sizePct, List<AgentVerdict> agents) {
        PlaybookPlan plan = new PlaybookPlan(
            bd("92.87"), bd("90.50"), bd("97.00"), bd("99.49"),
            2.5, sizePct, "below zone", "first opposing OB"
        );
        return new FinalVerdict(
            "LONG — " + eligibility,
            plan, sizePct,
            agents,
            List.of("test warning"),
            eligibility
        );
    }

    private static PlaybookEvaluation playbook(Direction dir, int score) {
        var setup = new SetupCandidate(
            SetupType.ZONE_RETEST, "OB BULLISH 91.03-94.71",
            bd("94.71"), bd("91.03"), bd("92.87"),
            1.0, true, false, true, 2.5, score
        );
        PlaybookPlan plan = new PlaybookPlan(
            bd("92.87"), bd("90.50"), bd("97.00"), bd("99.49"),
            2.5, 0.01, "below zone", "first opposing OB"
        );
        return new PlaybookEvaluation(
            new FilterResult(true, "BULLISH", dir, true, 6, 0, 6, 1.0, true, VaPosition.BELOW_VA, true),
            List.of(setup), setup, plan, List.of(), score,
            dir + " — ZONE RETEST — " + score + "/7", Instant.now()
        );
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    // ── Fake ports ───────────────────────────────────────────────────────

    static class InMemoryRepo implements TradeDecisionRepositoryPort {
        final Map<Long, TradeDecision> store = new LinkedHashMap<>();
        final AtomicLong seq = new AtomicLong();
        int saveCount = 0;
        boolean throwOnSave = false;

        @Override
        public TradeDecision save(TradeDecision d) {
            saveCount++;
            if (throwOnSave) throw new RuntimeException("simulated DB failure");
            Long id = d.id() != null ? d.id() : seq.incrementAndGet();
            TradeDecision stored = d.withId(id);
            store.put(id, stored);
            return stored;
        }

        @Override
        public Optional<TradeDecision> findById(Long id) { return Optional.ofNullable(store.get(id)); }

        @Override
        public List<TradeDecision> findRecent(int limit) {
            List<TradeDecision> all = new ArrayList<>(store.values());
            Collections.reverse(all);
            return all.subList(0, Math.min(limit, all.size()));
        }

        @Override
        public List<TradeDecision> findRecentByInstrument(String instrument, int limit) {
            return findRecent(Integer.MAX_VALUE).stream()
                .filter(d -> instrument.equalsIgnoreCase(d.instrument()))
                .limit(limit)
                .toList();
        }

        @Override
        public List<TradeDecision> findThread(String instrument, String timeframe,
                                               String direction, String zoneName) {
            return store.values().stream()
                .filter(d -> instrument.equals(d.instrument())
                          && timeframe.equals(d.timeframe())
                          && direction.equals(d.direction())
                          && zoneName.equals(d.zoneName()))
                .sorted(Comparator.comparingInt(TradeDecision::revision))
                .toList();
        }
    }

    static class RecordingNarrator implements DecisionNarratorPort {
        NarratorResponse nextResponse;
        NarratorRequest lastRequest;

        @Override
        public NarratorResponse narrate(NarratorRequest request) {
            this.lastRequest = request;
            return nextResponse != null
                ? nextResponse
                : NarratorResponse.fallback("no canned response set");
        }
    }
}

package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.IndicatorAlertSnapshot;
import com.riskdesk.domain.alert.port.AlertStateStore;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for alert evaluator state persistence, EvalKey serialization,
 * and restart recovery behavior.
 */
class IndicatorAlertEvaluatorStateTest {

    private static final Instant CLOSED_CANDLE = Instant.parse("2026-03-28T16:00:00Z");

    // ── EvalKey serialization round-trip ──────────────────────────────────────

    @Test
    void serializeAndParseEvalKey_withoutPrices() {
        var key = IndicatorAlertEvaluator.parseEvalKey("RSI:MCL:1h:");
        assertNotNull(key);
        assertEquals("RSI", key.family());
        assertEquals("MCL", key.instrument());
        assertEquals("1h", key.timeframe());
        assertNull(key.qualifier());
        assertNull(key.high());
        assertNull(key.low());

        String serialized = IndicatorAlertEvaluator.serializeEvalKey(key);
        assertEquals("RSI:MCL:1h:", serialized);
    }

    @Test
    void serializeAndParseEvalKey_withQualifier() {
        var key = IndicatorAlertEvaluator.parseEvalKey("SMC:E6:10m:internal");
        assertNotNull(key);
        assertEquals("SMC", key.family());
        assertEquals("E6", key.instrument());
        assertEquals("10m", key.timeframe());
        assertEquals("internal", key.qualifier());
    }

    @Test
    void serializeAndParseEvalKey_withPrices() {
        var key = IndicatorAlertEvaluator.parseEvalKey("OB:MCL:10m::62.50:62.00");
        assertNotNull(key);
        assertEquals("OB", key.family());
        assertNull(key.qualifier());
        assertEquals(new BigDecimal("62.50"), key.high());
        assertEquals(new BigDecimal("62.00"), key.low());

        String serialized = IndicatorAlertEvaluator.serializeEvalKey(key);
        assertEquals("OB:MCL:10m::62.50:62.00", serialized);
    }

    @Test
    void parseEvalKey_nullOrBlank_returnsNull() {
        assertNull(IndicatorAlertEvaluator.parseEvalKey(null));
        assertNull(IndicatorAlertEvaluator.parseEvalKey(""));
        assertNull(IndicatorAlertEvaluator.parseEvalKey("   "));
    }

    @Test
    void parseEvalKey_tooFewParts_returnsNull() {
        assertNull(IndicatorAlertEvaluator.parseEvalKey("RSI:MCL"));
        assertNull(IndicatorAlertEvaluator.parseEvalKey("RSI"));
    }

    @Test
    void parseEvalKey_malformedPrices_stillParsesWithoutPrices() {
        var key = IndicatorAlertEvaluator.parseEvalKey("OB:MCL:10m::notANumber:alsoNot");
        assertNotNull(key);
        assertEquals("OB", key.family());
        assertNull(key.high());
        assertNull(key.low());
    }

    @Test
    void serializeEvalKey_truncatesAt255() {
        // qualifier longer than 255 chars total
        String longQualifier = "x".repeat(300);
        var key = IndicatorAlertEvaluator.parseEvalKey("RSI:MCL:1h:" + longQualifier);
        assertNotNull(key);
        String serialized = IndicatorAlertEvaluator.serializeEvalKey(key);
        assertTrue(serialized.length() <= 255);
    }

    // ── State recovery on construction ───────────────────────────────────────

    @Test
    void constructor_recoversStateFromStore_suppressesFalseTransition() {
        // Simulate: RSI was OVERSOLD before restart
        InMemoryAlertStateStore store = new InMemoryAlertStateStore();
        store.save("RSI:E6:1h:", "OVERSOLD");

        IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator(store);

        // First evaluation post-restart with same OVERSOLD → should NOT fire (not a transition)
        IndicatorAlertSnapshot snap = makeSnapshot(null, new BigDecimal("25.3"), "OVERSOLD", null, null);
        List<Alert> alerts = evaluator.evaluate(Instrument.E6, "1h", snap);

        assertTrue(alerts.isEmpty(), "Recovered state should suppress re-firing the same signal after restart");
    }

    @Test
    void constructor_recoveredState_allowsNewTransition() {
        // Simulate: RSI was OVERSOLD before restart
        InMemoryAlertStateStore store = new InMemoryAlertStateStore();
        store.save("RSI:E6:1h:", "OVERSOLD");

        IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator(store);

        // RSI now OVERBOUGHT → different signal → should fire
        IndicatorAlertSnapshot snap = makeSnapshot(null, new BigDecimal("78.5"), "OVERBOUGHT", null, null);
        List<Alert> alerts = evaluator.evaluate(Instrument.E6, "1h", snap);

        assertFalse(alerts.isEmpty(), "A new signal different from recovered state should fire");
        assertTrue(alerts.stream().anyMatch(a -> a.message().contains("overbought")));
    }

    // ── State persistence on transitions ─────────────────────────────────────

    @Test
    void transition_persistsStateToStore() {
        InMemoryAlertStateStore store = new InMemoryAlertStateStore();
        IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator(store);

        IndicatorAlertSnapshot snap = makeSnapshot(null, new BigDecimal("25.3"), "OVERSOLD", null, null);
        evaluator.evaluate(Instrument.MCL, "1h", snap);

        // Store should now contain the RSI state
        assertFalse(store.getAll().isEmpty(), "State should be persisted after a transition");
        assertTrue(store.getAll().values().stream().anyMatch(v -> v.equals("OVERSOLD")));
    }

    @Test
    void nullSignal_removesStateFromStore() {
        InMemoryAlertStateStore store = new InMemoryAlertStateStore();
        store.save("RSI:MCL:1h:", "OVERSOLD");

        IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator(store);

        // RSI back to normal (null signal)
        IndicatorAlertSnapshot snap = makeSnapshot(null, new BigDecimal("50.0"), null, null, null);
        evaluator.evaluate(Instrument.MCL, "1h", snap);

        // The RSI key should be removed from the store
        assertFalse(store.getAll().containsKey("RSI:MCL:1h:"),
                "Null signal should remove state from store");
    }

    // ── Semantic dedup window ────────────────────────────────────────────────

    @Test
    void semanticDedupWindowSeconds_knownTimeframes() {
        assertEquals(300L, com.riskdesk.application.service.MentorSignalReviewService.semanticDedupWindowSeconds("1m"));
        assertEquals(1500L, com.riskdesk.application.service.MentorSignalReviewService.semanticDedupWindowSeconds("5m"));
        assertEquals(4500L, com.riskdesk.application.service.MentorSignalReviewService.semanticDedupWindowSeconds("15m"));
        assertEquals(18000L, com.riskdesk.application.service.MentorSignalReviewService.semanticDedupWindowSeconds("1h"));
        assertEquals(72000L, com.riskdesk.application.service.MentorSignalReviewService.semanticDedupWindowSeconds("4h"));
        assertEquals(432000L, com.riskdesk.application.service.MentorSignalReviewService.semanticDedupWindowSeconds("1d"));
    }

    @Test
    void semanticDedupWindowSeconds_unknownTimeframe_defaultsTo1h() {
        assertEquals(18000L, com.riskdesk.application.service.MentorSignalReviewService.semanticDedupWindowSeconds("unknown"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static IndicatorAlertSnapshot makeSnapshot(
            String emaCrossover,
            BigDecimal rsi, String rsiSignal,
            String macdCrossover,
            String lastBreakType) {
        return new IndicatorAlertSnapshot(
                emaCrossover,
                rsi, rsiSignal,
                macdCrossover,
                lastBreakType,
                lastBreakType,
                null, null, null, null, null,
                Collections.emptyList(), Collections.emptyList(),
                CLOSED_CANDLE,
                null, null, null, null, Collections.emptyList(), Collections.emptyList(),
                null, null, null, null, null, null, null, null
        );
    }

    /** Simple in-memory implementation of AlertStateStore for testing. */
    private static class InMemoryAlertStateStore implements AlertStateStore {
        private final Map<String, String> state = new ConcurrentHashMap<>();

        @Override
        public Map<String, String> loadRecent() {
            return new HashMap<>(state);
        }

        @Override
        public void save(String evalKey, String signal) {
            state.put(evalKey, signal);
        }

        @Override
        public void remove(String evalKey) {
            state.remove(evalKey);
        }

        Map<String, String> getAll() {
            return state;
        }
    }
}

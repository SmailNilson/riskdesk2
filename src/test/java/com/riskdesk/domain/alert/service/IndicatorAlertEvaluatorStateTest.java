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

    // ── EvalKey serialization round-trip (via string-based public API) ────────

    @Test
    void serializeParseRoundTrip_withoutPrices() {
        String input = "RSI:MCL:1h:";
        var key = IndicatorAlertEvaluator.parseEvalKey(input);
        assertNotNull(key);
        String serialized = IndicatorAlertEvaluator.serializeEvalKey(key);
        assertEquals(input, serialized);
    }

    @Test
    void serializeParseRoundTrip_withQualifier() {
        String input = "SMC:E6:10m:internal";
        var key = IndicatorAlertEvaluator.parseEvalKey(input);
        assertNotNull(key);
        String serialized = IndicatorAlertEvaluator.serializeEvalKey(key);
        assertEquals(input, serialized);
    }

    @Test
    void serializeParseRoundTrip_withPrices() {
        String input = "OB:MCL:10m::62.50:62.00";
        var key = IndicatorAlertEvaluator.parseEvalKey(input);
        assertNotNull(key);
        String serialized = IndicatorAlertEvaluator.serializeEvalKey(key);
        assertEquals(input, serialized);
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
    void parseEvalKey_malformedPrices_stillParses() {
        var key = IndicatorAlertEvaluator.parseEvalKey("OB:MCL:10m::notANumber:alsoNot");
        assertNotNull(key, "Should parse even with malformed prices");
        // Round-trip should still work (prices dropped)
        String serialized = IndicatorAlertEvaluator.serializeEvalKey(key);
        assertEquals("OB:MCL:10m:", serialized);
    }

    @Test
    void serializeEvalKey_truncatesAt255() {
        String longQualifier = "x".repeat(300);
        var key = IndicatorAlertEvaluator.parseEvalKey("RSI:MCL:1h:" + longQualifier);
        assertNotNull(key);
        String serialized = IndicatorAlertEvaluator.serializeEvalKey(key);
        assertTrue(serialized.length() <= 255);
    }

    // ── State recovery on construction ───────────────────────────────────────

    @Test
    void constructor_recoversStateFromStore_suppressesFalseTransition() {
        InMemoryAlertStateStore store = new InMemoryAlertStateStore();
        // Key format must match what the evaluator generates internally (lowercase family)
        store.save("rsi:E6:1h:", "OVERSOLD");

        IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator(store);

        IndicatorAlertSnapshot snap = makeSnapshot(null, new BigDecimal("25.3"), "OVERSOLD", null, null);
        List<Alert> alerts = evaluator.evaluate(Instrument.E6, "1h", snap);

        assertTrue(alerts.isEmpty(), "Recovered state should suppress re-firing the same signal after restart");
    }

    @Test
    void constructor_recoveredState_allowsNewTransition() {
        InMemoryAlertStateStore store = new InMemoryAlertStateStore();
        store.save("rsi:E6:1h:", "OVERSOLD");

        IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator(store);

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

        assertFalse(store.getAll().isEmpty(), "State should be persisted after a transition");
        assertTrue(store.getAll().values().stream().anyMatch(v -> v.equals("OVERSOLD")));
    }

    @Test
    void nullSignal_removesStateFromStore() {
        InMemoryAlertStateStore store = new InMemoryAlertStateStore();
        store.save("rsi:MCL:1h:", "OVERSOLD");

        IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator(store);

        IndicatorAlertSnapshot snap = makeSnapshot(null, new BigDecimal("50.0"), null, null, null);
        evaluator.evaluate(Instrument.MCL, "1h", snap);

        assertFalse(store.getAll().containsKey("rsi:MCL:1h:"),
                "Null signal should remove state from store");
    }

    @Test
    void storeFailure_doesNotCrashEvaluation() {
        AlertStateStore failingStore = new AlertStateStore() {
            @Override public Map<String, String> loadRecent() { return Map.of(); }
            @Override public void save(String evalKey, String signal) { throw new RuntimeException("DB down"); }
            @Override public void remove(String evalKey) { throw new RuntimeException("DB down"); }
        };

        IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator(failingStore);
        IndicatorAlertSnapshot snap = makeSnapshot(null, new BigDecimal("25.3"), "OVERSOLD", null, null);

        // Should not throw even if store fails
        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "1h", snap);
        assertFalse(alerts.isEmpty(), "Alerts should still fire even if persistence fails");
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
                null, null, null, null, null, null, null, null,
                null, null
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

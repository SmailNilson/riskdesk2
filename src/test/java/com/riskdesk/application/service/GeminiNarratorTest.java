package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.decision.port.NarratorRequest;
import com.riskdesk.domain.decision.port.NarratorResponse;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GeminiNarrator}. Only the fallback paths are covered here —
 * the happy-path requires a live Gemini endpoint and is covered by integration smoke
 * tests when the {@code GEMINI_API_KEY} is set in the environment.
 *
 * <p>The key invariant we verify is: <b>the narrator never throws</b>. Any failure path
 * returns a {@link NarratorResponse#available() unavailable} response with a templated
 * fallback string so the caller can always persist the decision.
 */
class GeminiNarratorTest {

    @Test
    void narrate_returnsFallback_whenDisabled() {
        MentorProperties props = new MentorProperties();
        props.setEnabled(false);
        props.setApiKey("whatever");
        props.setModel("gemini-3.1-pro-preview");
        props.setEndpoint("https://generativelanguage.googleapis.com");
        props.setTimeoutMs(30_000);

        GeminiNarrator narrator = new GeminiNarrator(props, new ObjectMapper());

        NarratorResponse response = narrator.narrate(sampleRequest("MCL", "LONG", "ELIGIBLE"));

        assertFalse(response.available());
        assertNull(response.model());
        assertEquals(0L, response.latencyMs());
        assertTrue(response.narrative().contains("narrator disabled")
                || response.narrative().contains("MCL"),
            "fallback text should mention the reason or the instrument");
    }

    @Test
    void narrate_returnsFallback_whenApiKeyMissing() {
        MentorProperties props = new MentorProperties();
        props.setEnabled(true);
        props.setApiKey(""); // blank
        props.setModel("gemini-3.1-pro-preview");
        props.setEndpoint("https://generativelanguage.googleapis.com");
        props.setTimeoutMs(30_000);

        GeminiNarrator narrator = new GeminiNarrator(props, new ObjectMapper());

        NarratorResponse response = narrator.narrate(sampleRequest("MGC", "SHORT", "INELIGIBLE"));

        assertFalse(response.available());
        assertTrue(response.narrative().toLowerCase().contains("api key")
                || response.narrative().contains("MGC"),
            "fallback text should flag missing API key");
    }

    @Test
    void narrate_fallback_includesInstrumentAndEligibility() {
        MentorProperties props = new MentorProperties();
        props.setEnabled(false); // triggers fallback
        props.setApiKey("x");
        props.setModel("m");
        props.setEndpoint("https://test");
        props.setTimeoutMs(1_000);

        GeminiNarrator narrator = new GeminiNarrator(props, new ObjectMapper());

        NarratorResponse r1 = narrator.narrate(sampleRequest("MCL", "LONG", "BLOCKED"));
        NarratorResponse r2 = narrator.narrate(sampleRequest("E6", "SHORT", "ELIGIBLE"));

        assertTrue(r1.narrative().contains("MCL"));
        assertTrue(r1.narrative().contains("BLOCKED"));
        assertTrue(r2.narrative().contains("E6"));
        assertTrue(r2.narrative().contains("ELIGIBLE"));
    }

    @Test
    void narrate_fallback_neverThrowsOnNullFields() {
        MentorProperties props = new MentorProperties();
        props.setEnabled(false);
        props.setApiKey("x");
        props.setModel("m");
        props.setEndpoint("https://test");
        props.setTimeoutMs(1_000);

        GeminiNarrator narrator = new GeminiNarrator(props, new ObjectMapper());

        NarratorRequest partial = new NarratorRequest(
            "MCL", "10m", null, null, null,
            null, 0.0,
            null, null, null, null, null,
            null, null,
            "fr"
        );

        assertDoesNotThrow(() -> narrator.narrate(partial));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private static NarratorRequest sampleRequest(String instrument, String direction, String eligibility) {
        return new NarratorRequest(
            instrument, "10m", direction,
            "ZONE_RETEST", "OB BULLISH 91.03-94.71",
            eligibility, 0.01,
            new BigDecimal("92.87"), new BigDecimal("90.50"),
            new BigDecimal("97.00"), new BigDecimal("99.49"),
            2.5,
            List.of(new NarratorRequest.AgentVerdictLine("MTF", "HIGH", "h1 bullish")),
            List.of("R:R tight"),
            "fr"
        );
    }
}

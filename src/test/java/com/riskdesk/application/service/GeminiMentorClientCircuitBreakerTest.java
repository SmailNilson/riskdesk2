package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link GeminiMentorClient} short-circuits fast when its
 * {@link GeminiCircuitBreaker} is OPEN. Without this wiring, a real Gemini
 * outage makes every alert-review pay the full timeoutMs × MAX_ATTEMPTS
 * retry budget and the 4-thread mentorExecutor saturates within minutes.
 */
class GeminiMentorClientCircuitBreakerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void analyze_failsFastWhenBreakerAlreadyOpen() {
        MentorProperties props = enabledProps();
        GeminiCircuitBreaker openBreaker = new GeminiCircuitBreaker(1, Duration.ofSeconds(30));
        // Trip the breaker BEFORE any call — one failure with threshold=1 opens it.
        openBreaker.recordFailure();
        assertThat(openBreaker.currentState()).isEqualTo(GeminiCircuitBreaker.State.OPEN);

        GeminiMentorClient client = new GeminiMentorClient(props, objectMapper, openBreaker);

        assertThatThrownBy(() -> client.analyze(objectMapper.createObjectNode(), List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("circuit breaker OPEN");
    }

    @Test
    void circuitState_exposesCurrentState() {
        MentorProperties props = enabledProps();
        GeminiCircuitBreaker breaker = new GeminiCircuitBreaker(3, Duration.ofSeconds(30));
        GeminiMentorClient client = new GeminiMentorClient(props, objectMapper, breaker);

        assertThat(client.circuitState()).isEqualTo(GeminiCircuitBreaker.State.CLOSED);
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(client.circuitState()).isEqualTo(GeminiCircuitBreaker.State.OPEN);
    }

    private MentorProperties enabledProps() {
        MentorProperties props = new MentorProperties();
        props.setEnabled(true);
        props.setApiKey("test-key");
        // Minimal endpoint — the breaker check happens BEFORE any HTTP call.
        props.setEndpoint("https://example.invalid");
        props.setModel("gemini-test");
        return props;
    }
}

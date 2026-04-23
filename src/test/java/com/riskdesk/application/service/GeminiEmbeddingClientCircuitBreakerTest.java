package com.riskdesk.application.service;

import com.riskdesk.infrastructure.config.MentorProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link GeminiEmbeddingClient} short-circuits fast when its
 * {@link GeminiCircuitBreaker} is OPEN. {@code MentorMemoryService} already
 * treats any {@link IllegalStateException} from embeddings as a silent skip,
 * so failing fast here preserves mentor-flow latency during a Gemini outage.
 */
class GeminiEmbeddingClientCircuitBreakerTest {

    @Test
    void embed_failsFastWhenBreakerAlreadyOpen() {
        MentorProperties props = enabledProps();
        GeminiCircuitBreaker openBreaker = new GeminiCircuitBreaker(1, Duration.ofSeconds(30));
        openBreaker.recordFailure();
        assertThat(openBreaker.currentState()).isEqualTo(GeminiCircuitBreaker.State.OPEN);

        GeminiEmbeddingClient client = new GeminiEmbeddingClient(props, openBreaker);

        assertThatThrownBy(() -> client.embed("semantic text here"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("circuit breaker OPEN");
    }

    @Test
    void circuitState_exposesCurrentState() {
        MentorProperties props = enabledProps();
        GeminiCircuitBreaker breaker = new GeminiCircuitBreaker(2, Duration.ofSeconds(30));
        GeminiEmbeddingClient client = new GeminiEmbeddingClient(props, breaker);

        assertThat(client.circuitState()).isEqualTo(GeminiCircuitBreaker.State.CLOSED);
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(client.circuitState()).isEqualTo(GeminiCircuitBreaker.State.OPEN);
    }

    private MentorProperties enabledProps() {
        MentorProperties props = new MentorProperties();
        props.setEnabled(true);
        props.setEmbeddingsEnabled(true);
        props.setApiKey("test-key");
        props.setEndpoint("https://example.invalid");
        props.setEmbeddingsModel("gemini-embedding-test");
        props.setEmbeddingDimensions(768);
        return props;
    }
}

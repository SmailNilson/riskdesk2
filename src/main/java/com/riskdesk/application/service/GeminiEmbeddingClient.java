package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class GeminiEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClient.class);

    private final MentorProperties properties;
    private final RestTemplate restTemplate;
    /**
     * Fail-fast breaker. Embeddings calls run on the mentor-analysis executor
     * when `indexAudit` is invoked synchronously; without a breaker, an outage
     * burns the full read timeout per call and can starve the pool. Same
     * calibration as the agent adapter and mentor client (3 failures / 30 s).
     */
    private final GeminiCircuitBreaker breaker;

    @Autowired
    public GeminiEmbeddingClient(MentorProperties properties) {
        this.properties = properties;
        this.restTemplate = buildRestTemplate(properties);
        this.breaker = new GeminiCircuitBreaker(3, Duration.ofSeconds(30));
    }

    /** Package-private constructor for tests that need to inject a pre-built breaker. */
    GeminiEmbeddingClient(MentorProperties properties, GeminiCircuitBreaker breaker) {
        this.properties = properties;
        this.restTemplate = buildRestTemplate(properties);
        this.breaker = breaker;
    }

    /** Exposed for actuator-style introspection. */
    public GeminiCircuitBreaker.State circuitState() {
        return breaker.currentState();
    }

    public List<Double> embed(String text) {
        return embed(text, properties.getEmbeddingsModel());
    }

    public List<Double> embed(String text, String modelName) {
        if (!properties.isEnabled() || !properties.isEmbeddingsEnabled()) {
            throw new IllegalStateException("Mentor embeddings are disabled.");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured on the backend.");
        }
        if (!breaker.allowRequest()) {
            // Fail fast during a Gemini outage — callers (MentorMemoryService)
            // already treat IllegalStateException as a silent skip.
            throw new IllegalStateException("Gemini embedding circuit breaker OPEN — failing fast");
        }

        try {
            Map<String, Object> body = Map.of(
                "model", "models/" + modelName,
                "content", Map.of(
                    "parts", List.of(Map.of("text", text))
                ),
                "outputDimensionality", properties.getEmbeddingDimensions()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", properties.getApiKey());

            String endpoint = properties.getEndpoint()
                + "/v1beta/models/"
                + modelName
                + ":embedContent";

            JsonNode root = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class
            ).getBody();

            JsonNode values = root.path("embedding").path("values");
            if (!values.isArray() || values.isEmpty()) {
                // The outer catch records the breaker failure — avoid double-counting.
                throw new IllegalStateException("Gemini embedding response was empty.");
            }

            List<Double> embedding = new ArrayList<>(values.size());
            for (JsonNode value : values) {
                embedding.add(value.asDouble());
            }
            breaker.recordSuccess();
            return embedding;
        } catch (Exception e) {
            breaker.recordFailure();
            log.error("Gemini embedding call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Gemini embedding call failed: " + e.getMessage(), e);
        }
    }

    private RestTemplate buildRestTemplate(MentorProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMs());
        factory.setReadTimeout(properties.getTimeoutMs());
        return new RestTemplate(factory);
    }
}

package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.engine.playbook.agent.port.AgentAiRequest;
import com.riskdesk.domain.engine.playbook.agent.port.AgentAiResponse;
import com.riskdesk.domain.engine.playbook.agent.port.GeminiAgentPort;
import com.riskdesk.infrastructure.config.VertexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

/**
 * Vertex AI–backed implementation of {@link GeminiAgentPort}.
 *
 * <p>Uses the project-scoped Vertex AI API key (higher quota than the personal
 * Google AI Studio key used by {@link GeminiMentorClient}). Targets
 * {@code gemini-3.1-flash-lite-preview} — sufficient for short agent calls (400–800 tokens).
 *
 * <p>Marked {@code @Primary} so Spring injects this adapter into the three AI trading
 * agents (MTF-Confluence, Order-Flow, Zone-Quality). When Vertex is disabled or
 * {@code VERTEX_API_KEY} is absent, calls are <em>delegated</em> to {@link GeminiAgentAdapter}
 * so environments with only the Gemini key still get AI responses.
 *
 * <p>URL shape: {@code generativelanguage.googleapis.com} uses the Gemini Developer API path
 * ({@code /v1beta/models/…:generateContent}). A real Vertex AI host
 * ({@code aiplatform.googleapis.com}) requires a different path and OAuth2 — detected
 * automatically via {@link #buildEndpointUrl()}.
 */
@Primary
@Service
public class VertexAiAgentAdapter implements GeminiAgentPort {

    private static final Logger log = LoggerFactory.getLogger(VertexAiAgentAdapter.class);
    private static final String VERTEX_AI_HOST = "aiplatform.googleapis.com";

    private final VertexProperties properties;
    private final GeminiAgentAdapter geminiAdapter;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final GeminiCircuitBreaker breaker;

    public VertexAiAgentAdapter(VertexProperties properties,
                                GeminiAgentAdapter geminiAdapter,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.geminiAdapter = geminiAdapter;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate();
        this.breaker = new GeminiCircuitBreaker(3, Duration.ofSeconds(30));
    }

    @Override
    public AgentAiResponse analyze(AgentAiRequest request) {
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.debug("Vertex not configured — delegating agent '{}' to Gemini adapter", request.agentName());
            return geminiAdapter.analyze(request);
        }
        if (!breaker.allowRequest()) {
            return AgentAiResponse.fallback("Vertex AI circuit breaker OPEN");
        }

        try {
            String payloadJson = objectMapper.writeValueAsString(request.payload());

            Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                    "parts", List.of(Map.of("text", request.systemPrompt()))
                ),
                "contents", List.of(
                    Map.of("role", "user",
                           "parts", List.of(Map.of("text",
                               "Analyse ce payload JSON et retourne la réponse au format imposé.\n\n" + payloadJson)))
                ),
                // thinkingBudget=0: flash is fast enough without reasoning traces,
                // and thinking tokens would compete with the 400-800 token output budget.
                "generationConfig", Map.of(
                    "temperature", 0.1,
                    "maxOutputTokens", request.maxOutputTokens(),
                    "thinkingConfig", Map.of("thinkingBudget", 0),
                    "responseMimeType", "application/json",
                    "responseSchema", buildAgentResponseSchema()
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", properties.getApiKey());
            HttpEntity<Map<String, Object>> http = new HttpEntity<>(body, headers);

            String endpoint = buildEndpointUrl();

            long start = System.currentTimeMillis();
            JsonNode root = restTemplate
                .exchange(endpoint, HttpMethod.POST, http, JsonNode.class)
                .getBody();
            long latencyMs = System.currentTimeMillis() - start;

            if (root == null) {
                breaker.recordFailure();
                return AgentAiResponse.fallback("Empty Vertex response");
            }

            logUsage(request.agentName(), root, latencyMs);

            String text = extractText(root);
            if (text == null || text.isBlank()) {
                breaker.recordFailure();
                return AgentAiResponse.fallback("Empty text in Vertex response");
            }

            AgentAiResponse parsed = parseAgentResponse(text);
            if (parsed.aiAvailable()) {
                breaker.recordSuccess();
            } else {
                breaker.recordFailure();
            }
            return parsed;

        } catch (Exception e) {
            breaker.recordFailure();
            log.warn("Agent '{}' Vertex call failed: {}", request.agentName(), e.getMessage());
            return AgentAiResponse.fallback("Vertex error: " + e.getClass().getSimpleName());
        }
    }

    public GeminiCircuitBreaker.State circuitState() {
        return breaker.currentState();
    }

    // Gemini Developer API and true Vertex AI use different URL shapes.
    // With VERTEX_API_KEY the standard generativelanguage.googleapis.com host works fine.
    // A real aiplatform.googleapis.com host needs OAuth2 + project/location path — warn if misconfigured.
    private String buildEndpointUrl() {
        String base = properties.getEndpoint();
        if (base.contains(VERTEX_AI_HOST)) {
            log.warn("Vertex AI host detected ({}). This requires OAuth2 auth and a project/location " +
                "path not yet supported. Switch endpoint to generativelanguage.googleapis.com " +
                "and use a Vertex API key instead.", base);
        }
        return base + "/v1beta/models/" + properties.getModel() + ":generateContent";
    }

    private static Map<String, Object> buildAgentResponseSchema() {
        Map<String, Object> flagsProps = new LinkedHashMap<>();
        flagsProps.put("size_pct", Map.of("type", "NUMBER", "description", "Optional risk cap, 0..0.01"));
        flagsProps.put("blocked", Map.of("type", "BOOLEAN"));
        flagsProps.put("counter_trend", Map.of("type", "BOOLEAN"));
        flagsProps.put("htf_fake_break", Map.of("type", "BOOLEAN"));
        flagsProps.put("mtf_alignment", Map.of("type", "INTEGER", "description", "0..3 HTF aligned"));
        flagsProps.put("no_data", Map.of("type", "BOOLEAN"));
        flagsProps.put("data_quality", Map.of("type", "STRING", "enum", List.of("real_ticks", "degraded")));
        flagsProps.put("flow_supports", Map.of("type", "BOOLEAN"));
        flagsProps.put("wall_blocking_tp", Map.of("type", "BOOLEAN"));
        flagsProps.put("weak_zone", Map.of("type", "BOOLEAN"));
        flagsProps.put("fake_break", Map.of("type", "BOOLEAN"));
        flagsProps.put("trap_risk", Map.of("type", "BOOLEAN"));
        flagsProps.put("no_of_enrichment", Map.of("type", "BOOLEAN"));

        Map<String, Object> flagsSchema = Map.of(
            "type", "OBJECT",
            "description", "Structured signals — agent fills only the subset relevant to its domain",
            "properties", flagsProps
        );

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("confidence", Map.of("type", "STRING", "enum", List.of("HIGH", "MEDIUM", "LOW")));
        props.put("reasoning", Map.of("type", "STRING", "description", "Max 250 chars, factual, cite key signals"));
        props.put("flags", flagsSchema);

        return Map.of(
            "type", "OBJECT",
            "properties", props,
            "required", List.of("confidence", "reasoning")
        );
    }

    private AgentAiResponse parseAgentResponse(String text) {
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("```")) {
                trimmed = trimmed.replaceFirst("^```json\\s*", "")
                                 .replaceFirst("^```\\s*", "")
                                 .replaceFirst("\\s*```$", "")
                                 .trim();
            }
            JsonNode node = objectMapper.readTree(trimmed);

            String confidence = node.path("confidence").asText("MEDIUM");
            String reasoning = node.path("reasoning").asText("");

            Map<String, Object> flags = new LinkedHashMap<>();
            JsonNode flagsNode = node.path("flags");
            if (flagsNode.isObject()) {
                flagsNode.fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue();
                    if (v.isBoolean()) flags.put(e.getKey(), v.asBoolean());
                    else if (v.isNumber()) flags.put(e.getKey(), v.numberValue());
                    else if (v.isTextual()) flags.put(e.getKey(), v.asText());
                    else flags.put(e.getKey(), v.toString());
                });
            }

            return new AgentAiResponse(confidence, reasoning, flags, true);
        } catch (Exception e) {
            log.warn("Failed to parse Vertex agent response: {} — raw: {}", e.getMessage(), text);
            return AgentAiResponse.fallback("Parse error: " + e.getClass().getSimpleName());
        }
    }

    private String extractText(JsonNode root) {
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) return null;
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) return null;
        return parts.get(0).path("text").asText(null);
    }

    private void logUsage(String agentName, JsonNode root, long latencyMs) {
        try {
            JsonNode usage = root.path("usageMetadata");
            int in = usage.path("promptTokenCount").asInt(0);
            int out = usage.path("candidatesTokenCount").asInt(0);
            log.info("Agent '{}' Vertex — latency: {}ms | tokens in: {} out: {}",
                agentName, latencyMs, in, out);
        } catch (Exception ignored) {}
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMs());
        factory.setReadTimeout(properties.getTimeoutMs());
        return new RestTemplate(factory);
    }
}

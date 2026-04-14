package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.engine.playbook.agent.port.AgentAiRequest;
import com.riskdesk.domain.engine.playbook.agent.port.AgentAiResponse;
import com.riskdesk.domain.engine.playbook.agent.port.GeminiAgentPort;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Application-layer adapter implementing {@link GeminiAgentPort}.
 *
 * <p>Wraps Gemini's REST endpoint with a compact schema (confidence enum + reasoning
 * + free-form flags). Reuses {@link MentorProperties} for API key / model / endpoint
 * so infrastructure config stays in one place.
 *
 * <p>All failures (timeout, API disabled, empty body, parse error) return
 * {@link AgentAiResponse#fallback(String)} — the domain agents use a deterministic
 * rule fallback when {@code aiAvailable=false}.
 *
 * <p>Keeps {@code maxOutputTokens} tight (400–800) so agent calls cost ~20× less
 * than full Mentor reviews (~$0.002 vs $0.04).
 */
@Service
public class GeminiAgentAdapter implements GeminiAgentPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiAgentAdapter.class);

    private final MentorProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public GeminiAgentAdapter(MentorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate(properties);
    }

    @Override
    public AgentAiResponse analyze(AgentAiRequest request) {
        if (!properties.isEnabled()) {
            return AgentAiResponse.fallback("Gemini disabled");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return AgentAiResponse.fallback("GEMINI_API_KEY missing");
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
                "generationConfig", Map.of(
                    "temperature", 0.1,
                    "maxOutputTokens", request.maxOutputTokens(),
                    "responseMimeType", "application/json",
                    "responseSchema", buildAgentResponseSchema()
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", properties.getApiKey());
            HttpEntity<Map<String, Object>> http = new HttpEntity<>(body, headers);

            String endpoint = properties.getEndpoint()
                + "/v1beta/models/"
                + properties.getModel()
                + ":generateContent";

            long start = System.currentTimeMillis();
            JsonNode root = restTemplate
                .exchange(endpoint, HttpMethod.POST, http, JsonNode.class)
                .getBody();
            long latencyMs = System.currentTimeMillis() - start;

            if (root == null) {
                return AgentAiResponse.fallback("Empty Gemini body");
            }

            logUsage(request.agentName(), root, latencyMs);

            String text = extractText(root);
            if (text == null || text.isBlank()) {
                return AgentAiResponse.fallback("Empty text in Gemini response");
            }

            return parseAgentResponse(text);

        } catch (Exception e) {
            log.warn("Agent '{}' Gemini call failed: {}", request.agentName(), e.getMessage());
            return AgentAiResponse.fallback("Gemini error: " + e.getClass().getSimpleName());
        }
    }

    // ── Response schema (kept small to enforce deterministic output) ────

    private static Map<String, Object> buildAgentResponseSchema() {
        Map<String, Object> flagsSchema = Map.of(
            "type", "OBJECT",
            "description", "Free-form structured signals (e.g. counter_trend=true, size_pct=0.003)",
            "properties", Map.of() // permissive
        );

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("confidence", Map.of(
            "type", "STRING",
            "enum", List.of("HIGH", "MEDIUM", "LOW")
        ));
        props.put("reasoning", Map.of(
            "type", "STRING",
            "description", "Max 250 chars, factual, cite key signals"
        ));
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
            log.warn("Failed to parse agent response JSON: {} — raw: {}", e.getMessage(), text);
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
            log.info("Agent '{}' Gemini — latency: {}ms | tokens in: {} out: {}",
                agentName, latencyMs, in, out);
            if (latencyMs > 15_000) {
                log.warn("Agent '{}' SLOW — {}ms (consider reducing payload or switching to flash)",
                    agentName, latencyMs);
            }
        } catch (Exception ignored) {}
    }

    private RestTemplate buildRestTemplate(MentorProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Agents are fast single-shot calls — cap at half the mentor timeout
        int timeout = Math.max(5_000, properties.getTimeoutMs() / 2);
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }
}

package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.decision.port.DecisionNarratorPort;
import com.riskdesk.domain.decision.port.NarratorRequest;
import com.riskdesk.domain.decision.port.NarratorResponse;
import com.riskdesk.infrastructure.config.VertexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vertex AI–backed implementation of {@link DecisionNarratorPort}.
 *
 * <p>Replaces {@link GeminiNarrator} as {@code @Primary} bean. Uses the project-scoped
 * Vertex AI API key with {@code gemini-2.0-flash} — the narrator outputs ≤ 250 tokens
 * (3–5 lines), so flash is more than sufficient and dramatically reduces latency.
 *
 * <p>Falls back gracefully to a templated one-liner when Vertex is unavailable.
 */
@Primary
@Service
public class VertexAiNarrator implements DecisionNarratorPort {

    private static final Logger log = LoggerFactory.getLogger(VertexAiNarrator.class);
    private static final int MAX_OUTPUT_TOKENS = 250;
    private static final String VERTEX_AI_HOST = "aiplatform.googleapis.com";

    private final VertexProperties properties;
    private final GeminiNarrator geminiNarrator;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public VertexAiNarrator(VertexProperties properties,
                            GeminiNarrator geminiNarrator,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.geminiNarrator = geminiNarrator;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate();
    }

    @Override
    public NarratorResponse narrate(NarratorRequest request) {
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.debug("Vertex not configured — delegating narrator to Gemini");
            return geminiNarrator.narrate(request);
        }

        try {
            Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                    "parts", List.of(Map.of("text", buildSystemPrompt(request.language())))
                ),
                "contents", List.of(
                    Map.of("role", "user",
                           "parts", List.of(Map.of("text", buildUserPrompt(request))))
                ),
                "generationConfig", Map.of(
                    "temperature", 0.1,
                    "maxOutputTokens", MAX_OUTPUT_TOKENS,
                    "thinkingConfig", Map.of("thinkingBudget", 0),
                    "responseMimeType", "application/json",
                    "responseSchema", buildResponseSchema()
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
                return NarratorResponse.fallback(templatedFallback(request, "empty Vertex body"));
            }

            logUsage(root, latencyMs);

            String text = extractText(root);
            if (text == null || text.isBlank()) {
                return NarratorResponse.fallback(templatedFallback(request, "empty text"));
            }

            String narrative = parseNarrative(text);
            if (narrative == null || narrative.isBlank()) {
                return NarratorResponse.fallback(templatedFallback(request, "parse error"));
            }

            return new NarratorResponse(narrative, properties.getModel(), latencyMs, true);

        } catch (Exception e) {
            log.warn("Narrator Vertex call failed: {}", e.getMessage());
            return NarratorResponse.fallback(
                templatedFallback(request, "Vertex error: " + e.getClass().getSimpleName()));
        }
    }

    private static String buildSystemPrompt(String language) {
        boolean fr = language == null || "fr".equalsIgnoreCase(language);
        if (fr) {
            return """
                Tu es un assistant de trading. Tu reçois UN VERDICT DÉJÀ VALIDÉ par quatre
                agents spécialisés (Session, MTF Confluence, Order Flow, Zone Quality).

                Ta mission UNIQUE : produire un paragraphe narratif en français de 3 à 5
                lignes pour l'opérateur humain, en citant 2 ou 3 points clés des verdicts
                des agents.

                Tu ne discutes PAS la décision. Tu ne proposes PAS d'alternatives. Tu
                n'inventes AUCUNE donnée (pas de prix, pas de niveaux, pas de pourcentages
                qui ne sont pas dans l'input).

                Format STRICT : retourne uniquement un JSON {"narrative": "..."}.
                """;
        }
        return """
            You are a trading assistant. You receive an ALREADY-VALIDATED verdict from
            four specialised agents (Session, MTF Confluence, Order Flow, Zone Quality).

            Your SOLE job: produce a 3-5 line narrative paragraph for the human operator,
            citing 2 or 3 key points from the agent verdicts.

            Do NOT re-decide. Do NOT propose alternatives. Do NOT invent data.

            Format: return only a JSON {"narrative": "..."}.
            """;
    }

    private static String buildUserPrompt(NarratorRequest r) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Setup: ").append(r.instrument()).append(' ')
          .append(r.direction()).append(" on ").append(r.timeframe()).append('\n');
        if (r.zoneName() != null) {
            sb.append("Zone: ").append(r.zoneName());
            if (r.setupType() != null) sb.append(" (").append(r.setupType()).append(')');
            sb.append('\n');
        }
        sb.append("Plan: ");
        appendPrice(sb, "Entry", r.entryPrice());
        appendPrice(sb, "SL",    r.stopLoss());
        appendPrice(sb, "TP1",   r.takeProfit1());
        appendPrice(sb, "TP2",   r.takeProfit2());
        if (r.rrRatio() != null) {
            sb.append(String.format(Locale.ROOT, "R:R %.1f:1 · ", r.rrRatio()));
        }
        sb.append('\n');
        sb.append(String.format(Locale.ROOT, "Eligibility: %s (size %.2f%%)%n",
            r.eligibility(), r.sizePercent() * 100.0));
        sb.append("\nAgent verdicts:\n");
        if (r.agentVerdicts() != null) {
            for (NarratorRequest.AgentVerdictLine v : r.agentVerdicts()) {
                sb.append("  - ").append(v.agentName())
                  .append(": ").append(v.confidence())
                  .append(" — ").append(nullSafe(v.reasoning())).append('\n');
            }
        }
        if (r.warnings() != null && !r.warnings().isEmpty()) {
            sb.append("\nWarnings: ").append(String.join("; ", r.warnings())).append('\n');
        }
        sb.append("\nProduce the narrative JSON.");
        return sb.toString();
    }

    private static void appendPrice(StringBuilder sb, String label, BigDecimal v) {
        if (v == null) return;
        sb.append(label).append(' ').append(v.toPlainString()).append(" · ");
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static String templatedFallback(NarratorRequest r, String reason) {
        String dir = r.direction() == null ? "?" : r.direction();
        String elig = r.eligibility() == null ? "?" : r.eligibility();
        return String.format(Locale.ROOT,
            "[narrator fallback — %s] %s %s %s — %s (size %.2f%%). Zone: %s.",
            reason, r.instrument(), dir, r.timeframe(),
            elig, r.sizePercent() * 100.0, nullSafe(r.zoneName()));
    }

    private static Map<String, Object> buildResponseSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("narrative", Map.of(
            "type", "STRING",
            "description", "3-5 line paragraph for the operator. No new data invented."
        ));
        return Map.of(
            "type", "OBJECT",
            "properties", props,
            "required", List.of("narrative")
        );
    }

    private String parseNarrative(String text) {
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("```")) {
                trimmed = trimmed.replaceFirst("^```json\\s*", "")
                                 .replaceFirst("^```\\s*", "")
                                 .replaceFirst("\\s*```$", "")
                                 .trim();
            }
            JsonNode node = objectMapper.readTree(trimmed);
            return node.path("narrative").asText(null);
        } catch (Exception e) {
            log.warn("Failed to parse Vertex narrator JSON: {} — raw: {}", e.getMessage(), text);
            return null;
        }
    }

    private String extractText(JsonNode root) {
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) return null;
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) return null;
        return parts.get(0).path("text").asText(null);
    }

    private void logUsage(JsonNode root, long latencyMs) {
        try {
            JsonNode usage = root.path("usageMetadata");
            int in  = usage.path("promptTokenCount").asInt(0);
            int out = usage.path("candidatesTokenCount").asInt(0);
            log.info("Narrator Vertex — latency: {}ms | tokens in: {} out: {}", latencyMs, in, out);
        } catch (Exception ignored) {}
    }

    private String buildEndpointUrl() {
        String base = properties.getEndpoint();
        if (base.contains(VERTEX_AI_HOST)) {
            log.warn("Vertex AI host detected ({}). Requires OAuth2 + project/location path " +
                "not yet supported. Use generativelanguage.googleapis.com with Vertex API key.", base);
        }
        return base + "/v1beta/models/" + properties.getModel() + ":generateContent";
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMs());
        factory.setReadTimeout(properties.getTimeoutMs());
        return new RestTemplate(factory);
    }
}

package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.decision.port.DecisionNarratorPort;
import com.riskdesk.domain.decision.port.NarratorRequest;
import com.riskdesk.domain.decision.port.NarratorResponse;
import com.riskdesk.domain.engine.playbook.model.RiskFraction;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Gemini-backed implementation of {@link DecisionNarratorPort}. Produces a 3–5 line
 * natural-language paragraph explaining an already-validated trade decision.
 *
 * <p><b>Key constraint:</b> the system prompt explicitly forbids re-deciding the verdict.
 * The narrator rephrases the agents' reasoning; it does not analyse market data. This
 * keeps the call cheap (~$0.005, 200 output tokens max) and deterministic.
 *
 * <p>Reuses {@link MentorProperties} for endpoint / model / API key / timeout. Falls back
 * gracefully to a templated one-liner when Gemini is disabled or fails — callers persist
 * the decision either way.
 */
@Service
public class GeminiNarrator implements DecisionNarratorPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiNarrator.class);
    private static final int MAX_OUTPUT_TOKENS = 250;

    private final MentorProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public GeminiNarrator(MentorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate(properties);
    }

    @Override
    public NarratorResponse narrate(NarratorRequest request) {
        if (!properties.isEnabled()) {
            return NarratorResponse.fallback(templatedFallback(request, "narrator disabled"));
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return NarratorResponse.fallback(templatedFallback(request, "API key missing"));
        }

        try {
            String systemPrompt = buildSystemPrompt(request.language());
            String userPrompt = buildUserPrompt(request);

            Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                    "parts", List.of(Map.of("text", systemPrompt))
                ),
                "contents", List.of(
                    Map.of("role", "user",
                           "parts", List.of(Map.of("text", userPrompt)))
                ),
                "generationConfig", Map.of(
                    "temperature", 0.1,
                    "maxOutputTokens", MAX_OUTPUT_TOKENS,
                    "responseMimeType", "application/json",
                    "responseSchema", buildResponseSchema()
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
                return NarratorResponse.fallback(templatedFallback(request, "empty Gemini body"));
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
            log.warn("Narrator Gemini call failed: {}", e.getMessage());
            return NarratorResponse.fallback(
                templatedFallback(request, "Gemini error: " + e.getClass().getSimpleName()));
        }
    }

    // ── Prompt building ──────────────────────────────────────────────────

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
            r.eligibility(), RiskFraction.toPercent(r.sizePercent())));

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
            elig, RiskFraction.toPercent(r.sizePercent()), nullSafe(r.zoneName()));
    }

    // ── Gemini response handling ──────────────────────────────────────────

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
            log.warn("Failed to parse narrator JSON: {} — raw: {}", e.getMessage(), text);
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
            log.info("Narrator Gemini — latency: {}ms | tokens in: {} out: {}", latencyMs, in, out);
        } catch (Exception ignored) {}
    }

    private RestTemplate buildRestTemplate(MentorProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Narrator is even lighter than agents — tight timeout keeps the SignalConfluenceBuffer
        // flush responsive when Gemini hiccups.
        int timeout = Math.max(4_000, properties.getTimeoutMs() / 3);
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }
}

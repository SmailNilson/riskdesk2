package com.riskdesk.infrastructure.quant.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.quant.advisor.AdvisorPort;
import com.riskdesk.domain.quant.advisor.AiAdvice;
import com.riskdesk.domain.quant.advisor.MultiInstrumentContext;
import com.riskdesk.domain.quant.memory.MemoryRecord;
import com.riskdesk.domain.quant.memory.SessionMemory;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI advisor implementation backed by the existing Gemini API plumbing
 * ({@link MentorProperties#getApiKey()} + {@code generativelanguage} endpoint).
 *
 * <p><b>Deviation note:</b> the original spec called for the Vertex AI Java
 * SDK in {@code europe-west1} with context caching. RiskDesk does not yet
 * include the Vertex AI dependency — switching brings GCP credential setup
 * and a new dep tree. To keep the slice shippable, this adapter mirrors the
 * pattern already in use by {@code GeminiEmbeddingClient} (direct HTTP +
 * circuit breaker). The {@link AdvisorPort} contract is unchanged, so a
 * future Vertex AI swap is a one-class replacement.</p>
 *
 * <p>Disabled by default — opt in with {@code riskdesk.quant.advisor.enabled=true}.</p>
 */
@Component
@ConditionalOnProperty(prefix = "riskdesk.quant.advisor", name = "enabled", havingValue = "true")
public class GeminiQuantAdvisorAdapter implements AdvisorPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiQuantAdvisorAdapter.class);

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*?\\}");

    private final QuantAdvisorPromptBuilder promptBuilder;
    private final MentorProperties mentorProperties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String advisorModel;
    private final double temperature;
    private final int memoryTopK;

    public GeminiQuantAdvisorAdapter(QuantAdvisorPromptBuilder promptBuilder,
                                     MentorProperties mentorProperties,
                                     ObjectMapper objectMapper,
                                     @Value("${riskdesk.quant.advisor-model:${riskdesk.mentor.model:gemini-3.1-pro-preview}}") String advisorModel,
                                     @Value("${riskdesk.quant.advisor-temperature:0.2}") double temperature,
                                     @Value("${riskdesk.quant.memory-rag-top-k:5}") int memoryTopK) {
        this.promptBuilder = promptBuilder;
        this.mentorProperties = mentorProperties;
        this.objectMapper = objectMapper;
        this.advisorModel = advisorModel;
        this.temperature = temperature;
        this.memoryTopK = memoryTopK;
        this.restTemplate = buildRestTemplate(mentorProperties.getTimeoutMs());
    }

    @Override
    public AiAdvice askAdvice(QuantSnapshot quant,
                              PatternAnalysis pattern,
                              SessionMemory memory,
                              List<MemoryRecord> similarSituations,
                              MultiInstrumentContext context) {
        if (mentorProperties.getApiKey() == null || mentorProperties.getApiKey().isBlank()) {
            log.warn("quant advisor: GEMINI_API_KEY not configured — returning unavailable");
            return AiAdvice.unavailable("api key not configured");
        }

        String prompt = promptBuilder.build(quant, pattern, memory, similarSituations, context, memoryTopK);
        String promptHash = QuantAdvisorPromptBuilder.hash(prompt);
        long startNs = System.nanoTime();

        try {
            String rawText = callGemini(prompt);
            AiAdvice advice = parseAdvice(rawText);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.info("quant advisor: instrument={} score={} verdict={} confidence={} promptHash={} elapsedMs={}",
                quant.instrument(), quant.score(), advice.verdict(), advice.confidence(), promptHash, elapsedMs);
            return advice;
        } catch (Exception ex) {
            log.warn("quant advisor: call failed instrument={} promptHash={} error={}",
                quant.instrument(), promptHash, ex.toString());
            return AiAdvice.unavailable("advisor call failed: " + ex.getClass().getSimpleName());
        }
    }

    private String callGemini(String prompt) {
        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", prompt))
            )),
            "generationConfig", Map.of(
                "temperature", temperature,
                "responseMimeType", "application/json"
            )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", mentorProperties.getApiKey());

        String endpoint = mentorProperties.getEndpoint()
            + "/v1beta/models/"
            + advisorModel
            + ":generateContent";

        JsonNode root = restTemplate.exchange(
            endpoint,
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            JsonNode.class
        ).getBody();
        if (root == null) {
            throw new IllegalStateException("empty Gemini response");
        }
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new IllegalStateException("no candidates in Gemini response");
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalStateException("no parts in Gemini response");
        }
        return parts.get(0).path("text").asText("");
    }

    private AiAdvice parseAdvice(String raw) {
        if (raw == null || raw.isBlank()) {
            return AiAdvice.unavailable("empty advisor response");
        }
        String json = extractFirstJsonObject(raw);
        try {
            JsonNode node = objectMapper.readTree(json);
            String verdictRaw = node.path("verdict").asText("");
            AiAdvice.Verdict verdict;
            try {
                verdict = AiAdvice.Verdict.valueOf(verdictRaw.toUpperCase());
            } catch (IllegalArgumentException ex) {
                return AiAdvice.unavailable("unknown verdict: " + verdictRaw);
            }
            String reasoning = node.path("reasoning").asText("");
            String risk = node.path("risk").asText("");
            double confidence = clamp(node.path("confidence").asDouble(0.0));
            return new AiAdvice(verdict, reasoning, risk, confidence, advisorModel, Instant.now());
        } catch (Exception ex) {
            return AiAdvice.unavailable("parse error: " + ex.getMessage());
        }
    }

    private static String extractFirstJsonObject(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed;
        Matcher m = JSON_BLOCK.matcher(raw);
        return m.find() ? m.group() : raw;
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        return new RestTemplate(factory);
    }
}

package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.infrastructure.config.MentorProperties;
import com.riskdesk.presentation.dto.MentorSimilarAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class GeminiMentorClient implements MentorModelClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiMentorClient.class);

    private static final String SYSTEM_INSTRUCTION = """
        Rôle : Tu es un Mentor Expert en Trading de Futures (Day Trading & Scalping), spécialisé dans l'analyse technique (Price Action, VWAP, Order Flow) et les corrélations inter-marchés. Ton but est d'auditer mes trades sur les métaux (Or/MGC et Platine/PL) avec une rigueur professionnelle.

        Contexte : Je vais te fournir un payload JSON décrivant le contexte marché et l'intention de trade. Il existe deux modes:
        - TRADE_AUDIT: le payload contient déjà un plan d'exécution (entry / stop / take profit)
        - SETUP_REVIEW: le payload ne contient pas forcément entry / stop / take profit; dans ce cas tu dois juger si le setup est valide et proposer toi-même un plan s'il est conforme.

        Règles d'analyse :
        - Sois critique, précis, et professionnel.
        - Si une donnée est absente dans le payload, dis-le clairement et n'invente rien.
        - N'interprète pas l'absence de entry / stop / take profit comme une faute si analysis_mode = SETUP_REVIEW.
        - En mode SETUP_REVIEW, évalue d'abord si l'idée de trade est techniquement valable, puis propose un plan d'exécution seulement si tu juges le setup conforme.
        - En mode TRADE_AUDIT, audite strictement la qualité de l'exécution, du stop, du take profit et du risk management.
        - Appuie ton jugement sur la structure, le VWAP, le momentum, les corrélations, le stop loss et la gestion du trade quand ces données existent.
        - Réponds uniquement en JSON valide.

        Format JSON attendu :
        {
          "technicalQuickAnalysis": "texte court",
          "strengths": ["point 1", "point 2"],
          "errors": ["point 1", "point 2"],
          "verdict": "Trade Validé - Discipline Respectée" ou "Trade Non-Conforme - Erreur de Processus",
          "improvementTip": "une phrase claire",
          "proposedTradePlan": {
            "entryPrice": 0,
            "stopLoss": 0,
            "takeProfit": 0,
            "rewardToRiskRatio": 0,
            "rationale": "texte court"
          } ou null
        }
        """;

    private final MentorProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public GeminiMentorClient(MentorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate(properties);
    }

    @Override
    public MentorModelResult analyze(JsonNode payload, List<MentorSimilarAudit> similarAudits) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Mentor AI integration is disabled.");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured on the backend.");
        }

        try {
            String payloadJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            String similarAuditsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(similarAudits);
            String prompt = """
                Analyse ce trade futures selon le playbook du mentor.
                Utilise uniquement les valeurs du JSON ci-dessous.
                Si des audits similaires sont fournis, utilise-les comme mémoire de processus et non comme vérité absolue.
                Si analysis_mode = SETUP_REVIEW et que le setup paraît valable, retourne un proposedTradePlan cohérent.
                Si analysis_mode = SETUP_REVIEW et que le setup n'est pas valable, retourne proposedTradePlan = null.
                Si analysis_mode = TRADE_AUDIT, proposedTradePlan peut être null.

                Payload JSON:
                %s

                Audits similaires:
                %s
                """.formatted(payloadJson, similarAuditsJson);

            Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                    "parts", List.of(Map.of("text", SYSTEM_INSTRUCTION))
                ),
                "contents", List.of(
                    Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", prompt))
                    )
                ),
                "generationConfig", Map.of(
                    "temperature", 1,
                    "responseMimeType", "application/json"
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", properties.getApiKey());
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            String endpoint = properties.getEndpoint()
                + "/v1beta/models/"
                + properties.getModel()
                + ":generateContent";

            JsonNode root = restTemplate.exchange(endpoint, HttpMethod.POST, request, JsonNode.class).getBody();
            String text = extractText(root);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("Gemini returned an empty response.");
            }

            return new MentorModelResult(properties.getModel(), sanitizeJsonText(text));
        } catch (Exception e) {
            log.error("Gemini mentor call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Gemini mentor call failed: " + e.getMessage(), e);
        }
    }

    private RestTemplate buildRestTemplate(MentorProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMs());
        factory.setReadTimeout(properties.getTimeoutMs());
        return new RestTemplate(factory);
    }

    private String extractText(JsonNode root) {
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return null;
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return null;
        }
        return parts.get(0).path("text").asText(null);
    }

    private String sanitizeJsonText(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```json\\s*", "");
            trimmed = trimmed.replaceFirst("^```\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }
}

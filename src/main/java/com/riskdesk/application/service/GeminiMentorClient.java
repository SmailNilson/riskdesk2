package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

@Service
public class GeminiMentorClient implements MentorModelClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiMentorClient.class);
    private static final int MAX_ATTEMPTS = 2;

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
        - RÈGLE D'ÉVALUATION ET DE PLANIFICATION : je vais te fournir un entry_price qui est soit une hypothèse de ma part, soit le prix actuel du marché. Tu dois évaluer la pertinence de ce prix. Cependant, que le trade soit conforme ou non à ce prix précis, tu dois systématiquement recalculer et proposer toi-même le meilleur prix d'entrée absolu en Limit Order. Cette Optimal Entry doit être dérivée des zones de liquidité, du VWAP et des Order Blocks (nearest_support_ob / nearest_resistance_ob).
        - RÈGLE DE LA DEEP ENTRY (Entrée Safe) : en plus de l'Optimal Entry, analyse momentum_and_flow. Si tu proposes un LONG sur pullback mais que le Money Flow est RED, tu dois proposer une deuxième entrée appelée Safe Deep Entry. Cette entrée doit être plus basse, dans le fond extrême de l'Order Block ou sur une moyenne lente majeure, pour anticiper un liquidity sweep. Si le contexte ne justifie pas cette option, retourne safeDeepEntry = null.
        - RÈGLE DE RÉ-ÉVALUATION : si trade_intention.review_type = MANUAL_REANALYSIS, alors ceci est une ré-évaluation d'une alerte passée. Le bloc original_alert_context décrit quand et pourquoi l'alerte initiale a sonné. Les autres données JSON représentent le contexte de marché en temps réel au moment de la relance. Dans ce cas, ton but est de juger si le setup d'origine est toujours valide maintenant, si l'entrée actuelle est encore exploitable, si le trade est déjà parti (late entry / FOMO), ou si la structure a été invalidée depuis l'alerte initiale.
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
            "rationale": "texte court",
            "safeDeepEntry": {
              "entryPrice": 0,
              "rationale": "texte court"
            } ou null
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
            String reanalysisInstruction = buildReanalysisInstruction(payload);
            String prompt = """
                Analyse ce trade futures selon le playbook du mentor.
                Utilise uniquement les valeurs du JSON ci-dessous.
                Si des audits similaires sont fournis, utilise-les comme mémoire de processus et non comme vérité absolue.
                Si entry_price est fourni, évalue sa pertinence mais ne t'y limite jamais.
                Tu dois recalculer toi-même l'Optimal Entry en Limit Order, même si le verdict final est non conforme.
                Retourne proposedTradePlan dès que les niveaux de marché suffisent à construire un plan exploitable.
                N'utilise proposedTradePlan = null que si les données techniques sont insuffisantes pour proposer une entrée fiable.
                Si trade_intention.review_type = MANUAL_REANALYSIS, traite original_alert_context comme le contexte historique de départ, mais fonde ton verdict principal sur les données live que tu reçois maintenant.
                %s

                Payload JSON:
                %s

                Audits similaires:
                %s
                """.formatted(reanalysisInstruction, payloadJson, similarAuditsJson);

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

            JsonNode root = executeWithRetry(endpoint, request);
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

    private JsonNode executeWithRetry(String endpoint, HttpEntity<Map<String, Object>> request) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restTemplate.exchange(endpoint, HttpMethod.POST, request, JsonNode.class).getBody();
            } catch (RuntimeException e) {
                lastFailure = e;
                if (!isTransientTimeout(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Gemini mentor call timed out on attempt {}/{}. Retrying once.", attempt, MAX_ATTEMPTS);
            }
        }
        throw lastFailure == null ? new IllegalStateException("Gemini mentor call failed.") : lastFailure;
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

    private boolean isTransientTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            if (current instanceof ResourceAccessException
                && current.getMessage() != null
                && current.getMessage().toLowerCase().contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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

    private String buildReanalysisInstruction(JsonNode payload) {
        if (!"MANUAL_REANALYSIS".equals(payload.path("trade_intention").path("review_type").asText(null))) {
            return "";
        }

        String originalAlertTime = payload.path("original_alert_context").path("original_alert_time").asText("inconnue");
        String originalAlertPrice = payload.path("original_alert_context").path("original_alert_price").isNumber()
            ? payload.path("original_alert_context").path("original_alert_price").decimalValue().toPlainString()
            : "inconnu";
        return """
            ATTENTION : Ceci est une RÉ-ÉVALUATION d'une alerte passée. L'alerte initiale a sonné à %s au prix de %s.
            Les données JSON que tu reçois maintenant sont les données EN TEMPS RÉEL.
            Ton but est de me dire : le setup d'origine est-il toujours valide ? Sommes-nous au bon prix pour entrer maintenant, le trade est-il déjà parti (Late entry/FOMO), ou la structure a-t-elle été invalidée entre-temps ?
            """.formatted(originalAlertTime, originalAlertPrice);
    }
}

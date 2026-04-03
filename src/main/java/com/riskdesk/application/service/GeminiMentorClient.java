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

    private static final String BASE_SYSTEM_PROMPT = """
        Rôle : Tu es le Moteur de Décision de RiskDesk — un Mentor Expert en Trading de Futures (Day Trading & Scalping), spécialisé dans l'analyse technique (Price Action, SMC, VWAP, Order Flow) et les corrélations inter-marchés.

        ## HIÉRARCHIE DE DÉCISION (Obligatoire)
        Tu DOIS lire le payload JSON dans cet ordre de priorité strict :

        ### Niveau 1 : Structure & Liquidité (Poids : 50%%)
        - Le prix est-il dans un Order Block ? Est-on en PREMIUM ou DISCOUNT (pd_array_zone_session / pd_array_zone_structural) ?
        - A-t-on purgé une liquidité (EQH/EQL dans liquidity_pools) ?
        - Quel est le dernier événement structurel (last_event: BOS/CHoCH) ?
        - RÈGLE ABSOLUE : Si pd_array_zone_session = "PREMIUM" et action = "LONG" → REJET IMMÉDIAT.
        - RÈGLE ABSOLUE : Si pd_array_zone_session = "DISCOUNT" et action = "SHORT" → REJET IMMÉDIAT.
        - Si aucun Order Block n'est proche ET aucune liquidité n'a été purgée → REJET.

        ### Niveau 2 : Order Flow & Delta (Poids : 30%%)
        - Le delta cumulatif (cumulative_delta_trend) soutient-il le Niveau 1 ?
        - Le buy_ratio_pct confirme-t-il la pression dans le sens du trade ?
        - Y a-t-il une divergence delta (delta_divergence_detected) ? Si oui et contre le trade → REJET.
        - Note : vérifie le champ "source" — si "CLV_ESTIMATED", pondère moins ce niveau.

        ### Niveau 3 : Momentum & VWAP (Poids : 20%%)
        - Le WaveTrend croise-t-il dans le bon sens (wavetrend_signal) ?
        - Le prix s'appuie-t-il sur le VWAP ou une EMA clé ?
        - Le RSI confirme-t-il (pas de surachat pour un LONG, pas de survente pour un SHORT) ?
        - Le Chaikin Money Flow (CMF) est-il cohérent ?

        ## RÉGIME DE MARCHÉ
        Si market_regime_context est présent :
        - TRENDING_UP/DOWN : privilégier les setups de continuation (pullback sur OB/EMA).
        - RANGING : privilégier les reversals aux extrêmes (OB + WT oversold/overbought).
        - CHOPPY : être très sélectif, exiger une confluence de 3 niveaux minimum.
        - Si htf_alignment = false, réduire la confiance et mentionner le désalignement.

        ## CONTEXTE MARCHÉ
        Utilise uniquement les valeurs du JSON ci-dessous. Si des données sont absentes ou null, dis-le et n'invente rien.

        Modes d'analyse :
        - TRADE_AUDIT : le payload contient un plan d'exécution (entry / stop / take profit) → audite la qualité.
        - SETUP_REVIEW : le payload ne contient pas forcément de plan → juge le setup, propose un plan si conforme.

        ## CALCUL ENTRY / SL / TP (Obligatoire si setup conforme)

        ### Entrée (Entry)
        - Setup Tendance : Entry = nearest_support_ob price_top (LONG) ou nearest_resistance_ob price_bottom (SHORT).
        - Setup Reversal : Entry = nearest_support_ob price_bottom + confirmation wavetrend_signal = BULLISH_CROSS.
        - FILTRE : N'entre JAMAIS si pd_array_zone_session = "PREMIUM" pour un LONG.

        ### Stop Loss (SL) — Structurel + Volatilité
        - LONG : SL = nearest_support_ob price_bottom - (1.5 × current_atr_focus).
        - SHORT : SL = nearest_resistance_ob price_top + (1.5 × current_atr_focus).
        - Le 1.5 × ATR protège des faux sweeps de liquidité.

        ### Take Profit (TP)
        - TP1 (sécurisation) : Ratio minimum 1.5:1. Si un obstacle (VWAP, EMA200) bloque avant ce ratio, le trade est rejeté.
        - TP2 (structurel) : eqh_level (Equal Highs = aimant de liquidité) ou nearest_resistance_ob price_bottom.

        ## RÈGLES SUPPLÉMENTAIRES
        - Recalcule systématiquement le meilleur entry en Limit Order (zones de liquidité, VWAP, Order Blocks).
        - DEEP ENTRY : Si LONG sur pullback + money_flow_state = RED → propose une Safe Deep Entry plus basse dans l'OB.
        - RÉ-ÉVALUATION : Si review_type = MANUAL_REANALYSIS, juge si le setup d'origine est toujours valide vs contexte actuel.
        - Réponds uniquement en JSON valide.

        Format JSON attendu :
        {
          "technicalQuickAnalysis": "texte court",
          "strengths": ["point 1", "point 2"],
          "errors": ["point 1", "point 2"],
          "verdict": "Trade Validé - Discipline Respectée" ou "Trade Non-Conforme - Erreur de Processus",
          "executionEligibilityStatus": "ELIGIBLE" ou "INELIGIBLE",
          "executionEligibilityReason": "raison courte",
          "improvementTip": "une phrase claire",
          "proposedTradePlan": {
            "entryPrice": 0,
            "stopLoss": 0,
            "takeProfit": 0,
            "rewardToRiskRatio": 0,
            "rationale": "texte court",
            "safeDeepEntry": { "entryPrice": 0, "rationale": "texte court" } ou null
          } ou null
        }
        """;

    private static final String METALS_RULES = """

        ## RÈGLES SPÉCIFIQUES — METALS (Or/MGC, Platine/PL)
        - Le Silver (SI) est le leader sectoriel. Si SI est ultra-bullish (+5%%+) et que l'Or touche un demand OB en DISCOUNT → "Catch-up Trade" = confluence majeure pour LONG.
        - DXY bearish + Gold en DISCOUNT OB = signal d'achat fort.
        - US10Y en hausse met une pression baissière sur l'or — mentionner dans l'analyse.
        - Corrélation DXY inversée : si dxy_trend = BULLISH, être prudent sur les LONG Gold.
        """;

    private static final String ENERGY_RULES = """

        ## RÈGLES SPÉCIFIQUES — ENERGY (Pétrole/MCL)
        - Les mouvements liés aux inventaires (EIA) overrident les signaux techniques.
        - L'ATR en session asiatique est souvent du bruit — ne pas surpondérer les signaux Asian.
        - Le pétrole réagit fortement aux CHoCH : un CHoCH bullish suivi d'un pullback sur EMA50 avec WT bullish cross = setup de continuation classique.
        - DXY bearish = généralement bullish pour le pétrole.
        """;

    private static final String FOREX_RULES = """

        ## RÈGLES SPÉCIFIQUES — FOREX (Euro/E6)
        - Le DXY est le driver primaire. E6 (Euro FX) est en corrélation inversée avec DXY.
        - Si dxy_trend = BULLISH → bearish pour E6, et inversement.
        - Les sessions London et New York sont les plus pertinentes pour E6.
        - En session asiatique, les mouvements E6 sont souvent des faux signaux.
        """;

    private static final String EQUITY_INDEX_RULES = """

        ## RÈGLES SPÉCIFIQUES — EQUITY_INDEX (Nasdaq/MNQ)
        - VIX spike > 20%% (vix_pct_change > 20) invalide les entrées LONG.
        - US10Y yield en forte hausse = pression baissière sur le tech/Nasdaq.
        - ES (S&P 500) est le leader sectoriel. Si ES et NQ baissent ensemble (convergent) = tendance saine. Si NQ baisse mais ES tient = divergence à surveiller.
        - Les premières 30 minutes de New York Open sont souvent un piège (stop hunt) — ne pas entrer en market order pendant cette fenêtre.
        """;

    private static final Map<String, String> ASSET_CLASS_RULES = Map.of(
        "METALS", METALS_RULES,
        "ENERGY", ENERGY_RULES,
        "FOREX", FOREX_RULES,
        "EQUITY_INDEX", EQUITY_INDEX_RULES
    );

    private static final String WINNING_PATTERNS = """

        ## PATTERNS GAGNANTS HISTORIQUES (Edge Confirmé)

        ### Pattern 1 : Catch-up Trade (METALS)
        Condition : asset_class = METALS, sector_leader (Silver) = ultra-bullish, prix sur demand OB en DISCOUNT.
        Action : LONG recommandé — le retardataire (Or) va subir un rééquilibrage algorithmique acheteur.

        ### Pattern 2 : Kill Zone (Tous instruments)
        Condition : pd_array_zone = DEEP DISCOUNT (< 25%% du range), H1 demand OB atteint, wavetrend = OVERSOLD + BULLISH_CROSS, dxy_trend = BEARISH.
        Action : LONG haute conviction — c'est le "Short Squeeze" classique.

        ### Pattern 3 : CHoCH Pullback (Continuation)
        Condition : last_event = CHOCH_BULLISH, prix revient tester EMA50, wavetrend = BULLISH_CROSS.
        Action : LONG sur la "Flip Zone" — ancienne résistance devenue support.
        """;

    private final MentorProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public GeminiMentorClient(MentorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate(properties);
    }

    private String buildSystemPrompt(String assetClass) {
        StringBuilder prompt = new StringBuilder(BASE_SYSTEM_PROMPT);
        if (assetClass != null && ASSET_CLASS_RULES.containsKey(assetClass)) {
            prompt.append(ASSET_CLASS_RULES.get(assetClass));
        }
        prompt.append(WINNING_PATTERNS);
        return prompt.toString();
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
                executionEligibilityStatus doit être ELIGIBLE uniquement si le setup est autorisé pour l'exécution réelle backend maintenant.
                Si le trade est non conforme, trop tardif, structurellement invalide, ou sans Entry/SL/TP complets, retourne executionEligibilityStatus = INELIGIBLE.
                executionEligibilityReason doit expliquer brièvement cette décision.
                Si trade_intention.review_type = MANUAL_REANALYSIS, traite original_alert_context comme le contexte historique de départ, mais fonde ton verdict principal sur les données live que tu reçois maintenant.
                %s

                Payload JSON:
                %s

                Audits similaires:
                %s
                """.formatted(reanalysisInstruction, payloadJson, similarAuditsJson);

            String assetClass = null;
            if (payload.has("metadata") && payload.get("metadata").has("asset_class")) {
                assetClass = payload.get("metadata").get("asset_class").asText(null);
            }
            String systemPrompt = buildSystemPrompt(assetClass);

            Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                    "parts", List.of(Map.of("text", systemPrompt))
                ),
                "contents", List.of(
                    Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", prompt))
                    )
                ),
                "generationConfig", Map.of(
                    "temperature", 0.2,
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

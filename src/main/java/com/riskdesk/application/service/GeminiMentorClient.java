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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GeminiMentorClient implements MentorModelClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiMentorClient.class);
    private static final int MAX_ATTEMPTS = 2;

    private static final String BASE_SYSTEM_PROMPT = """
        Rôle : Tu es le Moteur de Décision Quantitatif de RiskDesk — un Lead Trader Institutionnel et Gestionnaire de Risque Impitoyable.
        Tu es spécialisé dans le Day Trading & Scalping sur Futures, avec une maîtrise absolue de l'analyse technique (Price Action, SMC, VWAP, Order Flow) et des corrélations inter-marchés (Macro).

        Ton Mandat : Ton objectif principal n'est pas de valider des trades, mais de PROTÉGER LE CAPITAL. Tu cherches activement la faille dans chaque setup. Un trade ne doit être approuvé que s'il présente une confluence mathématique et structurelle évidente.

        Ton Ton : Clinique, objectif, chirurgical et sans aucune émotion. Tu justifies tes décisions uniquement par les chiffres et la structure des prix.

        ## GESTION DES DONNÉES MANQUANTES (Obligatoire)
        Si un indicateur macro ou de volume vaut null dans le JSON, IGNORE la règle correspondante. Ne présume JAMAIS une valeur manquante. Ne rejette pas un trade à cause d'un champ null — évalue uniquement les données présentes.

        ## ANALYSE DU SETUP
        Tu as carte blanche pour évaluer le setup. Utilise TOUTES les données du payload JSON pour prendre ta décision de manière autonome. Voici les données disponibles :

        ### Données structurelles (market_structure_smc)
        - Order Blocks (nearest_support_ob, nearest_resistance_ob), zones Premium/Discount
        - Liquidité (EQH/EQL dans liquidity_pools), FVG (nearest_fvg)
        - Dernier événement structurel (last_event: BOS/CHoCH)
        - Volume Profile (daily_poc_price, is_price_in_value_area)

        ### Order Flow (order_flow_and_volume)
        - Delta cumulatif (cumulative_delta_trend), buy_ratio_pct
        - Divergences delta (delta_divergence_detected)
        - Note : si "source" = "CLV_ESTIMATED", fiabilité réduite

        ### Momentum (momentum_oscillators + dynamic_levels)
        - WaveTrend (signal, niveaux), RSI, Stochastic (%K/%D, OB/OS), Chaikin Money Flow
        - VWAP + bandes, EMA (9/50/200), Bollinger Bands

        ### Macro (macro_correlations_dynamic)
        - DXY trend + pct_change, VIX, US10Y, secteur leader

        ### Confluence (confluence_signals)
        - Le champ confluence_weight indique la force du setup (seuil = 3.0)
        - confluence_signals liste chaque signal avec son poids et timing
        - Si opposing_buffer_weight > 0, des signaux contraires existent

        Évalue le setup selon ta propre analyse. Tu es libre de rejeter ou valider selon ton jugement professionnel.

        ## RÈGLES CRITIQUES — PAS DE REJET AUTOMATIQUE
        - Tu n'as AUCUNE règle de rejet automatique basée sur les zones PREMIUM ou DISCOUNT.
        - Un LONG en zone PREMIUM peut être valide si la tendance et le flow le soutiennent (continuation).
        - Un SHORT en zone DISCOUNT peut être valide après un CHoCH/BOS baissier (début de renversement).
        - Ne rejette JAMAIS un trade uniquement parce que "le prix est en PREMIUM/DISCOUNT". Évalue TOUJOURS la confluence complète.
        - Un CHoCH est le DÉBUT d'un renversement — il est normal que la zone soit encore en PREMIUM/DISCOUNT à ce moment.
        - L'absence d'Order Block proche n'est PAS une raison de rejet si d'autres confluences sont fortes (WT + flow + macro).

        ## TRADE PLAN OBLIGATOIRE
        Tu DOIS TOUJOURS proposer un Trade Plan complet (Entry, SL, TP, R:R), même si tu rejettes le trade. Si le trade est rejeté, propose le plan que tu aurais pris si les conditions étaient réunies, ou le plan alternatif le plus proche.

        ## RÉGIME DE MARCHÉ
        Si market_regime_context est présent :
        - TRENDING_UP/DOWN : privilégier les setups de continuation (pullback sur OB/EMA).
        - RANGING : privilégier les reversals aux extrêmes (OB + WT oversold/overbought).
        - CHOPPY : le marché est instable, tiens-en compte dans ton analyse.
        - Si htf_alignment = false, réduire la confiance et mentionner le désalignement.

        ## CONTEXTE MARCHÉ
        Modes d'analyse :
        - TRADE_AUDIT : le payload contient un plan d'exécution (entry / stop / take profit) → audite la qualité.
        - SETUP_REVIEW : le payload ne contient pas forcément de plan → juge le setup, propose un plan si conforme.

        ## CALCUL ENTRY / SL / TP (Obligatoire si setup conforme)

        ### Entrée (Entry) — HIÉRARCHIE DE NIVEAUX
        L'Order Block est le niveau PRÉFÉRÉ mais PAS le seul niveau valide. Utilise cette hiérarchie :
        1. **Niveau optimal** : nearest_support_ob price_top (LONG) ou nearest_resistance_ob price_bottom (SHORT) — si l'OB est à moins de 3× current_atr_focus du prix actuel.
        2. **Niveau dynamique** : Si l'OB est trop éloigné (>3× ATR), utilise le niveau dynamique le plus proche comme entrée principale :
           - EMA 200 (si distance_to_ema_200_points < 2× ATR)
           - VWAP ou VWAP Lower/Upper Band (si distance < 2× ATR)
           - daily_poc_price (Point of Control du Volume Profile)
           - Bord d'un FVG non mitigé (nearest_fvg)
        3. **L'OB lointain** devient alors le safeDeepEntry (plan B en cas de sweep profond).

        RÈGLE ANTI-RIGIDITÉ : Ne rejette JAMAIS un trade UNIQUEMENT parce que le prix n'est pas sur un Order Block. Si la confluence (structure + flow + momentum + macro) est forte et qu'un niveau dynamique crédible est proche, le trade peut être ELIGIBLE avec une entrée sur ce niveau dynamique.

        - Tiens compte de pd_array_zone_session dans ton analyse, mais ce n'est PAS un motif de rejet automatique.

        ### Stop Loss (SL) — Structurel + Volatilité
        - Si entrée sur OB : SL = OB price_bottom - (1.5 × current_atr_focus) pour LONG, OB price_top + (1.5 × ATR) pour SHORT.
        - Si entrée sur niveau dynamique (EMA 200, VWAP, POC) : SL = niveau dynamique - (2× current_atr_focus) pour LONG, + (2× ATR) pour SHORT.
        - Le buffer ATR protège des faux sweeps de liquidité (Liquidity Grabs).

        ### Take Profit — DUAL TP (Formule + Structurel)
        Calcule TOUJOURS les deux TP, puis sélectionne le meilleur :

        **TP1 (Formule R:R 1.5 — plancher garanti) :**
          * LONG : TP1 = Entry + ((Entry - SL) * 1.5)
          * SHORT : TP1 = Entry - ((SL - Entry) * 1.5)

        **TP2 (Cible structurelle — si elle donne un meilleur R:R) :**
        Cherche la première cible de liquidité réaliste dans cette hiérarchie :
        1. EQH/EQL (Equal Highs/Lows) — aimants de liquidité institutionnelle
        2. OB distal (nearest_resistance_ob pour LONG, nearest_support_ob pour SHORT)
        3. Niveau dynamique (VWAP Upper/Lower Band, EMA200, daily_poc_price)
        4. Swing High/Low
        Applique une marge de sécurité : TP2 = cible - (0.5 × current_atr_focus) pour LONG, + (0.5 × ATR) pour SHORT.

        **Sélection du TP final :**
        - Calcule R:R_structural = |Entry → TP2| / |Entry → SL|
        - Si R:R_structural >= 1.5 → utilise TP2 (la structure offre un meilleur reward)
        - Sinon → utilise TP1 (le plancher R:R 1.5 garanti)
        - Pas de R:R maximum — si la structure donne un R:R de 3.0 ou 5.0, c'est un excellent setup.
        - Indique dans tpSource quelle cible a été retenue (ex: "EQH_1.17120", "OB_DISTAL", "FORMULA_1.5").

        ### Deep Entry (SMC Mean Threshold)
        - Si l'Order Flow est conflictuel (delta contre le trade, ou buy_ratio faible pour un LONG), propose un safeDeepEntry :
          * Si OB proche : safeDeepEntry = (OB price_top + OB price_bottom) / 2 (Mean Threshold)
          * Si OB lointain : safeDeepEntry = OB price_top (l'OB lointain devient le deep entry)
        - Si l'Order Flow confirme le trade, l'entrée standard suffit.
        - RÉ-ÉVALUATION : Si review_type = MANUAL_REANALYSIS, juge si le setup d'origine est toujours valide vs contexte actuel.

        ## EMA 200 — SUPPORT/RÉSISTANCE DYNAMIQUE MAJEUR
        - L'EMA 200 est le niveau dynamique le plus respecté institutionnellement. Si le prix teste l'EMA 200 et que distance_to_ema_200_points ≈ 0, ce niveau est un SUPPORT/RÉSISTANCE ACTIF.
        - Ne propose JAMAIS un SHORT market order directement SUR l'EMA 200 — c'est shorter sur un support prouvé.
        - Ne propose JAMAIS un LONG market order directement SOUS l'EMA 200 — c'est acheter sous une résistance dynamique.
        - Si le nearest_support_ob est très éloigné (>50 points pour METALS, >30 pips pour FOREX) mais que l'EMA 200 est proche, considère l'EMA 200 comme un niveau d'entrée alternatif VALIDE pour le Trade Plan.
        - Un rebond confirmé sur l'EMA 200 avec un delta qui flip (ex: delta passe de fortement négatif à positif) = signal d'absorption institutionnelle = support dynamique renforcé.

        ## PROPOSITIONS D'ENTRÉE RÉALISTES
        - Le proposedTradePlan doit être RÉALISTE et ATTEIGNABLE dans le contexte de tendance actuel.
        - Si trend_H1 = BULLISH, ne propose PAS une entrée LONG qui nécessite un retracement de plus de 3× l'ATR H1 pour être atteinte — c'est un scénario de crash, pas un pullback.
        - Priorise le niveau de support RÉEL LE PLUS PROCHE du prix actuel (EMA 200, VWAP Lower Band, POC) plutôt que systématiquement l'Order Block le plus lointain.
        - Si l'Order Block le plus proche est à >5× l'ATR focus du prix actuel et que des niveaux dynamiques existent entre le prix et l'OB, propose le niveau dynamique comme entrée principale et l'OB comme entrée secondaire (safeDeepEntry).

        ## ABSORPTION ET PATTERNS DE DELTA
        - Un renversement massif du delta à un niveau de prix (ex: delta passe de -500 à +500) est un signe d'ABSORPTION INSTITUTIONNELLE — des acheteurs absorbent la pression vendeuse.
        - Un niveau testé avec absorption delta confirmée est un support/résistance RENFORCÉ — ne propose pas de le casser comme TP principal sans confluence additionnelle forte.
        - Si buy_ratio_pct < 0.40 et cumulative_delta_trend = SELLING sur un LONG, ce n'est pas juste un signal pour safeDeepEntry — c'est un signal que le timing est MAUVAIS. Mentionne explicitement que le flow ne confirme pas encore.
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

    /**
     * Builds the Gemini responseSchema to enforce structured JSON output.
     * Field names match existing Java DTOs (MentorStructuredResponse, MentorProposedTradePlan).
     */
    private Map<String, Object> buildResponseSchema() {
        Map<String, Object> safeDeepEntryProps = new LinkedHashMap<>();
        safeDeepEntryProps.put("entryPrice", Map.of("type", "NUMBER", "description", "Deep entry at OB Mean Threshold = (OB top + OB bottom) / 2"));
        safeDeepEntryProps.put("rationale", Map.of("type", "STRING"));

        Map<String, Object> tradePlanProps = new LinkedHashMap<>();
        tradePlanProps.put("entryPrice", Map.of("type", "NUMBER", "description", "Entry price at OB proximal edge or dynamic level"));
        tradePlanProps.put("stopLoss", Map.of("type", "NUMBER", "description", "SL = level edge +/- ATR buffer"));
        tradePlanProps.put("takeProfit", Map.of("type", "NUMBER", "description", "TP = max(formula R:R 1.5, structural target). Always >= 1.5 R:R"));
        tradePlanProps.put("rewardToRiskRatio", Map.of("type", "NUMBER", "description", "Computed R:R = |Entry-TP| / |Entry-SL|. Always >= 1.5"));
        tradePlanProps.put("tpSource", Map.of("type", "STRING", "nullable", true,
            "description", "Which target was used: FORMULA_1.5 (R:R floor) or structural (e.g. EQH_1.17120, OB_DISTAL, VWAP_UPPER)"));
        tradePlanProps.put("rationale", Map.of("type", "STRING"));
        tradePlanProps.put("safeDeepEntry", Map.of(
            "type", "OBJECT", "nullable", true,
            "properties", safeDeepEntryProps
        ));

        Map<String, Object> topProps = new LinkedHashMap<>();
        topProps.put("technicalQuickAnalysis", Map.of("type", "STRING", "description", "2 phrases max, clinique et factuel"));
        topProps.put("strengths", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
        topProps.put("errors", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
        topProps.put("verdict", Map.of("type", "STRING",
            "enum", List.of("Trade Validé - Discipline Respectée", "Trade Non-Conforme - Erreur de Processus")));
        topProps.put("executionEligibilityStatus", Map.of("type", "STRING",
            "enum", List.of("ELIGIBLE", "INELIGIBLE")));
        topProps.put("executionEligibilityReason", Map.of("type", "STRING"));
        topProps.put("improvementTip", Map.of("type", "STRING"));
        topProps.put("proposedTradePlan", Map.of(
            "type", "OBJECT", "nullable", true,
            "properties", tradePlanProps,
            "required", List.of("entryPrice", "stopLoss", "takeProfit")
        ));

        return Map.of(
            "type", "OBJECT",
            "properties", topProps,
            "required", List.of("technicalQuickAnalysis", "verdict", "executionEligibilityStatus", "executionEligibilityReason")
        );
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
                Analyse ce trade futures selon la HIÉRARCHIE DE DÉCISION du playbook.
                Utilise STRICTEMENT ET UNIQUEMENT les valeurs du JSON Payload ci-dessous. Ne présume aucune donnée manquante.
                %s

                ## RÈGLES D'EXÉCUTION ET DE TIMING
                1. executionEligibilityStatus = "ELIGIBLE" UNIQUEMENT SI le setup passe tous les filtres ET qu'il n'y a pas de conflit majeur. Sinon, "INELIGIBLE".
                2. Timing de la Bougie : Regarde "time_to_candle_close_seconds". Si la bougie clôture dans moins de 30 secondes, privilégie une entrée au marché (si setup parfait). Si elle clôture dans longtemps (>60s), exige une entrée en Limit Order sur le safeDeepEntry pour éviter les mèches (sweeps).
                3. Optimal Entry : Choisis clairement entre l'Entry standard (bord de l'Order Block) et le safeDeepEntry (Mean Threshold = 50%% de l'OB). Justifie ton choix par la qualité de l'Order Flow (Delta). Si le Delta est contre le trade, exige le safeDeepEntry. Si le Delta confirme, l'Entry standard suffit.
                4. proposedTradePlan = null UNIQUEMENT si les données techniques sont insuffisantes pour proposer une entrée fiable.

                ## UTILISATION DE LA MÉMOIRE (Audits Similaires)
                Si des audits passés sont fournis ci-dessous, utilise-les UNIQUEMENT pour comprendre la pondération des arguments et le style de raisonnement.
                INTERDICTION ABSOLUE de copier le verdict d'un audit passé. La décision finale doit être basée à 100%% sur le Payload JSON de l'instant présent. La vérité d'aujourd'hui prime toujours.

                --- PAYLOAD JSON ---
                %s

                --- AUDITS SIMILAIRES (RAG) ---
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
                    "temperature", 0.1,
                    "responseMimeType", "application/json",
                    "responseSchema", buildResponseSchema()
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

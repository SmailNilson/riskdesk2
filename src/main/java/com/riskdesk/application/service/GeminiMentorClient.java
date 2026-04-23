package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Duration;
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
        - Champ "source" : "REAL_TICKS" = ticks IBKR classifiés Lee-Ready (haute fiabilité) ;
          "CLV_ESTIMATED" = estimation depuis Close-Location-Value des bougies (fiabilité réduite).
        - **RÈGLE DE PONDÉRATION** : quand source="CLV_ESTIMATED", le poids de l'order flow
          dans la décision est DIVISÉ PAR DEUX. Ne rejette JAMAIS un setup uniquement sur
          un signal CLV_ESTIMATED ; exige au minimum une confirmation structurelle (BOS/CHoCH
          aligné, OB defended) ou un momentum aligné (WT + RSI) avant de t'appuyer dessus.
        - Quand source="CLV_ESTIMATED", mentionne explicitement "[order flow estimé]" dans
          technicalQuickAnalysis pour signaler la dégradation de fiabilité.

        ### Momentum (momentum_oscillators + dynamic_levels)
        - WaveTrend (signal, niveaux), RSI, Stochastic (%K/%D, OB/OS), Chaikin Money Flow
        - VWAP + bandes, EMA (9/50/200), Bollinger Bands

        ### Macro (macro_correlations_dynamic)
        - DXY trend + pct_change, VIX, US10Y, secteur leader
        - **RÈGLE ANTI-TAUTOLOGIE FOREX** : sur les paires EUR/USD (6E), GBP/USD, USD/JPY, etc.
          le DXY est MATHÉMATIQUEMENT dérivé de la paire elle-même (EUR pèse 57,6% du DXY).
          Ne présente JAMAIS "DXY haussier tiré par la faiblesse de l'EUR" comme une
          "divergence macro" : c'est la corrélation structurelle de la paire, pas un signal
          indépendant. Sur FOREX, cherche les vraies divergences ailleurs :
            - US10Y (rendement obligataire) vs direction de la paire
            - Cross-rates (EURGBP, EURJPY) qui isolent la force spécifique de la devise
            - Sector leaders par asset class (ex. Silver pour METALS, VIX pour EQUITY_INDEX)
          Si AUCUNE divergence macro indépendante n'est disponible (tous champs null sauf DXY),
          NE CITE PAS la macro comme raison de rejet — évalue sur la structure + momentum + flow.

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
        - Si htf_aligned = false, réduire la confiance et mentionner le désalignement HTF.

        ## RÉGIME DE VOLATILITÉ
        Si volatility_regime est présent dans risk_management_gatekeeper :
        - LOW (atr_percentile_rank < 20) : marché comprimé. Les breakouts sont violents — privilégier les entrées sur OB avec SL serré.
        - NORMAL : comportement standard. Appliquer les règles ATR normales.
        - ELEVATED (atr_percentile_rank entre 81 et 90 inclus) : volatilité au-dessus de la moyenne mais exploitable.
          - Adapter les stops : SL minimum 2× current_atr_focus.
          - Pas de rejet automatique. Si la confluence est claire (≥2 signaux alignés OU 1 signal fort + order flow confirmant), le trade reste ELIGIBLE.
          - Un "order flow confirmant" = CMF > 0.3 aligné avec la direction, OU buy_ratio > 65%% pour LONG / < 35%% pour SHORT, OU absorption détectée dans l'OB (defended=true ou absorptionScore > 2).
        - EXTREME (atr_percentile_rank > 90) : volatilité anormale (crise, news, liquidation).
          - Recommander 50%% de la taille standard dans improvementTip.
          - SL minimum 2.5× current_atr_focus ; TP minimum 2× ATR du prix.
          - Exiger l'UNE de ces conditions (pas toutes) :
            (a) confluence >= 3 signaux, OU
            (b) 2 signaux alignés + order flow confirmant (CMF > 0.3 aligné, OU buy_ratio > 70%% / < 30%%, OU absorption defended=true), OU
            (c) setup "Catch-up" ou "Kill Zone" (patterns historiques à edge confirmé).
          - Rejeter UNIQUEMENT si aucune de (a)(b)(c) n'est satisfaite. Ne rejette PAS un setup simplement parce que atr_percentile_rank > 90 si le flow et la structure sont cohérents.

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
        - trend_H1 est un objet multi-résolution avec 5 échelles : swing_50 (macro), swing_25 (mid), swing_9 (fast), internal_5 (rapide), micro_1 (immédiat). Évalue la CONVERGENCE : si 4/5 disent BULLISH et swing_50 dit BEARISH, c'est un lag du swing_50 — pas un vrai signal baissier. Si la majorité est d'accord, la tendance est claire ; si les résolutions divergent, c'est un contexte MIXTE à traiter avec prudence.
        - Si la majorité des résolutions trend_H1 indiquent BULLISH, ne propose PAS une entrée LONG qui nécessite un retracement de plus de 3× l'ATR H1 — c'est un scénario de crash, pas un pullback.
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
        - ORDER FLOW METALS : Si absorption bullish dans OB demand Gold + Silver (SI) en hausse → catch-up trade haute conviction (confluences structure + flow + macro alignées).
        """;

    private static final String ENERGY_RULES = """

        ## RÈGLES SPÉCIFIQUES — ENERGY (Pétrole/MCL)
        - Les mouvements liés aux inventaires (EIA) overrident les signaux techniques.
        - L'ATR en session asiatique est souvent du bruit — ne pas surpondérer les signaux Asian.
        - Le pétrole réagit fortement aux CHoCH : un CHoCH bullish suivi d'un pullback sur EMA50 avec WT bullish cross = setup de continuation classique.
        - DXY bearish = généralement bullish pour le pétrole.
        - En volatility_regime EXTREME sur MCL (fréquent lors de crises géopolitiques ou inventaires EIA), les BOS et Supertrend flips sont souvent des faux signaux causés par des wicks de liquidation. Exiger un CHoCH confirmé ou un OB mitigé pour valider. Un simple BOS est insuffisant.
        - Si atr_percentile_rank > 90, mentionner explicitement "volatilité extrême — réduction de taille recommandée" dans improvementTip.
        - ORDER FLOW ENERGY : Si flash crash sur MCL pendant EIA news → attendre crashPhase = REVERSING avant d'entrer. Absorption pendant EIA = signal fort car liquidité réelle (pas du bruit).
        """;

    private static final String FOREX_RULES = """

        ## RÈGLES SPÉCIFIQUES — FOREX (Euro/E6)
        - Le DXY est le driver primaire. E6 (Euro FX) est en corrélation inversée avec DXY.
        - Si dxy_trend = BULLISH → bearish pour E6, et inversement.
        - Les sessions London et New York sont les plus pertinentes pour E6.
        - En session asiatique, les mouvements E6 sont souvent des faux signaux.
        - ANALYSE DE DIVERGENCE MONÉTAIRE : Consulter dxy_component_breakdown dans macro_correlations_dynamic. Si dxy_trend = BEARISH ET que la composante EURUSD contribue majoritairement à la baisse DXY, c'est un signal de force EUR structurelle (divergence Fed dovish vs ECB neutral/hawkish). Augmenter la confiance des signaux LONG E6 et mentionner "momentum structurel EUR" dans l'analyse. Les signaux SHORT E6 dans ce contexte sont counter-trend macro — signaler le risque et réduire eligibility.
        - Si dxy_trend = BULLISH avec EURUSD en composante dominante de la hausse DXY, c'est un signal de faiblesse EUR structurelle. Favoriser SHORT E6, traiter les LONG avec prudence accrue.
        - Si us10y_yield_pct_change est disponible ET positif (yields en hausse), cela renforce le USD → bearish E6.
        - ORDER FLOW FOREX : Delta divergence sur E6 + DXY confirmant → signal de retournement haute confiance. Si depth_imbalance disponible, l'utiliser comme filtre de timing (< 0.3 = ne pas fader).
        """;

    private static final String EQUITY_INDEX_RULES = """

        ## RÈGLES SPÉCIFIQUES — EQUITY_INDEX (Nasdaq/MNQ)
        - VIX spike > 20%% (vix_pct_change > 20) invalide les entrées LONG.
        - US10Y yield en forte hausse = pression baissière sur le tech/Nasdaq.
        - ES (S&P 500) est le leader sectoriel. Si ES et NQ baissent ensemble (convergent) = tendance saine. Si NQ baisse mais ES tient = divergence à surveiller.
        - Les premières 30 minutes de New York Open sont souvent un piège (stop hunt) — ne pas entrer en market order pendant cette fenêtre.
        - ORDER FLOW EQUITY : Si VIX spike >20%% + depth_imbalance < 0.3 sur MNQ → ne pas acheter même avec OB demand. Attendre stabilisation depth (imbalance > 0.5) avant toute entrée LONG.
        """;

    private static final Map<String, String> ASSET_CLASS_RULES = Map.of(
        "METALS", METALS_RULES,
        "ENERGY", ENERGY_RULES,
        "FOREX", FOREX_RULES,
        "EQUITY_INDEX", EQUITY_INDEX_RULES
    );

    private static final String ORDER_FLOW_INTELLIGENCE = """

        ## ORDER FLOW INTELLIGENCE

        ### Sources de données Order Flow
        Les métriques order flow proviennent de deux sources (champ `source` dans order_flow_and_volume) :
        - `REAL_TICKS` : delta calculé trade par trade via classification Lee-Ready (bid/ask). HAUTE confiance — chaque contrat est classifié individuellement.
        - `CLV_ESTIMATED` : approximation via Close Location Value (position du close dans la range de la bougie). Confiance MOYENNE — utiliser comme indicatif seulement, NE PAS baser de décision dessus.

        ### Métriques Order Flow disponibles
        - `delta_flow_current` : (buyVolume - sellVolume) sur fenêtre glissante 5 min. Positif = acheteurs agressifs dominent.
        - `cumulative_delta_trend` : direction du delta cumulé depuis début de session CME (BUYING/SELLING/NEUTRAL).
        - `buy_ratio_pct` : %% de volume acheteur agressif. >55%% = pression acheteuse. <45%% = pression vendeuse.
        - `delta_divergence_detected` + `delta_divergence_type` : prix vs delta en désaccord. BEARISH_DIVERGENCE = prix monte mais delta baisse (vendeurs cachés). BULLISH_DIVERGENCE = prix baisse mais delta monte (acheteurs cachés). C'est un signal FORT.

        ### Scores Order Flow des Zones SMC (smc_order_flow_scores)
        Chaque zone SMC porte un score de qualité 0-100 basé sur l'order flow réel :
        - `obFormationScore` / `obLiveScore` : qualité de l'Order Block. Formation = delta de l'impulsion à la création. Live = absorption + depth en temps réel. >70 = institutionnel fort. <40 = zone fragile.
        - `defended` : true si absorption détectée + prix stable dans la zone = acheteur/vendeur passif massif. C'est LE signal institutionnel le plus fiable.
        - `fvgQualityScore` : intensité directionnelle du Fair Value Gap. >70 = vraie imbalance institutionnelle. <30 = gap vide sans conviction.
        - `breakConfidenceScore` + `confirmed` : fiabilité du BOS/CHoCH. confirmed=true = volume spike >2× + delta aligné. confirmed=false = possible fakeout.
        - `liquidityConfirmScore` : ordres visibles au niveau EQH/EQL dans le carnet Level 2.

        ### Corrélation Order Flow × Zones SMC

        1. **ABSORPTION dans un OB** :
           - absorptionScore > 2.0 dans un OB demand → confiance LONG +20 points
           - absorptionScore > 2.0 dans un OB supply → confiance SHORT +20 points
           - OB avec `defended: true` → zone institutionnelle CONFIRMÉE, traiter comme HIGH CONVICTION

        2. **DELTA DIVERGENCE** :
           - BEARISH_DIVERGENCE dans zone supply → signal SHORT renforcé (vendeurs cachés absorbent les achats)
           - BULLISH_DIVERGENCE dans zone demand → signal LONG renforcé (acheteurs cachés absorbent les ventes)

        3. **SPOOFING** (si spoofing_detected dans smc_order_flow_scores) :
           - Spoofing ASK près d'une supply zone → le "mur" vendeur est faux, breakout probable → ignorer la résistance
           - Spoofing BID près d'une demand zone → le "plancher" acheteur est faux, breakdown probable → ignorer le support

        4. **ICEBERG** (si iceberg_detected dans smc_order_flow_scores) :
           - Iceberg BID détecté → acheteur institutionnel caché, biais LONG
           - Iceberg ASK détecté → vendeur institutionnel caché, biais SHORT

        5. **FLASH CRASH** (si crash_phase présent) :
           - crashPhase = REVERSING + reversalScore > 70 dans zone demand → signal LONG haute conviction
           - crashPhase = ACCELERATING → NE PAS fader le mouvement, attendre DECELERATING/REVERSING

        6. **DEPTH IMBALANCE** (si depth_imbalance présent) :
           - depthImbalance < 0.3 → bids en fuite, momentum baissier, ne pas acheter
           - depthImbalance > 0.7 → asks en fuite, momentum haussier, ne pas vendre

        ### Pondération selon la source
        Si `source: "REAL_TICKS"` :
          - Order flow = CONFIRMATIONS PRINCIPALES. Pondérer : Structure 40%% > Order Flow 35%% > Momentum 25%%
          - L'absorption et le delta divergence sont des signaux DÉCISIONNELS
          - Un OB avec obLiveScore > 80 + defended = prioriser cette zone sur tout autre niveau

        Si `source: "CLV_ESTIMATED"` :
          - Volume = INDICATIF seulement. Pondérer : Structure 50%% > Momentum 30%% > Volume 20%%
          - NE PAS utiliser les divergences delta comme signal décisionnel
          - NE PAS mentionner l'absorption ou le depth (données non disponibles en CLV)

        ### Règle de priorisation des zones
        Toujours prioriser les zones avec les scores OF les plus élevés. Un OB à formationScore=90 dans un contexte de confluence est plus fiable qu'un OB à formationScore=30 même avec d'autres confirmations momentum.
        """;

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
    /**
     * Fail-fast breaker. Without this, a Gemini outage let every alert pay the
     * full {@code timeoutMs} × {@link #MAX_ATTEMPTS} retry budget; the
     * 4-thread {@code mentorExecutor} saturated in minutes and subsequent
     * reviews were dropped by the stuck-ANALYZING cleanup. Same calibration as
     * {@code GeminiAgentAdapter}: 3 consecutive failures → open for 30 s.
     */
    private final GeminiCircuitBreaker breaker;

    @Autowired
    public GeminiMentorClient(MentorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate(properties);
        this.breaker = new GeminiCircuitBreaker(3, Duration.ofSeconds(30));
    }

    /** Package-private constructor for tests that need to inject a pre-built breaker. */
    GeminiMentorClient(MentorProperties properties, ObjectMapper objectMapper, GeminiCircuitBreaker breaker) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate(properties);
        this.breaker = breaker;
    }

    /** Exposed for actuator-style introspection. */
    public GeminiCircuitBreaker.State circuitState() {
        return breaker.currentState();
    }

    private String buildSystemPrompt(String assetClass) {
        StringBuilder prompt = new StringBuilder(BASE_SYSTEM_PROMPT);
        prompt.append(ORDER_FLOW_INTELLIGENCE);
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
        tradePlanProps.put("orderFlowConfidence", Map.of(
            "type", "INTEGER", "nullable", true,
            "description", "0-100 confidence score based on order flow data quality and alignment. null if source=CLV_ESTIMATED."));
        tradePlanProps.put("orderFlowFactors", Map.of(
            "type", "ARRAY", "nullable", true,
            "items", Map.of("type", "STRING"),
            "description", "Key OF factors that influenced the decision (e.g. 'Absorption bullish score 3.2 in demand OB', 'Delta divergence bearish')"));

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
        if (!breaker.allowRequest()) {
            // Fail fast — a real Gemini outage would otherwise block every
            // caller for 2×timeoutMs (up to ~2 min each) while the 4-thread
            // mentorExecutor saturates.
            throw new IllegalStateException("Gemini mentor circuit breaker OPEN — failing fast");
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
                2. Timing de la Bougie et Confirmation : Si "signal_confirmed_on_candle_close" = true, le signal est déjà validé par la clôture de la bougie précédente. "time_to_candle_close_seconds" réfère à la NOUVELLE bougie en cours. Dans ce cas, évalue le setup sur ses mérites (structure, Order Flow, macro) et propose un plan d'entrée optimal (market order si le prix est sur un niveau clé, limit order sur OB/FVG sinon). NE PAS rejeter pour raison de timing — le signal est confirmé. Si "signal_confirmed_on_candle_close" = false ou absent, c'est une review manuelle intra-bougie : <30s → market order possible (si setup parfait), >60s → exige Limit Order sur safeDeepEntry pour éviter les mèches (sweeps).
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
                // Gemini 3.x "thinking" models silently consume output tokens
                // for their reasoning trace when thinkingBudget is unset. Prod
                // audit showed ~96% of SIGNAL reviews fell back to
                // "Structured mentor response unavailable" because replies
                // were truncated at ~400 chars with the full 3000 token
                // budget — thinking was eating the output room. Pinning
                // thinkingBudget guarantees the JSON schema fits in what
                // remains of maxOutputTokens.
                "generationConfig", Map.of(
                    "temperature", 0.1,
                    // Bumped from 1500 → 3000 after prod audit: ~37% of rejections
                    // were Gemini responses truncated mid-first-field (≈160 chars).
                    // Schema is large; 1500 wasn't enough budget to finish the JSON.
                    "maxOutputTokens", 3000,
                    "thinkingConfig", Map.of("thinkingBudget", properties.getThinkingBudget()),
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

            // Measure latency + payload size
            long startMs = System.currentTimeMillis();
            int payloadLength = payloadJson.length();
            int ragLength = similarAuditsJson.length();
            int systemPromptLength = systemPrompt.length();

            JsonNode root = executeWithRetry(endpoint, request);

            long latencyMs = System.currentTimeMillis() - startMs;

            // Cost + latency tracking
            logUsageMetadata(root, assetClass, latencyMs, systemPromptLength, payloadLength, ragLength);

            String text = extractText(root);
            if (text == null || text.isBlank()) {
                // The outer catch records the breaker failure — avoid double-counting.
                throw new IllegalStateException("Gemini returned an empty response.");
            }

            breaker.recordSuccess();
            return new MentorModelResult(properties.getModel(), sanitizeJsonText(text));
        } catch (Exception e) {
            breaker.recordFailure();
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
        JsonNode first = candidates.get(0);
        // finishReason != STOP means the model was truncated (MAX_TOKENS, SAFETY,
        // RECITATION, etc.). We surface this so downstream can attempt partial recovery.
        String finishReason = first.path("finishReason").asText(null);
        if (finishReason != null && !"STOP".equalsIgnoreCase(finishReason)) {
            log.warn("Gemini response terminated with finishReason={} — downstream parser will attempt partial recovery.", finishReason);
        }
        JsonNode parts = first.path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return null;
        }
        // Concatenate every text part — Gemini may emit the JSON across multiple parts
        // especially with responseMimeType=application/json.
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            String t = part.path("text").asText(null);
            if (t != null && !t.isEmpty()) {
                sb.append(t);
            }
        }
        String text = sb.toString();
        return text.isEmpty() ? null : text;
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

    private void logUsageMetadata(JsonNode root, String assetClass,
                                   long latencyMs, int systemPromptChars,
                                   int payloadChars, int ragChars) {
        try {
            JsonNode usage = root.path("usageMetadata");
            int inputTokens = usage.isMissingNode() ? 0 : usage.path("promptTokenCount").asInt(0);
            int outputTokens = usage.isMissingNode() ? 0 : usage.path("candidatesTokenCount").asInt(0);
            int cachedTokens = usage.isMissingNode() ? 0 : usage.path("cachedContentTokenCount").asInt(0);
            int totalTokens = usage.isMissingNode() ? 0 : usage.path("totalTokenCount").asInt(inputTokens + outputTokens);

            log.info("Gemini review — latency: {}ms | tokens in: {} out: {} cached: {} total: {} | "
                     + "chars system: {} payload: {} rag: {} | asset: {}",
                     latencyMs, inputTokens, outputTokens, cachedTokens, totalTokens,
                     systemPromptChars, payloadChars, ragChars,
                     assetClass != null ? assetClass : "unknown");

            if (latencyMs > 20_000) {
                log.warn("Gemini SLOW review — {}ms for {} input tokens (asset: {}). "
                         + "Consider: reduce RAG audits (rag chars: {}), "
                         + "switch to gemini-2.5-flash for auto reviews, "
                         + "or check if preview model is throttled.",
                         latencyMs, inputTokens, assetClass, ragChars);
            }
        } catch (Exception e) {
            log.debug("Failed to extract Gemini usage metadata: {}", e.getMessage());
        }
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

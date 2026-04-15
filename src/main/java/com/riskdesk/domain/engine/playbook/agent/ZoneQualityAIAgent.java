package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.agent.port.AgentAiRequest;
import com.riskdesk.domain.engine.playbook.agent.port.AgentAiResponse;
import com.riskdesk.domain.engine.playbook.agent.port.GeminiAgentPort;
import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SmcOrderBlock;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Agent 3 — Zone Quality & Freshness (Gemini-powered).
 *
 * Replaces the old rule-based ZoneQualityAgent. Evaluates what the mechanical
 * playbook can't see: OB order-flow formation/live scores, FVG quality,
 * BOS/CHoCH OK-vs-FAKE confirmation, EQH/EQL L2 liquidity badge, Volume
 * Profile positioning, trap zones, and clutter on the path to TP.
 *
 * <p>Falls back to a neutral MEDIUM verdict if Gemini is unavailable.
 */
public class ZoneQualityAIAgent implements TradingAgent {

    public static final String AGENT_NAME = "Zone-Quality-AI";

    private static final String SYSTEM_PROMPT = """
        Rôle : Tu es un Analyste Quantitatif spécialisé SMC + Order Flow. Tu évalues
        la QUALITÉ INSTITUTIONNELLE de la zone d'entrée proposée par le playbook
        (Order Block, FVG, ou Breaker) et la fiabilité du dernier BOS/CHoCH qui
        l'a validée.

        Focus : ne pas juger la direction ou la macro — ça c'est d'autres agents.
        Juger UNIQUEMENT la zone elle-même et son environnement immédiat.

        ## Signaux à analyser
        - zone_quality.ob_live_score : 0-100. >70 = institutionnel fort. <40 = fragile.
        - zone_quality.ob_defended : true = absorption confirmée, zone CONFIRMÉE.
        - zone_quality.ob_absorption_score : >2.0 = absorption massive.
        - zone_quality.fvg_quality_score : >70 = vraie imbalance. <30 = gap vide.
        - zone_quality.nearest_break_confirmed : false = CHoCH/BOS FAKE (wick de liquidité).
        - zone_quality.nearest_break_confidence : <40 = casse douteuse.
        - zone_quality.nearest_equal_level_liquidity_score : ordres visibles au niveau EQH/EQL.
        - zone_profile.obstacles_between_entry_and_tp : nb OB/FVG opposés sur le chemin.
        - zone_profile.trap_zone_nearby : OB opposé à moins de 1× ATR de l'entrée.
        - zone_profile.zone_size_atr : taille de la zone / ATR.
        - volume_profile.price_in_value_area + poc_price : POC = aimant.

        ## Règles
        1. ob_defended=true + ob_live_score>70 + 0 obstacle path → HIGH.
        2. ob_live_score<40 OU fvg_quality<30 (FVG vide) → LOW, flags.weak_zone=true.
        3. nearest_break_confirmed=false + CHoCH (pas BOS) → LOW, flags.fake_break=true,
           flags.size_pct=0.003 (on trade sur une casse qui n'est pas confirmée).
        4. obstacles_between_entry_and_tp>=3 → LOW (path cluttered).
        5. trap_zone_nearby=true AVEC ob_defended=false → LOW, flags.trap_risk=true.
        6. zone_size_atr > 3.0 → MEDIUM max (entrée imprécise).
        7. Zones stackées (3+ aligned), POC derrière SL → HIGH bonus.
        8. Si toutes les métriques zone_quality sont null (pas d'enrichment OF) →
           MEDIUM, flags.no_of_enrichment=true.

        ## Sortie JSON OBLIGATOIRE
        {
          "confidence": "HIGH" | "MEDIUM" | "LOW",
          "reasoning": "max 250 caractères, cite les scores clés et le verdict",
          "flags": {
            "weak_zone": boolean,
            "fake_break": boolean,
            "trap_risk": boolean,
            "no_of_enrichment": boolean,
            "size_pct": number (optionnel, 0..0.01)
          }
        }
        """;

    private final GeminiAgentPort port;

    public ZoneQualityAIAgent(GeminiAgentPort port) {
        this.port = port;
    }

    @Override
    public String name() { return AGENT_NAME; }

    @Override
    public AgentVerdict evaluate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.bestSetup() == null || playbook.plan() == null) {
            return AgentVerdict.skip(name(), "No setup to analyze");
        }

        PlaybookInput input = context.input();
        if (input == null) {
            return AgentVerdict.skip(name(), "No input data available");
        }

        SetupCandidate setup = playbook.bestSetup();
        Direction direction = playbook.filters().tradeDirection();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", context.instrument().name());
        payload.put("timeframe", context.timeframe());
        payload.put("direction", direction.name());
        payload.put("setup_score", playbook.checklistScore());

        // Zone identity
        Map<String, Object> zone = new LinkedHashMap<>();
        zone.put("type", setup.type().name());
        zone.put("name", setup.zoneName());
        zone.put("high", setup.zoneHigh());
        zone.put("low", setup.zoneLow());
        zone.put("price_in_zone", setup.priceInZone());
        zone.put("distance_from_price", setup.distanceFromPrice());
        zone.put("rr_ratio", setup.rrRatio());
        payload.put("zone", zone);

        // Plan
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("entry", playbook.plan().entryPrice());
        plan.put("stop_loss", playbook.plan().stopLoss());
        plan.put("tp1", playbook.plan().takeProfit1());
        plan.put("tp2", playbook.plan().takeProfit2());
        payload.put("plan", plan);

        // Zone quality (OF-enriched)
        var zq = context.zoneQuality();
        Map<String, Object> zqMap = new LinkedHashMap<>();
        if (zq != null) {
            zqMap.put("ob_formation_score", zq.obFormationScore());
            zqMap.put("ob_live_score", zq.obLiveScore());
            zqMap.put("ob_defended", zq.obDefended());
            zqMap.put("ob_absorption_score", zq.obAbsorptionScore());
            zqMap.put("fvg_quality_score", zq.fvgQualityScore());
            zqMap.put("nearest_break_confidence", zq.nearestBreakConfidence());
            zqMap.put("nearest_break_confirmed", zq.nearestBreakConfirmed());
            zqMap.put("nearest_equal_level_liquidity_score", zq.nearestEqualLevelLiquidityScore());
        }
        payload.put("zone_quality", zqMap);

        // Zone profile (computed locally from the input)
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("obstacles_between_entry_and_tp",
            countObstacles(input, direction, playbook.plan().entryPrice(), playbook.plan().takeProfit1()));
        profile.put("trap_zone_nearby",
            hasTrapZoneNearby(input, direction, playbook.plan().entryPrice(), context.atr()));
        if (context.atr() != null && context.atr().doubleValue() > 0) {
            double zoneSize = setup.zoneHigh().subtract(setup.zoneLow()).doubleValue();
            profile.put("zone_size_atr", zoneSize / context.atr().doubleValue());
        }
        profile.put("stacked_aligned_zones_count", countStackedZones(playbook, context));
        payload.put("zone_profile", profile);

        // Volume profile
        var vp = context.volumeProfile();
        Map<String, Object> vpMap = new LinkedHashMap<>();
        if (vp != null) {
            vpMap.put("poc_price", vp.pocPrice());
            vpMap.put("value_area_high", vp.valueAreaHigh());
            vpMap.put("value_area_low", vp.valueAreaLow());
            vpMap.put("price_in_value_area", vp.priceInValueArea());
        }
        payload.put("volume_profile", vpMap);

        AgentAiResponse ai = port.analyze(new AgentAiRequest(
            AGENT_NAME, SYSTEM_PROMPT, payload, 500));

        if (!ai.aiAvailable()) {
            return fallbackVerdict(playbook, context, direction);
        }

        Confidence conf = parseConfidence(ai.confidence());
        return new AgentVerdict(name(), conf, direction, ai.reasoning(),
            AgentAdjustments.fromGeminiFlags(ai.flags()));
    }

    // ── Local helpers ────────────────────────────────────────────────────

    private AgentVerdict fallbackVerdict(PlaybookEvaluation playbook, AgentContext context, Direction direction) {
        var zq = context.zoneQuality();
        int obstacles = countObstacles(context.input(), direction,
            playbook.plan().entryPrice(), playbook.plan().takeProfit1());

        Confidence conf;
        String reasoning;
        if (zq != null && zq.isHighQualityOb() && obstacles == 0) {
            conf = Confidence.HIGH;
            reasoning = "AI unavailable — fallback: OB defended + clear path";
        } else if (zq != null && (zq.isWeakOb() || zq.isFakeBreak())) {
            conf = Confidence.LOW;
            reasoning = "AI unavailable — fallback: weak OB or fake break";
        } else if (obstacles >= 3) {
            conf = Confidence.LOW;
            reasoning = "AI unavailable — fallback: path cluttered";
        } else {
            conf = Confidence.MEDIUM;
            reasoning = "AI unavailable — fallback: zone acceptable";
        }
        return new AgentVerdict(name(), conf, direction, reasoning,
            AgentAdjustments.flags(Map.of("fallback", true, "obstacles", obstacles)));
    }

    private int countObstacles(PlaybookInput input, Direction direction,
                                BigDecimal entry, BigDecimal tp) {
        if (entry == null || tp == null || input == null) return 0;
        int count = 0;
        String dir = direction.name();

        for (SmcOrderBlock ob : input.activeOrderBlocks()) {
            boolean opposing = ("LONG".equalsIgnoreCase(dir) && "BEARISH".equalsIgnoreCase(ob.type()))
                            || ("SHORT".equalsIgnoreCase(dir) && "BULLISH".equalsIgnoreCase(ob.type()));
            if (!opposing || ob.mid() == null) continue;
            boolean between = "LONG".equalsIgnoreCase(dir)
                ? ob.mid().compareTo(entry) > 0 && ob.mid().compareTo(tp) < 0
                : ob.mid().compareTo(entry) < 0 && ob.mid().compareTo(tp) > 0;
            if (between) count++;
        }

        for (var fvg : input.activeFairValueGaps()) {
            boolean opposing = ("LONG".equalsIgnoreCase(dir) && "BEARISH".equalsIgnoreCase(fvg.bias()))
                            || ("SHORT".equalsIgnoreCase(dir) && "BULLISH".equalsIgnoreCase(fvg.bias()));
            if (!opposing) continue;
            BigDecimal mid = fvg.top().add(fvg.bottom())
                .divide(BigDecimal.TWO, 6, java.math.RoundingMode.HALF_UP);
            boolean between = "LONG".equalsIgnoreCase(dir)
                ? mid.compareTo(entry) > 0 && mid.compareTo(tp) < 0
                : mid.compareTo(entry) < 0 && mid.compareTo(tp) > 0;
            if (between) count++;
        }
        return count;
    }

    private boolean hasTrapZoneNearby(PlaybookInput input, Direction direction,
                                       BigDecimal entry, BigDecimal atr) {
        if (entry == null || atr == null || input == null) return false;
        double threshold = atr.doubleValue();
        String dir = direction.name();
        for (SmcOrderBlock ob : input.activeOrderBlocks()) {
            boolean opposing = ("LONG".equalsIgnoreCase(dir) && "BEARISH".equalsIgnoreCase(ob.type()))
                            || ("SHORT".equalsIgnoreCase(dir) && "BULLISH".equalsIgnoreCase(ob.type()));
            if (!opposing || ob.mid() == null) continue;
            double dist = entry.subtract(ob.mid()).abs().doubleValue();
            if (dist <= threshold) return true;
        }
        return false;
    }

    private long countStackedZones(PlaybookEvaluation playbook, AgentContext context) {
        double atr = context.atr() != null ? context.atr().doubleValue() : 1.0;
        return playbook.setups().stream()
            .filter(s -> s.priceInZone() || s.distanceFromPrice() < atr)
            .count();
    }

    private static Confidence parseConfidence(String s) {
        if ("HIGH".equalsIgnoreCase(s)) return Confidence.HIGH;
        if ("LOW".equalsIgnoreCase(s)) return Confidence.LOW;
        return Confidence.MEDIUM;
    }
}

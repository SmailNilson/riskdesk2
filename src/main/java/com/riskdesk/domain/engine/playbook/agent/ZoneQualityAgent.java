package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SmcOrderBlock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 5 — Zone Quality & Freshness Analyst.
 *
 * Detects what the Playbook CAN'T see:
 * - Is this zone fresh (never retested) or exhausted (touched multiple times)?
 * - Does the zone have strong order flow confirmation?
 * - Are there obstacles between entry and TP that weaken the plan?
 * - Is there an opposing zone directly above/below that could trap?
 */
public class ZoneQualityAgent implements TradingAgent {

    @Override
    public String name() { return "Zone-Quality"; }

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
        String direction = playbook.filters().tradeDirection().name();
        List<String> warnings = new ArrayList<>();
        List<String> strengths = new ArrayList<>();

        // ── 1. Opposing zones between entry and TP ──────────────────────

        int obstaclesBetween = countObstaclesBetweenEntryAndTp(
            input, direction, playbook.plan().entryPrice(), playbook.plan().takeProfit1());

        if (obstaclesBetween >= 3) {
            warnings.add(String.format("%d opposing zones between entry and TP1 — cluttered path", obstaclesBetween));
        } else if (obstaclesBetween >= 2) {
            warnings.add(String.format("%d opposing zones between entry and TP1 — partial resistance", obstaclesBetween));
        } else if (obstaclesBetween == 0) {
            strengths.add("Clear path to TP1 — no opposing zones");
        }

        // ── 2. Trap detection: opposing zone immediately behind entry ───

        boolean trapZoneNearby = hasOpposingZoneNear(
            input, direction, playbook.plan().entryPrice(), context.atr());

        if (trapZoneNearby) {
            warnings.add("Opposing zone close to entry — potential trap/rejection after fill");
        }

        // ── 3. Zone size vs ATR (is the zone too wide?) ─────────────────

        if (context.atr() != null && context.atr().doubleValue() > 0) {
            double zoneSize = setup.zoneHigh().subtract(setup.zoneLow()).doubleValue();
            double zoneAtrRatio = zoneSize / context.atr().doubleValue();

            if (zoneAtrRatio > 3.0) {
                warnings.add(String.format("Zone too wide (%.1f x ATR) — imprecise entry", zoneAtrRatio));
            } else if (zoneAtrRatio < 0.5) {
                strengths.add(String.format("Tight zone (%.1f x ATR) — precise entry", zoneAtrRatio));
            }
        }

        // ── 4. Multiple aligned zones stacking ──────────────────────────

        long alignedZoneCount = playbook.setups().stream()
            .filter(s -> s.priceInZone() || s.distanceFromPrice() < (context.atr() != null ? context.atr().doubleValue() : 1.0))
            .count();

        if (alignedZoneCount >= 3) {
            strengths.add(String.format("%d zones stacking at price — strong confluence", alignedZoneCount));
        } else if (alignedZoneCount >= 2) {
            strengths.add("Double zone confluence");
        }

        // ── 5. Breaker vs fresh OB preference ───────────────────────────

        if (setup.zoneName().contains("Breaker")) {
            strengths.add("Breaker zone — polarity flip confirms direction change");
        }

        // ── Build verdict ────────────────────────────────────────────────

        Confidence confidence;
        String reasoning;

        if (warnings.size() >= 3) {
            confidence = Confidence.LOW;
            reasoning = "Zone weak: " + String.join("; ", warnings);
            return new AgentVerdict(name(), confidence, playbook.filters().tradeDirection(),
                reasoning, Map.of("size_pct", 0.005, "zone_warnings", warnings.size()));
        } else if (warnings.isEmpty() && strengths.size() >= 2) {
            confidence = Confidence.HIGH;
            reasoning = "Zone strong: " + String.join("; ", strengths);
        } else if (warnings.isEmpty()) {
            confidence = Confidence.HIGH;
            reasoning = strengths.isEmpty() ? "Zone acceptable — no red flags" : strengths.get(0);
        } else {
            confidence = Confidence.MEDIUM;
            String combined = String.join("; ", warnings);
            if (!strengths.isEmpty()) combined += " | Strengths: " + String.join(", ", strengths);
            reasoning = combined;
        }

        return new AgentVerdict(name(), confidence, playbook.filters().tradeDirection(),
            reasoning, Map.of("obstacles", obstaclesBetween, "strengths", strengths.size()));
    }

    private int countObstaclesBetweenEntryAndTp(PlaybookInput input, String direction,
                                                  BigDecimal entry, BigDecimal tp) {
        if (entry == null || tp == null) return 0;
        int count = 0;

        // Opposing OBs
        for (SmcOrderBlock ob : input.activeOrderBlocks()) {
            boolean opposing = ("LONG".equalsIgnoreCase(direction) && "BEARISH".equalsIgnoreCase(ob.type()))
                            || ("SHORT".equalsIgnoreCase(direction) && "BULLISH".equalsIgnoreCase(ob.type()));
            if (!opposing || ob.mid() == null) continue;

            boolean between = "LONG".equalsIgnoreCase(direction)
                ? ob.mid().compareTo(entry) > 0 && ob.mid().compareTo(tp) < 0
                : ob.mid().compareTo(entry) < 0 && ob.mid().compareTo(tp) > 0;
            if (between) count++;
        }

        // Opposing FVGs
        for (var fvg : input.activeFairValueGaps()) {
            boolean opposing = ("LONG".equalsIgnoreCase(direction) && "BEARISH".equalsIgnoreCase(fvg.bias()))
                            || ("SHORT".equalsIgnoreCase(direction) && "BULLISH".equalsIgnoreCase(fvg.bias()));
            if (!opposing) continue;

            BigDecimal mid = fvg.top().add(fvg.bottom()).divide(BigDecimal.TWO, 6, java.math.RoundingMode.HALF_UP);
            boolean between = "LONG".equalsIgnoreCase(direction)
                ? mid.compareTo(entry) > 0 && mid.compareTo(tp) < 0
                : mid.compareTo(entry) < 0 && mid.compareTo(tp) > 0;
            if (between) count++;
        }

        return count;
    }

    private boolean hasOpposingZoneNear(PlaybookInput input, String direction,
                                         BigDecimal entry, BigDecimal atr) {
        if (entry == null || atr == null) return false;
        double threshold = atr.doubleValue() * 1.0;

        for (SmcOrderBlock ob : input.activeOrderBlocks()) {
            boolean opposing = ("LONG".equalsIgnoreCase(direction) && "BEARISH".equalsIgnoreCase(ob.type()))
                            || ("SHORT".equalsIgnoreCase(direction) && "BULLISH".equalsIgnoreCase(ob.type()));
            if (!opposing || ob.mid() == null) continue;

            double dist = entry.subtract(ob.mid()).abs().doubleValue();
            if (dist <= threshold) return true;
        }
        return false;
    }
}

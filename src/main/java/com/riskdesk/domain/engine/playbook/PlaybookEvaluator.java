package com.riskdesk.domain.engine.playbook;

import com.riskdesk.domain.engine.playbook.calculator.MechanicalPlanCalculator;
import com.riskdesk.domain.engine.playbook.detector.BreakRetestDetector;
import com.riskdesk.domain.engine.playbook.detector.LiquiditySweepDetector;
import com.riskdesk.domain.engine.playbook.detector.ZoneRetestDetector;
import com.riskdesk.domain.engine.playbook.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Core playbook engine. Evaluates filters, detects setups, builds checklist,
 * and computes a mechanical trade plan. Pure domain logic -- no Spring, no JPA,
 * no external API calls.
 */
public class PlaybookEvaluator {

    private final ZoneRetestDetector zoneRetestDetector = new ZoneRetestDetector();
    private final LiquiditySweepDetector liquiditySweepDetector = new LiquiditySweepDetector();
    private final BreakRetestDetector breakRetestDetector = new BreakRetestDetector();
    private final MechanicalPlanCalculator planCalculator = new MechanicalPlanCalculator();

    public PlaybookEvaluation evaluate(PlaybookInput input) {
        // Step 1: Evaluate filters
        FilterResult filters = evaluateFilters(input);

        // If bias not aligned, short-circuit
        if (!filters.biasAligned()) {
            return noSetupResult(filters);
        }

        Direction direction = filters.tradeDirection();

        // Step 2: Detect all setups
        List<SetupCandidate> allSetups = new ArrayList<>();
        allSetups.addAll(zoneRetestDetector.detect(input, direction));
        allSetups.addAll(liquiditySweepDetector.detect(input, direction));
        allSetups.addAll(breakRetestDetector.detect(input, direction));

        if (allSetups.isEmpty()) {
            return noSetupResult(filters);
        }

        // Step 3: Calculate plan + checklist ONCE per candidate and keep them
        // paired so the best-setup path doesn't re-run the calculator/checklist.
        // EvalEntry is a local record — it never leaves this method.
        record EvalEntry(SetupCandidate candidate, PlaybookPlan plan,
                         List<ChecklistItem> checklist, int score) {}

        List<EvalEntry> evaluated = new ArrayList<>();
        for (SetupCandidate setup : allSetups) {
            PlaybookPlan plan = planCalculator.calculate(setup, input, direction, filters.sizeMultiplier());
            if (plan == null) continue;

            List<ChecklistItem> cl = buildChecklist(filters, setup, input);
            int score = (int) cl.stream().filter(c -> c.status() == ChecklistStatus.PASS).count();

            SetupCandidate withRrAndScore = new SetupCandidate(
                setup.type(), setup.zoneName(), setup.zoneHigh(), setup.zoneLow(), setup.zoneMid(),
                setup.distanceFromPrice(), setup.priceInZone(), setup.reactionVisible(),
                setup.orderFlowConfirms(), plan.rrRatio(), score
            );
            evaluated.add(new EvalEntry(withRrAndScore, plan, cl, score));
        }

        if (evaluated.isEmpty()) {
            return noSetupResult(filters);
        }

        // Step 4: Sort by priority (priceInZone > score > R:R > distance)
        evaluated.sort(Comparator
            .comparing((EvalEntry e) -> e.candidate().priceInZone() ? 0 : 1)
            .thenComparing(e -> -e.candidate().checklistScore())
            .thenComparing(e -> -e.candidate().rrRatio())
            .thenComparing(e -> e.candidate().distanceFromPrice()));

        // Step 5: Reuse the already-computed plan + checklist for the best entry
        EvalEntry bestEntry = evaluated.get(0);
        SetupCandidate best = bestEntry.candidate();
        PlaybookPlan plan = bestEntry.plan();
        List<ChecklistItem> checklist = bestEntry.checklist();
        int checklistScore = bestEntry.score();

        String verdict = buildVerdict(direction, best, checklistScore, filters);

        List<SetupCandidate> enriched = evaluated.stream()
            .map(EvalEntry::candidate).toList();

        return new PlaybookEvaluation(
            filters, enriched, best, plan, checklist, checklistScore, verdict, Instant.now()
        );
    }

    FilterResult evaluateFilters(PlaybookInput input) {
        // Filter 1: Bias aligned
        String swingBias = input.swingBias();
        boolean biasAligned = swingBias != null && !swingBias.isBlank()
            && ("BULLISH".equalsIgnoreCase(swingBias) || "BEARISH".equalsIgnoreCase(swingBias));
        Direction direction = "BULLISH".equalsIgnoreCase(swingBias) ? Direction.LONG : Direction.SHORT;

        // Filter 2: Structure clean
        int valid = 0, fake = 0, total = 0;
        for (SmcBreak brk : input.recentBreaks()) {
            total++;
            if (brk.breakConfidenceScore() != null) {
                if (brk.breakConfidenceScore() >= 0.75) valid++;
                else if (brk.breakConfidenceScore() < 0.60) fake++;
                else valid++; // mid-range = acceptable
            } else {
                valid++; // null score = no OF data, assume OK
            }
        }
        boolean structureClean = total == 0 || (double) valid / total >= 0.6;
        double sizeMultiplier = structureClean ? 1.0 : 0.5;

        // Filter 3: VA position
        VaPosition vaPos;
        if (input.lastPrice() != null && input.valueAreaLow() != null
                && input.lastPrice().doubleValue() < input.valueAreaLow()) {
            vaPos = VaPosition.BELOW_VA;
        } else if (input.lastPrice() != null && input.valueAreaHigh() != null
                && input.lastPrice().doubleValue() > input.valueAreaHigh()) {
            vaPos = VaPosition.ABOVE_VA;
        } else {
            vaPos = VaPosition.INSIDE_VA;
        }
        boolean vaOk = (direction == Direction.LONG && vaPos != VaPosition.ABOVE_VA)
                     || (direction == Direction.SHORT && vaPos != VaPosition.BELOW_VA);

        return new FilterResult(
            biasAligned, swingBias, direction,
            structureClean, valid, fake, total, sizeMultiplier,
            vaOk, vaPos,
            biasAligned && vaOk
        );
    }

    List<ChecklistItem> buildChecklist(FilterResult filters, SetupCandidate setup, PlaybookInput input) {
        List<ChecklistItem> items = new ArrayList<>();

        // 1. Bias aligned
        items.add(new ChecklistItem(1, "Bias aligned",
            filters.biasAligned() ? ChecklistStatus.PASS : ChecklistStatus.FAIL,
            filters.swingBias() + " -> " + filters.tradeDirection()));

        // 2. Setup identified
        items.add(new ChecklistItem(2, "Setup identified",
            ChecklistStatus.PASS,
            setup.type().name() + " — " + setup.zoneName()));

        // 3. Zone clear
        items.add(new ChecklistItem(3, "Zone clear",
            ChecklistStatus.PASS,
            setup.zoneLow() + " - " + setup.zoneHigh()));

        // 4. Price in zone
        items.add(new ChecklistItem(4, "Price in zone",
            setup.priceInZone() ? ChecklistStatus.PASS : ChecklistStatus.WAITING,
            setup.priceInZone() ? "In zone" : String.format("Distance: %.2f pts", setup.distanceFromPrice())));

        // 5. Reaction visible
        items.add(new ChecklistItem(5, "Reaction visible",
            setup.reactionVisible() ? ChecklistStatus.PASS : ChecklistStatus.WAITING,
            setup.reactionVisible() ? "Rejection detected" : "Waiting for confirmation"));

        // 6. Order flow confirms
        String ofDetail;
        ChecklistStatus ofStatus;
        if (input.deltaFlowBias() == null || input.deltaFlowBias().isBlank()) {
            ofStatus = ChecklistStatus.FAIL;
            ofDetail = "No order flow data";
        } else if (setup.orderFlowConfirms()) {
            ofStatus = ChecklistStatus.PASS;
            ofDetail = "Delta: " + input.deltaFlowBias();
        } else {
            ofStatus = ChecklistStatus.FAIL;
            ofDetail = "Delta neutral";
        }
        items.add(new ChecklistItem(6, "Order flow confirms", ofStatus, ofDetail));

        // 7. R:R >= 2:1
        items.add(new ChecklistItem(7, "R:R >= 2:1",
            setup.rrRatio() >= 2.0 ? ChecklistStatus.PASS : ChecklistStatus.FAIL,
            String.format("R:R %.1f:1", setup.rrRatio())));

        return items;
    }

    private String buildVerdict(Direction direction, SetupCandidate best,
                                int score, FilterResult filters) {
        String action = direction.name();
        String setup = best.type().name().replace("_", " ");
        String size = filters.sizeMultiplier() < 1.0 ? "half-size" : "full-size";

        if (score >= 6) return action + " — " + setup + " — " + score + "/7 — " + size;
        if (score >= 4) return action + " — " + setup + " — " + score + "/7 — WAIT for confirmation";
        return "NO TRADE — " + score + "/7 — insufficient confluence";
    }

    private PlaybookEvaluation noSetupResult(FilterResult filters) {
        String verdict = !filters.biasAligned()
            ? "NO TRADE — No clear bias"
            : "NO TRADE — No setup detected";
        return new PlaybookEvaluation(
            filters, List.of(), null, null, List.of(), 0, verdict, Instant.now()
        );
    }
}

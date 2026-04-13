package com.riskdesk.domain.engine.playbook.calculator;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SmcFvg;
import com.riskdesk.domain.engine.playbook.model.SmcOrderBlock;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes mechanical Entry / SL / TP based on the detected setup and
 * surrounding SMC zones. Pure calculation -- no AI, no persistence.
 */
public class MechanicalPlanCalculator {

    public PlaybookPlan calculate(SetupCandidate setup, PlaybookInput input,
                                  Direction direction, double sizeMultiplier) {
        BigDecimal entry;
        BigDecimal sl;
        String slRationale;
        BigDecimal atrMargin = input.atr().multiply(BigDecimal.valueOf(0.3));

        switch (setup.type()) {
            case ZONE_RETEST -> {
                entry = setup.zoneMid();
                sl = direction == Direction.LONG
                    ? setup.zoneLow().subtract(atrMargin)
                    : setup.zoneHigh().add(atrMargin);
                slRationale = "Below/above zone " + setup.zoneName() + " + ATR margin";
            }
            case LIQUIDITY_SWEEP -> {
                entry = input.lastPrice();
                BigDecimal sweepMargin = input.atr().multiply(BigDecimal.valueOf(0.5));
                sl = direction == Direction.LONG
                    ? setup.zoneLow().subtract(sweepMargin)
                    : setup.zoneHigh().add(sweepMargin);
                slRationale = "Below/above sweep wick + ATR margin";
            }
            case BREAK_RETEST -> {
                entry = setup.zoneMid();
                sl = direction == Direction.LONG
                    ? findNearestSwingExtreme(input, Direction.SHORT).subtract(atrMargin)
                    : findNearestSwingExtreme(input, Direction.LONG).add(atrMargin);
                slRationale = "Below/above last swing before BOS";
            }
            default -> {
                return null;
            }
        }

        BigDecimal risk = entry.subtract(sl).abs();
        if (risk.compareTo(BigDecimal.ZERO) <= 0) return null;

        // TP1 = first obstacle in trade direction
        BigDecimal tp1 = findFirstObstacle(input, direction, entry);
        String tp1Rationale;
        if (tp1 != null) {
            tp1Rationale = "First opposing obstacle";
        } else {
            // Fallback: 2:1 R:R
            tp1 = direction == Direction.LONG
                ? entry.add(risk.multiply(BigDecimal.TWO))
                : entry.subtract(risk.multiply(BigDecimal.TWO));
            tp1Rationale = "Default 2:1 R:R (no obstacle found)";
        }

        // TP2 = swing high/low
        BigDecimal tp2 = direction == Direction.LONG ? input.swingHigh() : input.swingLow();

        BigDecimal reward1 = tp1.subtract(entry).abs();
        double rr = reward1.doubleValue() / risk.doubleValue();

        double finalSize = sizeMultiplier * (rr >= 2.0 ? 0.01 : 0.005);

        return new PlaybookPlan(entry, sl, tp1, tp2, rr, finalSize, slRationale, tp1Rationale);
    }

    private BigDecimal findFirstObstacle(PlaybookInput input, Direction direction, BigDecimal entry) {
        BigDecimal nearest = null;
        double nearestDist = Double.MAX_VALUE;

        // Check opposing OBs
        for (SmcOrderBlock ob : input.activeOrderBlocks()) {
            boolean opposing = (direction == Direction.LONG && "BEARISH".equalsIgnoreCase(ob.type()))
                            || (direction == Direction.SHORT && "BULLISH".equalsIgnoreCase(ob.type()));
            if (!opposing || ob.mid() == null) continue;

            boolean ahead = direction == Direction.LONG
                ? ob.mid().compareTo(entry) > 0
                : ob.mid().compareTo(entry) < 0;
            if (!ahead) continue;

            double dist = ob.mid().subtract(entry).abs().doubleValue();
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = ob.mid();
            }
        }

        // Check opposing FVGs
        for (SmcFvg fvg : input.activeFairValueGaps()) {
            boolean opposing = (direction == Direction.LONG && "BEARISH".equalsIgnoreCase(fvg.bias()))
                            || (direction == Direction.SHORT && "BULLISH".equalsIgnoreCase(fvg.bias()));
            if (!opposing) continue;

            BigDecimal mid = fvg.top().add(fvg.bottom()).divide(BigDecimal.TWO, 6, RoundingMode.HALF_UP);
            boolean ahead = direction == Direction.LONG
                ? mid.compareTo(entry) > 0
                : mid.compareTo(entry) < 0;
            if (!ahead) continue;

            double dist = mid.subtract(entry).abs().doubleValue();
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = mid;
            }
        }

        return nearest;
    }

    private BigDecimal findNearestSwingExtreme(PlaybookInput input, Direction forDirection) {
        // For LONG SL: find the swing low. For SHORT SL: find the swing high.
        if (forDirection == Direction.SHORT) {
            return input.swingLow() != null ? input.swingLow() : input.lastPrice();
        } else {
            return input.swingHigh() != null ? input.swingHigh() : input.lastPrice();
        }
    }
}

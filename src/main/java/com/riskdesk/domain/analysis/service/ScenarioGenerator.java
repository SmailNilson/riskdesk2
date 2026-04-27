package com.riskdesk.domain.analysis.service;

import com.riskdesk.domain.analysis.model.Direction;
import com.riskdesk.domain.analysis.model.DirectionalBias;
import com.riskdesk.domain.analysis.model.LiveAnalysisSnapshot;
import com.riskdesk.domain.analysis.model.SmcContext;
import com.riskdesk.domain.analysis.model.TradeScenario;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure-domain scenario builder: takes a snapshot + bias and emits 2-3 weighted
 * trade scenarios with mechanical entry/SL/TP. The probabilities sum to 1.0.
 * <p>
 * Mechanical rules:
 * <ul>
 *   <li>Entry: nearest aligned OB or FVG bound; falls back to current price.</li>
 *   <li>SL: beyond strong high/low (the structural invalidation).</li>
 *   <li>TP1: equilibrium; TP2: opposite zone bound.</li>
 *   <li>R:R ≥ 1.0 ; setups below that ratio are dropped.</li>
 * </ul>
 */
public final class ScenarioGenerator {

    private static final double MIN_REWARD_RISK = 1.0;

    public List<TradeScenario> generate(LiveAnalysisSnapshot snap, DirectionalBias bias) {
        List<TradeScenario> raw = new ArrayList<>();

        Optional<TradeScenario> primary = bias.isStandAside()
            ? Optional.empty()
            : buildPrimary(snap, bias);
        primary.ifPresent(raw::add);

        Optional<TradeScenario> reversal = buildReversal(snap, bias);
        reversal.ifPresent(raw::add);

        TradeScenario range = buildRange(snap, bias);
        raw.add(range);

        return assignProbabilities(raw, bias);
    }

    private Optional<TradeScenario> buildPrimary(LiveAnalysisSnapshot snap, DirectionalBias bias) {
        SmcContext smc = snap.smc();
        Instrument inst = snap.instrument();
        BigDecimal price = snap.currentPrice();
        if (price == null || smc == null) return Optional.empty();

        BigDecimal entry, sl, tp1, tp2;
        if (bias.primary() == Direction.LONG) {
            entry = bestLongEntry(smc, price);
            sl = belowStrongLow(smc, entry, inst);
            tp1 = nearestUpwardLiquidity(smc, entry);
            tp2 = farUpwardLiquidity(smc, entry, tp1);
        } else { // SHORT
            entry = bestShortEntry(smc, price);
            sl = aboveStrongHigh(smc, entry, inst);
            tp1 = nearestDownwardLiquidity(smc, entry);
            tp2 = farDownwardLiquidity(smc, entry, tp1);
        }
        if (entry == null || sl == null || tp1 == null) return Optional.empty();
        double rr = rewardRisk(entry, sl, tp1, bias.primary());
        if (rr < MIN_REWARD_RISK) return Optional.empty();

        return Optional.of(new TradeScenario("Continuation", 0.0, bias.primary(),
            entry, sl, tp1, tp2 != null ? tp2 : tp1, rr,
            "Setup engages on retest of " + entry + " with bias confirmed",
            "Invalidation if price closes beyond " + sl));
    }

    private Optional<TradeScenario> buildReversal(LiveAnalysisSnapshot snap, DirectionalBias bias) {
        // Mirror direction with reduced confidence
        Direction inverse = bias.primary().opposite();
        if (inverse == Direction.NEUTRAL) {
            // bias was NEUTRAL → derive reversal from structure heuristic
            inverse = (snap.smc() != null && "PREMIUM".equals(snap.smc().currentZone()))
                ? Direction.SHORT : Direction.LONG;
        }

        DirectionalBias inverseBias = new DirectionalBias(
            snap.instrument(), snap.timeframe(), snap.decisionTimestamp(),
            inverse, Math.max(0, 100 - bias.confidence()),
            bias.structure(), bias.orderFlow(), bias.momentum(),
            List.of(), List.of(), List.of(), null);

        Optional<TradeScenario> reversal = buildPrimary(snap, inverseBias);
        return reversal.map(s -> new TradeScenario("Reversal", 0.0, s.direction(),
            s.entry(), s.stopLoss(), s.takeProfit1(), s.takeProfit2(), s.rewardRiskRatio(),
            "Triggers if " + bias.primary() + " setup invalidates and price reclaims opposite side",
            "Invalidation if " + bias.primary() + " bias resumes"));
    }

    private TradeScenario buildRange(LiveAnalysisSnapshot snap, DirectionalBias bias) {
        return new TradeScenario("Range", 0.0, Direction.NEUTRAL,
            null, null, null, null, 0.0,
            "Price compresses inside current value area",
            "Invalidation on either zone boundary breakout");
    }

    private List<TradeScenario> assignProbabilities(List<TradeScenario> raw, DirectionalBias bias) {
        if (raw.isEmpty()) return raw;
        // Heuristic: confidence 80 → 0.7 / 0.20 / 0.10 ; confidence 40 → 0.45 / 0.30 / 0.25
        int conf = bias.confidence();
        double primaryProb = bias.isStandAside() ? 0.0 : Math.min(0.85, 0.30 + (conf / 200.0));
        double reversalProb;
        double rangeProb;

        boolean hasPrimary = raw.stream().anyMatch(s -> "Continuation".equals(s.name()));
        boolean hasReversal = raw.stream().anyMatch(s -> "Reversal".equals(s.name()));

        if (hasPrimary && hasReversal) {
            reversalProb = (1.0 - primaryProb) * 0.55;
            rangeProb    = (1.0 - primaryProb) * 0.45;
        } else if (hasPrimary) {
            reversalProb = 0.0;
            rangeProb    = 1.0 - primaryProb;
        } else if (hasReversal) {
            primaryProb = 0.0;
            reversalProb = 0.55;
            rangeProb    = 0.45;
        } else {
            primaryProb = 0.0;
            reversalProb = 0.0;
            rangeProb    = 1.0;
        }

        List<TradeScenario> out = new ArrayList<>(raw.size());
        for (var s : raw) {
            double prob = switch (s.name()) {
                case "Continuation" -> primaryProb;
                case "Reversal" -> reversalProb;
                default -> rangeProb;
            };
            out.add(new TradeScenario(s.name(), round(prob, 2), s.direction(),
                s.entry(), s.stopLoss(), s.takeProfit1(), s.takeProfit2(),
                s.rewardRiskRatio(), s.triggerCondition(), s.invalidation()));
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Mechanical level pickers
    // ---------------------------------------------------------------------

    private BigDecimal bestLongEntry(SmcContext smc, BigDecimal price) {
        // Closest bullish OB or FVG below the price
        double current = price.doubleValue();
        Double best = null;
        if (smc.activeOrderBlocks() != null) {
            for (var ob : smc.activeOrderBlocks()) {
                if (!"BULLISH".equals(ob.type())) continue;
                if (ob.high() < current) {
                    if (best == null || ob.high() > best) best = ob.high();
                }
            }
        }
        if (smc.activeFvgs() != null) {
            for (var fvg : smc.activeFvgs()) {
                if (!"BULLISH".equals(fvg.bias())) continue;
                if (fvg.top() < current) {
                    if (best == null || fvg.top() > best) best = fvg.top();
                }
            }
        }
        return best != null ? BigDecimal.valueOf(best) : price;
    }

    private BigDecimal bestShortEntry(SmcContext smc, BigDecimal price) {
        double current = price.doubleValue();
        Double best = null;
        if (smc.activeOrderBlocks() != null) {
            for (var ob : smc.activeOrderBlocks()) {
                if (!"BEARISH".equals(ob.type())) continue;
                if (ob.low() > current) {
                    if (best == null || ob.low() < best) best = ob.low();
                }
            }
        }
        if (smc.activeFvgs() != null) {
            for (var fvg : smc.activeFvgs()) {
                if (!"BEARISH".equals(fvg.bias())) continue;
                if (fvg.bottom() > current) {
                    if (best == null || fvg.bottom() < best) best = fvg.bottom();
                }
            }
        }
        return best != null ? BigDecimal.valueOf(best) : price;
    }

    private BigDecimal belowStrongLow(SmcContext smc, BigDecimal entry, Instrument inst) {
        Double sl = smc.strongLow();
        if (sl == null || sl >= entry.doubleValue()) {
            return entry.subtract(defaultStopBuffer(inst));
        }
        return BigDecimal.valueOf(sl).subtract(tickSize(inst));
    }

    private BigDecimal aboveStrongHigh(SmcContext smc, BigDecimal entry, Instrument inst) {
        Double sh = smc.strongHigh();
        if (sh == null || sh <= entry.doubleValue()) {
            return entry.add(defaultStopBuffer(inst));
        }
        return BigDecimal.valueOf(sh).add(tickSize(inst));
    }

    private BigDecimal nearestUpwardLiquidity(SmcContext smc, BigDecimal entry) {
        if (smc.equilibriumLevel() != null && smc.equilibriumLevel() > entry.doubleValue()) {
            return BigDecimal.valueOf(smc.equilibriumLevel());
        }
        if (smc.weakHigh() != null && smc.weakHigh() > entry.doubleValue()) {
            return BigDecimal.valueOf(smc.weakHigh());
        }
        return null;
    }

    private BigDecimal farUpwardLiquidity(SmcContext smc, BigDecimal entry, BigDecimal tp1) {
        if (smc.premiumZoneTop() != null && smc.premiumZoneTop() > tp1.doubleValue()) {
            return BigDecimal.valueOf(smc.premiumZoneTop());
        }
        return null;
    }

    private BigDecimal nearestDownwardLiquidity(SmcContext smc, BigDecimal entry) {
        if (smc.equilibriumLevel() != null && smc.equilibriumLevel() < entry.doubleValue()) {
            return BigDecimal.valueOf(smc.equilibriumLevel());
        }
        if (smc.weakLow() != null && smc.weakLow() < entry.doubleValue()) {
            return BigDecimal.valueOf(smc.weakLow());
        }
        return null;
    }

    private BigDecimal farDownwardLiquidity(SmcContext smc, BigDecimal entry, BigDecimal tp1) {
        if (smc.discountZoneBottom() != null && smc.discountZoneBottom() < tp1.doubleValue()) {
            return BigDecimal.valueOf(smc.discountZoneBottom());
        }
        return null;
    }

    private double rewardRisk(BigDecimal entry, BigDecimal sl, BigDecimal tp, Direction dir) {
        double e = entry.doubleValue(), s = sl.doubleValue(), t = tp.doubleValue();
        if (dir == Direction.LONG) {
            double risk = e - s;
            double reward = t - e;
            return risk <= 0 ? 0 : reward / risk;
        } else {
            double risk = s - e;
            double reward = e - t;
            return risk <= 0 ? 0 : reward / risk;
        }
    }

    private BigDecimal defaultStopBuffer(Instrument inst) {
        // Conservative ATR-style fallback when SMC level is unusable
        return BigDecimal.valueOf(inst.getTickSize().doubleValue() * 20);
    }

    private BigDecimal tickSize(Instrument inst) {
        return inst.getTickSize();
    }

    private static double round(double v, int decimals) {
        return BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }
}

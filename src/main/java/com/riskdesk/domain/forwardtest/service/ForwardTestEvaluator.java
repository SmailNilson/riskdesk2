package com.riskdesk.domain.forwardtest.service;

import com.riskdesk.domain.forwardtest.model.*;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Pure domain service: evaluates forward-test positions against 1-minute candles.
 * NO Spring, NO JPA, NO I/O — only business logic and state transitions.
 *
 * <p>Handles: entry detection (Leg 1 + Leg 2), SL/TP resolution, missed moves,
 * TTL expiration, gap handling, slippage simulation, and adverse/favorable excursion tracking.</p>
 */
public class ForwardTestEvaluator {

    private final ForwardTestConfig config;

    public ForwardTestEvaluator(ForwardTestConfig config) {
        this.config = config;
    }

    public ForwardTestEvaluator() {
        this(ForwardTestConfig.defaults());
    }

    /**
     * Evaluates a position against an ordered list of 1-minute candles.
     * Returns the updated position after processing all candles.
     * Candles must be sorted chronologically.
     */
    public ForwardTestPosition evaluate(ForwardTestPosition position, List<Candle> candles) {
        ForwardTestPosition current = position;
        for (Candle candle : candles) {
            if (current.status().isTerminal()) break;

            if (current.expiresAt() != null && !candle.getTimestamp().isBefore(current.expiresAt())) {
                BigDecimal pnl = computePartialPnl(current, candle.getOpen());
                BigDecimal commission = computeCommission(current);
                current = current.withResolution(ForwardTestStatus.EXPIRED, candle.getOpen(),
                        pnl, commission, netPnl(pnl, commission), candle.getTimestamp());
                break;
            }

            current = switch (current.status()) {
                case PENDING_LEG1 -> evaluatePending(current, candle);
                case LEG1_FILLED  -> evaluateActive(current, candle);
                case FULLY_FILLED -> evaluateActive(current, candle);
                default           -> current;
            };
        }
        return current;
    }

    /**
     * Computes position sizing: how many contracts for a given risk budget.
     *
     * @param accountBalance  current account balance
     * @param instrument      the instrument being traded
     * @param entryPrice      planned entry price
     * @param stopLoss        planned stop-loss price
     * @return total number of contracts (both legs combined), minimum 1
     */
    public int computeQuantity(BigDecimal accountBalance, Instrument instrument,
                               BigDecimal entryPrice, BigDecimal stopLoss) {
        BigDecimal riskBudget = accountBalance.multiply(config.riskPct());
        BigDecimal stopDistance = entryPrice.subtract(stopLoss).abs();
        if (stopDistance.signum() <= 0) return 1;

        BigDecimal ticksAtRisk = stopDistance.divide(instrument.getTickSize(), 0, RoundingMode.HALF_UP);
        BigDecimal riskPerContract = ticksAtRisk.multiply(instrument.getTickValue());
        if (riskPerContract.signum() <= 0) return 1;

        int qty = riskBudget.divide(riskPerContract, 0, RoundingMode.DOWN).intValue();
        return Math.max(qty, 1);
    }

    // ── PENDING_LEG1 evaluation ──────────────────────────────────────────────

    private ForwardTestPosition evaluatePending(ForwardTestPosition pos, Candle candle) {
        boolean isMissed = isMissedMove(pos, candle);
        if (isMissed) {
            return pos.withStatus(ForwardTestStatus.MISSED)
                    .withResolution(ForwardTestStatus.MISSED, null, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, candle.getTimestamp());
        }

        if (touchesEntry(pos.side(), pos.entryStandard(), candle)) {
            BigDecimal fillPrice = applySlippage(pos.entryStandard(), pos.side(), pos.instrument(), true);
            PositionLeg filledLeg1 = pos.leg1().fill(fillPrice, candle.getTimestamp(), config.slippageTicks());

            ForwardTestPosition activated = pos.withLeg1(filledLeg1)
                    .withActivation(candle.getTimestamp(), ForwardTestStatus.LEG1_FILLED);

            if (pos.hasDualLegs() && touchesEntry(pos.side(), pos.safeDeepEntry(), candle)) {
                BigDecimal fill2 = applySlippage(pos.safeDeepEntry(), pos.side(), pos.instrument(), true);
                PositionLeg filledLeg2 = pos.leg2().fill(fill2, candle.getTimestamp(), config.slippageTicks());
                activated = activated.withLeg2(filledLeg2).withStatus(ForwardTestStatus.FULLY_FILLED);
            }

            return evaluateActive(activated, candle);
        }
        return pos;
    }

    // ── Active position evaluation (LEG1_FILLED or FULLY_FILLED) ─────────────

    private ForwardTestPosition evaluateActive(ForwardTestPosition pos, Candle candle) {
        if (pos.status() == ForwardTestStatus.LEG1_FILLED && pos.hasDualLegs()
                && !pos.leg2().isFilled()
                && touchesEntry(pos.side(), pos.safeDeepEntry(), candle)) {
            BigDecimal fill2 = applySlippage(pos.safeDeepEntry(), pos.side(), pos.instrument(), true);
            PositionLeg filledLeg2 = pos.leg2().fill(fill2, candle.getTimestamp(), config.slippageTicks());
            pos = pos.withLeg2(filledLeg2).withStatus(ForwardTestStatus.FULLY_FILLED);
        }

        pos = trackExcursion(pos, candle);

        boolean hitsSl = touchesStop(pos, candle);
        boolean hitsTp = touchesTarget(pos, candle);

        if (hitsSl && hitsTp) {
            // Pessimistic: SL wins when both touched in same candle
            return resolveAsLoss(pos, candle);
        }
        if (hitsSl) {
            return resolveAsLoss(pos, candle);
        }
        if (hitsTp) {
            return resolveAsWin(pos, candle);
        }
        return pos;
    }

    // ── Resolution helpers ───────────────────────────────────────────────────

    private ForwardTestPosition resolveAsWin(ForwardTestPosition pos, Candle candle) {
        BigDecimal exit = pos.takeProfit();
        BigDecimal pnl = computePnl(pos, exit);
        BigDecimal commission = computeCommission(pos);
        return pos.withResolution(ForwardTestStatus.WIN, exit, pnl, commission,
                netPnl(pnl, commission), candle.getTimestamp());
    }

    private ForwardTestPosition resolveAsLoss(ForwardTestPosition pos, Candle candle) {
        // Gap handling: if candle opens beyond SL, use open price (worse fill)
        BigDecimal exit = gapAdjustedStop(pos, candle);
        BigDecimal pnl = computePnl(pos, exit);
        BigDecimal commission = computeCommission(pos);
        return pos.withResolution(ForwardTestStatus.LOSS, exit, pnl, commission,
                netPnl(pnl, commission), candle.getTimestamp());
    }

    // ── Price touch detection ────────────────────────────────────────────────

    private boolean touchesEntry(Side side, BigDecimal entryPrice, Candle candle) {
        if (entryPrice == null) return false;
        if (side == Side.LONG) {
            return candle.getLow().compareTo(entryPrice) <= 0;
        } else {
            return candle.getHigh().compareTo(entryPrice) >= 0;
        }
    }

    private boolean touchesStop(ForwardTestPosition pos, Candle candle) {
        if (pos.side() == Side.LONG) {
            return candle.getLow().compareTo(pos.stopLoss()) <= 0;
        } else {
            return candle.getHigh().compareTo(pos.stopLoss()) >= 0;
        }
    }

    private boolean touchesTarget(ForwardTestPosition pos, Candle candle) {
        if (pos.side() == Side.LONG) {
            return candle.getHigh().compareTo(pos.takeProfit()) >= 0;
        } else {
            return candle.getLow().compareTo(pos.takeProfit()) <= 0;
        }
    }

    /**
     * Missed move: TP reached without entry ever being touched.
     * For LONG: high >= TP and low > entry (never dipped to entry)
     * For SHORT: low <= TP and high < entry (never rallied to entry)
     */
    private boolean isMissedMove(ForwardTestPosition pos, Candle candle) {
        if (pos.side() == Side.LONG) {
            return candle.getHigh().compareTo(pos.takeProfit()) >= 0
                    && candle.getLow().compareTo(pos.entryStandard()) > 0;
        } else {
            return candle.getLow().compareTo(pos.takeProfit()) <= 0
                    && candle.getHigh().compareTo(pos.entryStandard()) < 0;
        }
    }

    // ── Slippage ─────────────────────────────────────────────────────────────

    /**
     * Applies simulated slippage in the adverse direction.
     * For entries: LONG gets worse (higher) fill, SHORT gets worse (lower) fill.
     */
    private BigDecimal applySlippage(BigDecimal price, Side side, Instrument instrument, boolean isEntry) {
        BigDecimal slip = instrument.getTickSize().multiply(BigDecimal.valueOf(config.slippageTicks()));
        if (isEntry) {
            return side == Side.LONG ? price.add(slip) : price.subtract(slip);
        } else {
            return side == Side.LONG ? price.subtract(slip) : price.add(slip);
        }
    }

    // ── Gap handling ─────────────────────────────────────────────────────────

    /**
     * If candle opens beyond SL (weekend gap, halt), use the worse price.
     * LONG: if open < SL, fill at open (worse). SHORT: if open > SL, fill at open (worse).
     */
    private BigDecimal gapAdjustedStop(ForwardTestPosition pos, Candle candle) {
        BigDecimal sl = pos.stopLoss();
        if (pos.side() == Side.LONG && candle.getOpen().compareTo(sl) < 0) {
            return candle.getOpen();
        }
        if (pos.side() == Side.SHORT && candle.getOpen().compareTo(sl) > 0) {
            return candle.getOpen();
        }
        return sl;
    }

    // ── P&L computation ──────────────────────────────────────────────────────

    private BigDecimal computePnl(ForwardTestPosition pos, BigDecimal exitPrice) {
        BigDecimal avgEntry = pos.averageEntry();
        if (avgEntry == null) return BigDecimal.ZERO;
        return pos.instrument().calculatePnL(avgEntry, exitPrice, pos.filledQuantity(), pos.side());
    }

    private BigDecimal computePartialPnl(ForwardTestPosition pos, BigDecimal currentPrice) {
        if (!pos.status().isActive()) return BigDecimal.ZERO;
        return computePnl(pos, currentPrice);
    }

    private BigDecimal computeCommission(ForwardTestPosition pos) {
        BigDecimal perSide = config.commissionFor(pos.instrument());
        int totalContracts = pos.filledQuantity();
        // Commission = per-side × contracts × 2 sides (entry + exit)
        return perSide.multiply(BigDecimal.valueOf(totalContracts))
                .multiply(BigDecimal.valueOf(2));
    }

    private BigDecimal netPnl(BigDecimal pnl, BigDecimal commission) {
        if (pnl == null) return null;
        return pnl.subtract(commission == null ? BigDecimal.ZERO : commission);
    }

    // ── Excursion tracking ───────────────────────────────────────────────────

    private ForwardTestPosition trackExcursion(ForwardTestPosition pos, Candle candle) {
        BigDecimal avgEntry = pos.averageEntry();
        if (avgEntry == null) return pos;

        BigDecimal adverse;
        BigDecimal favorable;
        if (pos.side() == Side.LONG) {
            adverse = avgEntry.subtract(candle.getLow()).max(BigDecimal.ZERO);
            favorable = candle.getHigh().subtract(avgEntry).max(BigDecimal.ZERO);
        } else {
            adverse = candle.getHigh().subtract(avgEntry).max(BigDecimal.ZERO);
            favorable = avgEntry.subtract(candle.getLow()).max(BigDecimal.ZERO);
        }
        return pos.withDrawdown(adverse, favorable);
    }
}

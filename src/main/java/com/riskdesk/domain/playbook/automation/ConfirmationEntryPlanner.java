package com.riskdesk.domain.playbook.automation;

import com.riskdesk.domain.shared.TradingSessionResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * Confirmation-style entry plans on SMC zones — the zone must prove it holds
 * before we trade it.
 *
 * <p>Instead of the legacy passive limit inside the zone (which fills while the
 * zone is being violated — adverse selection), the entry is a STOP order at the
 * zone exit: a LONG buys when price breaks out above {@code zoneHigh}, a SHORT
 * sells when price breaks down through {@code zoneLow}. A pending setup is
 * invalidated (cancelled) if price first breaks the far side of the zone by
 * 0.5×ATR — never trade the reclaim of a broken zone. Exits are ATR brackets
 * anchored on the trigger level: SL at 1.5×ATR, TP at 2.25×ATR (R:R 1.5).
 *
 * <p>Session gates are direction-specific: LONGs only during US RTH
 * (09:30–16:00 ET), SHORTs during the extended day window (08:00–17:00 ET).
 *
 * <p>Parameters come from the full-window replay backtest on repaired candle
 * data (PR #450, rounds 3–4; see docs/AI_HANDOFF.md): LONG champion n=296,
 * WR 45.6%, PF 1.30, 5/6 positive months; SHORT champion n=315, WR 47.0%,
 * PF 1.46. Validate forward in paper before arming live.
 */
public final class ConfirmationEntryPlanner {

    public static final int MIN_CHECKLIST_SCORE = 5;
    public static final BigDecimal STOP_ATR_MULTIPLE = new BigDecimal("1.5");
    public static final BigDecimal TARGET_ATR_MULTIPLE = new BigDecimal("2.25");
    public static final BigDecimal INVALIDATION_ATR_MULTIPLE = new BigDecimal("0.5");

    /** A ready-to-persist confirmation plan; entry is a STOP trigger, not a limit. */
    public record ConfirmationPlan(
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal invalidationPrice
    ) {
        /** Fixed by construction: TARGET_ATR_MULTIPLE / STOP_ATR_MULTIPLE. */
        public BigDecimal rrRatio() {
            return TARGET_ATR_MULTIPLE.divide(STOP_ATR_MULTIPLE, 4, RoundingMode.HALF_UP);
        }
    }

    private ConfirmationEntryPlanner() {
    }

    /**
     * Builds the confirmation plan, or empty when any gate fails:
     * score below {@link #MIN_CHECKLIST_SCORE}, missing/inverted zone bounds,
     * missing ATR, or decision time outside the direction's session window.
     */
    public static Optional<ConfirmationPlan> plan(String direction,
                                                  int checklistScore,
                                                  BigDecimal zoneHigh,
                                                  BigDecimal zoneLow,
                                                  BigDecimal atr,
                                                  Instant decisionTime) {
        if (direction == null || decisionTime == null) {
            return Optional.empty();
        }
        if (checklistScore < MIN_CHECKLIST_SCORE) {
            return Optional.empty();
        }
        if (zoneHigh == null || zoneLow == null || zoneHigh.compareTo(zoneLow) <= 0) {
            return Optional.empty();
        }
        if (atr == null || atr.signum() <= 0) {
            return Optional.empty();
        }
        boolean isShort = "SHORT".equalsIgnoreCase(direction);
        boolean inSession = isShort
            ? TradingSessionResolver.isWithinUsDayWindow(decisionTime)
            : TradingSessionResolver.isWithinRth(decisionTime);
        if (!inSession) {
            return Optional.empty();
        }

        BigDecimal slDistance = atr.multiply(STOP_ATR_MULTIPLE);
        BigDecimal tpDistance = atr.multiply(TARGET_ATR_MULTIPLE);
        BigDecimal invalidationDistance = atr.multiply(INVALIDATION_ATR_MULTIPLE);

        BigDecimal entry = isShort ? zoneLow : zoneHigh;
        BigDecimal stopLoss = isShort ? entry.add(slDistance) : entry.subtract(slDistance);
        BigDecimal takeProfit = isShort ? entry.subtract(tpDistance) : entry.add(tpDistance);
        BigDecimal invalidation = isShort
            ? zoneHigh.add(invalidationDistance)
            : zoneLow.subtract(invalidationDistance);

        int scale = Math.max(entry.scale(), 2);
        return Optional.of(new ConfirmationPlan(
            entry,
            stopLoss.setScale(scale, RoundingMode.HALF_UP),
            takeProfit.setScale(scale, RoundingMode.HALF_UP),
            invalidation.setScale(scale, RoundingMode.HALF_UP)
        ));
    }
}

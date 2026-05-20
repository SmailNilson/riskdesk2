package com.riskdesk.domain.quant.automation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pure domain decision emitted by {@link AutoArmEvaluator} when a snapshot
 * qualifies for auto-arming. Carries everything the application layer needs to
 * persist a {@code TradeExecutionRecord} without re-reading the snapshot.
 *
 * <p>Prices are kept as {@link BigDecimal} because they ultimately become
 * IBKR limit-order prices (which require tick-size precision). The evaluator
 * builds them from the snapshot's {@code Double} suggestions.</p>
 *
 * @param direction      LONG or SHORT — never NEUTRAL (auto-arm only fires on actionable directions)
 * @param entryPrice     limit entry price (un-normalized; the application layer rounds to tick)
 * @param stopLoss       virtual SL price
 * @param takeProfit1    primary take-profit
 * @param takeProfit2    extended take-profit (may be null if snapshot does not provide one)
 * @param sizePercent    fraction of account to risk on this trade (0..1)
 * @param decisionAt     when the decision was made (UTC)
 * @param expiresAt      when the decision auto-expires if not fired or auto-submitted
 * @param reasoning      human-readable explanation surfaced in the UI badge
 */
public record AutoArmDecision(
    AutoArmDirection direction,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    double sizePercent,
    Instant decisionAt,
    Instant expiresAt,
    String reasoning
) {
    public AutoArmDecision {
        if (direction == null) throw new IllegalArgumentException("direction is required");
        if (entryPrice == null) throw new IllegalArgumentException("entryPrice is required");
        if (stopLoss == null) throw new IllegalArgumentException("stopLoss is required");
        if (takeProfit1 == null) throw new IllegalArgumentException("takeProfit1 is required");
        if (decisionAt == null) throw new IllegalArgumentException("decisionAt is required");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
        if (sizePercent <= 0.0 || sizePercent > 1.0) {
            throw new IllegalArgumentException("sizePercent must be in (0, 1]");
        }
        if (!expiresAt.isAfter(decisionAt)) {
            throw new IllegalArgumentException("expiresAt must be after decisionAt");
        }
        reasoning = reasoning == null ? "" : reasoning;
    }
}

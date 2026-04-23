package com.riskdesk.domain.trading.vo;

import com.riskdesk.domain.shared.vo.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value object representing a risk/reward ratio computed in dollars.
 *
 * <p>Inputs are {@link Money} (dollar-denominated) rather than raw price deltas so
 * that the contract makes unit-safety explicit: callers must convert price points
 * into a dollar amount via the instrument's point-value before calling. This
 * prevents silent mistakes where a raw price-delta ratio (points / points) is
 * mislabeled as a dollar ratio in downstream reporting.
 *
 * <p><b>PR-13 · No silent null, no silently-broken inputs.</b> The previous
 * contract returned {@code null} for three incompatible failure modes:
 * <ul>
 *   <li>a caller bug (null argument) — should fail fast, not silently</li>
 *   <li>zero risk — mathematically undefined (divide by zero)</li>
 *   <li>zero reward — valid ratio of 0, but also returned null, indistinguishable
 *       from the other two</li>
 * </ul>
 *
 * <p>The new contract:
 * <ul>
 *   <li><b>Null inputs</b> throw {@link NullPointerException} with the offending
 *       argument name — that was always a caller bug and should surface loudly.</li>
 *   <li><b>Non-positive risk or reward</b> returns a sentinel "undefined" ratio
 *       with a human-readable {@code reason()}. Risk must be strictly positive
 *       (zero is a divide-by-zero; negative is a wrong-signed plan). Reward must
 *       be strictly positive (zero means no profit potential; negative means the
 *       TP is on the wrong side of entry — an inverted, nonsensical plan).</li>
 *   <li><b>Valid positive inputs</b> produce a scale-2 ratio equal to
 *       {@code reward / risk}.</li>
 * </ul>
 */
public final class RiskRewardRatio {

    private final BigDecimal value;
    private final String reason;

    private RiskRewardRatio(BigDecimal value, String reason) {
        this.value = value;
        this.reason = reason;
    }

    /**
     * Calculates the risk/reward ratio as {@code reward / risk}, both expressed
     * in dollars via {@link Money}.
     *
     * @param risk   dollar amount at risk (SL distance × point-value × contracts); must be non-null and strictly positive
     * @param reward dollar amount of potential profit (TP distance × point-value × contracts); must be non-null and strictly positive
     * @return a defined ratio when both inputs are positive; an undefined sentinel otherwise — never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static RiskRewardRatio calculate(Money risk, Money reward) {
        Objects.requireNonNull(risk, "risk must not be null");
        Objects.requireNonNull(reward, "reward must not be null");

        if (!risk.isPositive()) {
            return undefined("risk must be strictly positive, got " + risk);
        }
        if (!reward.isPositive()) {
            return undefined("reward must be strictly positive, got " + reward);
        }

        BigDecimal ratio = reward.amount().divide(risk.amount(), 2, RoundingMode.HALF_UP);
        return new RiskRewardRatio(ratio, null);
    }

    /**
     * Factory for the sentinel "can't compute" ratio. Callers should prefer
     * {@link #calculate(Money, Money)} which produces this automatically on
     * degenerate inputs; exposed publicly so downstream code that detects its
     * own degenerate setup (e.g. entry == SL before reaching this VO) can emit
     * a consistently-shaped result.
     */
    public static RiskRewardRatio undefined(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        return new RiskRewardRatio(null, reason);
    }

    /**
     * @return the ratio value (scale 2), or {@code null} when this is an
     *         undefined sentinel. Callers should check {@link #isDefined()}
     *         before using.
     */
    public BigDecimal value() {
        return value;
    }

    /**
     * @return an operator-facing explanation when this is an undefined sentinel;
     *         {@code null} when the ratio is defined.
     */
    public String reason() {
        return reason;
    }

    /** @return {@code true} when the ratio was successfully computed. */
    public boolean isDefined() {
        return value != null;
    }

    @Override
    public String toString() {
        return isDefined() ? value.toPlainString() : "UNDEFINED(" + reason + ")";
    }
}

package com.riskdesk.domain.trading.vo;

import com.riskdesk.domain.shared.vo.Money;

/**
 * Value object representing a P&L (Profit and Loss) result,
 * containing both unrealized and realized components.
 */
public record PnLResult(Money unrealized, Money realized) {

    /**
     * Returns the total P&L (unrealized + realized).
     */
    public Money total() {
        return unrealized.add(realized);
    }

    /**
     * Creates a PnLResult with only unrealized P&L (realized = 0).
     */
    public static PnLResult unrealized(Money unrealized) {
        return new PnLResult(unrealized, Money.ZERO);
    }

    /**
     * Creates a PnLResult with only realized P&L (unrealized = 0).
     */
    public static PnLResult realized(Money realized) {
        return new PnLResult(Money.ZERO, realized);
    }
}

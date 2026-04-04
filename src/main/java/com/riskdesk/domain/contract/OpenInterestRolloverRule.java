package com.riskdesk.domain.contract;

import com.riskdesk.domain.model.Instrument;

/**
 * Pure domain rule: recommends a contract rollover when the next month's
 * Open Interest exceeds the current month's Open Interest — indicating
 * that liquidity has migrated to the next contract.
 *
 * Stateless, no Spring/JPA/IBKR dependencies.
 */
public final class OpenInterestRolloverRule {

    private OpenInterestRolloverRule() {}

    public record OpenInterestSnapshot(String contractMonth, long openInterest) {}

    /**
     * Evaluates whether the active contract should be rolled.
     *
     * @return RECOMMEND_ROLL if next month OI strictly exceeds current month OI, HOLD otherwise.
     */
    public static RolloverRecommendation evaluate(Instrument instrument,
                                                   OpenInterestSnapshot current,
                                                   OpenInterestSnapshot next) {
        RolloverRecommendation.Action action =
            next.openInterest() > 0 && next.openInterest() > current.openInterest()
                ? RolloverRecommendation.Action.RECOMMEND_ROLL
                : RolloverRecommendation.Action.HOLD;

        return new RolloverRecommendation(
            instrument,
            current.contractMonth(),
            next.contractMonth(),
            current.openInterest(),
            next.openInterest(),
            action
        );
    }
}

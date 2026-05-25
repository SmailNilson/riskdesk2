package com.riskdesk.domain.playbook.automation;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PlaybookProfilePlanner {

    private static final BigDecimal HALF_R = new BigDecimal("0.5");
    private static final BigDecimal ONE_R = BigDecimal.ONE;

    private PlaybookProfilePlanner() {
    }

    public static PlaybookProfilePlan planFor(PlaybookDecision decision, PlaybookExecutionProfile requestedProfile) {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        PlaybookExecutionProfile profile = requestedProfile == null
            ? PlaybookExecutionProfile.LEGACY
            : requestedProfile;
        if (profile != PlaybookExecutionProfile.LEGACY && !profile.supports(decision)) {
            profile = PlaybookExecutionProfile.LEGACY;
        }

        return switch (profile) {
            case LEGACY -> new PlaybookProfilePlan(
                profile,
                decision.entryPrice(),
                decision.stopLoss(),
                decision.takeProfit1(),
                decision.rrRatio(),
                true);
            case MGC_10M_SCALP_0_5R -> riskMultiplePlan(decision, profile, HALF_R, true);
            case MGC_10M_NORMAL_1R_BENCHMARK -> riskMultiplePlan(decision, profile, ONE_R, false);
        };
    }

    private static PlaybookProfilePlan riskMultiplePlan(PlaybookDecision decision,
                                                        PlaybookExecutionProfile profile,
                                                        BigDecimal multiple,
                                                        boolean executable) {
        if (decision.entryPrice() == null || decision.stopLoss() == null) {
            return new PlaybookProfilePlan(profile, decision.entryPrice(), decision.stopLoss(), null, multiple, executable);
        }
        BigDecimal risk = decision.entryPrice().subtract(decision.stopLoss()).abs();
        BigDecimal reward = risk.multiply(multiple);
        BigDecimal takeProfit = isShort(decision)
            ? decision.entryPrice().subtract(reward)
            : decision.entryPrice().add(reward);
        return new PlaybookProfilePlan(
            profile,
            decision.entryPrice(),
            decision.stopLoss(),
            takeProfit.setScale(scale(decision.entryPrice()), RoundingMode.HALF_UP),
            multiple,
            executable);
    }

    private static boolean isShort(PlaybookDecision decision) {
        return "SHORT".equalsIgnoreCase(decision.direction());
    }

    private static int scale(BigDecimal value) {
        return Math.max(value == null ? 2 : value.scale(), 2);
    }
}

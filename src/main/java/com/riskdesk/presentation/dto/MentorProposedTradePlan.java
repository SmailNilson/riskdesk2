package com.riskdesk.presentation.dto;

public record MentorProposedTradePlan(
    Double entryPrice,
    Double stopLoss,
    Double takeProfit,
    Double rewardToRiskRatio,
    String rationale
) {
}

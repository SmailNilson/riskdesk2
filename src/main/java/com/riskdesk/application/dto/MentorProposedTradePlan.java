package com.riskdesk.application.dto;

public record MentorProposedTradePlan(
    Double entryPrice,
    Double stopLoss,
    Double takeProfit,
    Double rewardToRiskRatio,
    String rationale
) {
}

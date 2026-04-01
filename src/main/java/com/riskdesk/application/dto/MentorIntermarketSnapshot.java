package com.riskdesk.application.dto;

import com.riskdesk.domain.marketdata.model.FxComponentContribution;

import java.util.List;

public record MentorIntermarketSnapshot(
    Double dxyPctChange,
    String dxyTrend,
    List<FxComponentContribution> dxyComponentBreakdown,
    Double silverSi1PctChange,
    Double goldMgc1PctChange,
    Double platPl1PctChange,
    String metalsConvergenceStatus
) {
}

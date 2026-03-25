package com.riskdesk.presentation.dto;

public record MentorIntermarketSnapshot(
    Double dxyPctChange,
    Double silverSi1PctChange,
    Double goldMgc1PctChange,
    Double platPl1PctChange,
    String metalsConvergenceStatus
) {
}

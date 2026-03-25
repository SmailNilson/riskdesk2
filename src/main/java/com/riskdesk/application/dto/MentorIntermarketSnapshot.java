package com.riskdesk.application.dto;

public record MentorIntermarketSnapshot(
    Double dxyPctChange,
    Double silverSi1PctChange,
    Double goldMgc1PctChange,
    Double platPl1PctChange,
    String metalsConvergenceStatus
) {
}

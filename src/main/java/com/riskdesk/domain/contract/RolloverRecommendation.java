package com.riskdesk.domain.contract;

import com.riskdesk.domain.model.Instrument;

/**
 * Result of comparing Open Interest between the current and next contract month.
 */
public record RolloverRecommendation(
    Instrument instrument,
    String currentMonth,
    String nextMonth,
    long currentOI,
    long nextOI,
    Action action
) {
    public enum Action { HOLD, RECOMMEND_ROLL }
}

package com.riskdesk.domain.contract;

/**
 * Describes how close a futures contract is to its expiry date.
 *
 * Thresholds (configurable via RolloverDetectionService):
 *   STABLE   — more than 7 days to last trade date
 *   WARNING  — 4–7 days to last trade date  → trader should plan the roll
 *   CRITICAL — 1–3 days to last trade date  → roll is urgent
 */
public enum RolloverStatus {
    STABLE,
    WARNING,
    CRITICAL
}

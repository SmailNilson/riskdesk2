package com.riskdesk.application.dto;

/** Partial update of the marketable-execution policy — null fields keep their current value. */
public record MarketableSettingsUpdateRequest(Boolean closeEnabled, Boolean reverseOpenEnabled, Integer crossTicks) {
}

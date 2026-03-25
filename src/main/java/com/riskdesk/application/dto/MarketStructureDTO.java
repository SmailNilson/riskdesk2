package com.riskdesk.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MarketStructureDTO(
    @JsonProperty("trend_focus") String trendFocus,
    @JsonProperty("last_event") String lastEvent,
    @JsonProperty("last_event_price") Double lastEventPrice,
    @JsonProperty("notes") String notes
) {
}

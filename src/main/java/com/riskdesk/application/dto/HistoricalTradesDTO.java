package com.riskdesk.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record HistoricalTradesDTO(
    @JsonProperty("instrument") String instrument,
    @JsonProperty("trades") List<TradeDTO> trades
) {
}

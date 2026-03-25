package com.riskdesk.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TradeDTO(
    @JsonProperty("trade_id") String tradeId,
    @JsonProperty("instrument") String instrument,
    @JsonProperty("symbol_detected") String symbolDetected,
    @JsonProperty("timestamp") String timestamp,
    @JsonProperty("session") String session,
    @JsonProperty("timeframe") String timeframe,
    @JsonProperty("direction") String direction,
    @JsonProperty("entry_price") Double entryPrice,
    @JsonProperty("stop_loss") Double stopLoss,
    @JsonProperty("take_profit") Double takeProfit,
    @JsonProperty("market_order") Boolean marketOrder,
    @JsonProperty("analysis_mode") String analysisMode,
    @JsonProperty("ai_verdict") String aiVerdict,
    @JsonProperty("trade_quality") String tradeQuality,
    @JsonProperty("confidence_score") Integer confidenceScore,
    @JsonProperty("risk_reward") Double riskReward,
    @JsonProperty("market_structure") MarketStructureDTO marketStructure,
    @JsonProperty("reasons") List<String> reasons,
    @JsonProperty("outcome_if_known") String outcomeIfKnown,
    @JsonProperty("has_screenshot") Boolean hasScreenshot,
    @JsonProperty("raw_context_excerpt") String rawContextExcerpt,
    @JsonProperty("dedupe_key") String dedupeKey,
    @JsonProperty("notes") String notes
) {
}

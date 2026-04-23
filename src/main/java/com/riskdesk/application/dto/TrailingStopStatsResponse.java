package com.riskdesk.application.dto;

public record TrailingStopStatsResponse(
    String period,
    TrackStats fixedSLTP,
    TrackStats trailingStop,
    Improvement improvement
) {
    public record TrackStats(int trades, int wins, double winRate, double netPnl) {
    }

    public record Improvement(double winRateDelta, double pnlDelta) {
    }
}

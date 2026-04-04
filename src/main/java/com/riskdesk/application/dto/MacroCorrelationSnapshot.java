package com.riskdesk.application.dto;

import com.riskdesk.domain.marketdata.model.FxComponentContribution;

import java.util.List;

/**
 * Asset-class-aware macro correlation snapshot for the Mentor payload.
 * Fields are populated dynamically based on the instrument's asset class.
 */
public record MacroCorrelationSnapshot(
    // DXY — all asset classes
    Double dxyPctChange,
    String dxyTrend,
    List<FxComponentContribution> dxyComponentBreakdown,

    // Sector leader — METALS and EQUITY_INDEX
    String sectorLeaderSymbol,
    Double sectorLeaderPctChange,
    String sectorLeaderTrend,

    // VIX — EQUITY_INDEX only
    Double vixPctChange,

    // US 10Y yield — METALS and EQUITY_INDEX
    Double us10yYieldPctChange,

    // Convergence status
    String correlationAlignment,   // CONVERGENT, DIVERGENT, UNAVAILABLE
    String dataAvailability        // FULL, PARTIAL, DXY_ONLY
) {}

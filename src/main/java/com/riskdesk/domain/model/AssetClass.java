package com.riskdesk.domain.model;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Asset class taxonomy for per-class Mentor IA strategy differentiation.
 */
public enum AssetClass {

    METALS(
        EnumSet.of(CorrelationKind.DXY, CorrelationKind.US10Y, CorrelationKind.SECTOR_LEADER),
        "SI"  // Silver is sector leader for Gold
    ),
    ENERGY(
        EnumSet.of(CorrelationKind.DXY),
        null
    ),
    FOREX(
        EnumSet.of(CorrelationKind.DXY),
        null
    ),
    EQUITY_INDEX(
        EnumSet.of(CorrelationKind.DXY, CorrelationKind.VIX, CorrelationKind.US10Y, CorrelationKind.SECTOR_LEADER),
        "ES"  // ES is sector leader for NQ
    );

    private final Set<CorrelationKind> relevantCorrelations;
    private final String sectorLeaderSymbol;

    AssetClass(Set<CorrelationKind> relevantCorrelations, String sectorLeaderSymbol) {
        this.relevantCorrelations = relevantCorrelations;
        this.sectorLeaderSymbol = sectorLeaderSymbol;
    }

    /** Returns the set of macro correlations relevant for this asset class. */
    public Set<CorrelationKind> relevantCorrelations() {
        return relevantCorrelations;
    }

    /** Returns the sector leader symbol (e.g., SI for METALS, ES for EQUITY_INDEX), or empty. */
    public Optional<String> sectorLeaderSymbol() {
        return Optional.ofNullable(sectorLeaderSymbol);
    }

    /** Kinds of macro correlations used by the Mentor IA per asset class. */
    public enum CorrelationKind {
        DXY,
        VIX,
        US10Y,
        SECTOR_LEADER
    }
}

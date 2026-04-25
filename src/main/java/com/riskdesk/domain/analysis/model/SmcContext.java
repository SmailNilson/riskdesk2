package com.riskdesk.domain.analysis.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable SMC view at the decision timestamp. Defensive deep copies are
 * applied at construction so the engine cannot be mutated during scoring.
 */
public record SmcContext(
    String internalBias,                 // BULLISH / BEARISH / null
    String swingBias,
    String currentZone,                  // PREMIUM / DISCOUNT / EQUILIBRIUM
    Double equilibriumLevel,
    Double premiumZoneTop,
    Double discountZoneBottom,
    Map<String, String> multiResolutionBias, // keys: swing50, swing25, swing9, internal5, micro1
    Double strongHigh,
    Double weakHigh,
    Double strongLow,
    Double weakLow,
    String lastBreakType,
    List<ActiveOrderBlock> activeOrderBlocks,
    List<ActiveFairValueGap> activeFvgs,
    List<RecentBreak> recentBreaks
) {
    public SmcContext {
        multiResolutionBias = Map.copyOf(Objects.requireNonNullElse(multiResolutionBias, Map.of()));
        activeOrderBlocks   = List.copyOf(Objects.requireNonNullElse(activeOrderBlocks, List.of()));
        activeFvgs          = List.copyOf(Objects.requireNonNullElse(activeFvgs, List.of()));
        recentBreaks        = List.copyOf(Objects.requireNonNullElse(recentBreaks, List.of()));
    }

    public record ActiveOrderBlock(String type, double low, double high, double mid,
                                    double obLiveScore, boolean defended) {}

    public record ActiveFairValueGap(String bias, double bottom, double top, double quality) {}

    public record RecentBreak(String type, String trend, double level, double confidence,
                                boolean confirmed) {}
}

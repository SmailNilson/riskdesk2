package com.riskdesk.domain.analysis.model;

import java.util.HashMap;
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
        // PR #269 review fix: IndicatorSnapshotAdapter.smcAsOf builds the multi-
        // resolution bias map from nullable IndicatorService fields (swing50, …,
        // micro1 can all be null before structure is established on a fresh
        // contract). Map.copyOf(...) throws NullPointerException on null values,
        // so live analysis would crash with an NPE at construction time instead
        // of returning a verdict. Drop null-valued entries before the copy.
        multiResolutionBias = sanitizeMultiResolutionBias(multiResolutionBias);
        activeOrderBlocks   = List.copyOf(Objects.requireNonNullElse(activeOrderBlocks, List.of()));
        activeFvgs          = List.copyOf(Objects.requireNonNullElse(activeFvgs, List.of()));
        recentBreaks        = List.copyOf(Objects.requireNonNullElse(recentBreaks, List.of()));
    }

    private static Map<String, String> sanitizeMultiResolutionBias(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> filtered = new HashMap<>(raw.size());
        for (var entry : raw.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(filtered);
    }

    public record ActiveOrderBlock(String type, double low, double high, double mid,
                                    double obLiveScore, boolean defended) {}

    public record ActiveFairValueGap(String bias, double bottom, double top, double quality) {}

    public record RecentBreak(String type, String trend, double level, double confidence,
                                boolean confirmed) {}
}

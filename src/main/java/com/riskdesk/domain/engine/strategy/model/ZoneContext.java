package com.riskdesk.domain.engine.strategy.model;

import java.util.List;

/**
 * Layer 2 — the "What".
 *
 * <p>Lists of currently-active zones near the price. A "nearby" zone is one within
 * {@code 0.5 × ATR} of {@code MarketContext.lastPrice}; the builder is responsible
 * for filtering out far-away zones so agents don't have to re-evaluate proximity.
 *
 * <p>Lists are always non-null but may be empty. An empty list means "no zone of
 * this kind is nearby", NOT "no data". If we don't know, the builder reports an
 * empty list and a low-quality {@link TickDataQuality} on the trigger side; context
 * agents don't need to distinguish.
 */
public record ZoneContext(
    List<OrderBlockZone> activeOrderBlocks,
    List<FvgZone> activeFvgs,
    List<LiquidityLevel> nearbyLiquidity
) {
    public ZoneContext {
        activeOrderBlocks = activeOrderBlocks == null ? List.of() : List.copyOf(activeOrderBlocks);
        activeFvgs = activeFvgs == null ? List.of() : List.copyOf(activeFvgs);
        nearbyLiquidity = nearbyLiquidity == null ? List.of() : List.copyOf(nearbyLiquidity);
    }

    public static ZoneContext empty() {
        return new ZoneContext(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return activeOrderBlocks.isEmpty() && activeFvgs.isEmpty() && nearbyLiquidity.isEmpty();
    }
}

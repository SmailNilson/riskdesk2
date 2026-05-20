package com.riskdesk.domain.quant.structure;

import java.util.List;
import java.util.Map;

/**
 * Minimal projection of the values exposed by
 * {@code /api/indicators/{instr}/5m} that are consumed by the
 * {@link StructuralFilterEvaluator}.
 *
 * <p>This is a deliberate narrow domain view: only the fields that drive a
 * BLOCK or WARNING decision are carried. Adapters in the infrastructure /
 * application layers translate the rich {@code IndicatorSnapshot} DTO into
 * this record so the domain stays free of presentation/JPA types.</p>
 *
 * <p>All fields may be {@code null} — the evaluator must defend against
 * missing data (e.g. a freshly started instrument with no indicators yet).</p>
 *
 * <p>Since the LONG-symmetry slice the projection also carries
 * {@link #vwapUpperBand} and {@link #equalHighs}, used by the LONG warnings
 * (mirror of {@link #vwapLowerBand} / {@link #equalLows} for SHORT).</p>
 *
 * @param vwap                   VWAP value, {@code null} when unavailable
 * @param vwapLowerBand          VWAP lower band ({@code vwap - σ})
 * @param vwapUpperBand          VWAP upper band ({@code vwap + σ})
 * @param bbPct                  Bollinger position [0, 1] — &lt; 0.15 oversold, &gt; 0.85 overbought
 * @param cmf                    Chaikin Money Flow [-1, 1]
 * @param currentZone            Premium / Discount / Equilibrium zone label
 * @param swingBias              swing market structure bias
 * @param lastInternalBreakType  last internal BOS/CHoCH type, e.g. "CHOCH_BEARISH"
 * @param multiResolutionBias    nested swing bias map (5 lookback scales)
 * @param activeOrderBlocks      active order blocks (BULLISH / BEARISH)
 * @param equalLows              equal-lows liquidity pools near the price
 * @param equalHighs             equal-highs liquidity pools near the price (LONG mirror)
 */
public record IndicatorsSnapshot(
    Double vwap,
    Double vwapLowerBand,
    Double vwapUpperBand,
    Double bbPct,
    Double cmf,
    String currentZone,
    String swingBias,
    String lastInternalBreakType,
    Map<String, String> multiResolutionBias,
    List<OrderBlockView> activeOrderBlocks,
    List<EqualLowView> equalLows,
    List<EqualHighView> equalHighs
) {
    public IndicatorsSnapshot {
        multiResolutionBias = multiResolutionBias == null ? Map.of() : Map.copyOf(multiResolutionBias);
        activeOrderBlocks  = activeOrderBlocks  == null ? List.of() : List.copyOf(activeOrderBlocks);
        equalLows          = equalLows          == null ? List.of() : List.copyOf(equalLows);
        equalHighs         = equalHighs         == null ? List.of() : List.copyOf(equalHighs);
    }

    /**
     * Backward-compat ctor (pre-LONG-symmetry shape — no vwapUpperBand, no
     * equalHighs). Defaults the new fields to {@code null} / empty so old
     * callers compile and run unchanged.
     */
    public IndicatorsSnapshot(
        Double vwap,
        Double vwapLowerBand,
        Double bbPct,
        Double cmf,
        String currentZone,
        String swingBias,
        String lastInternalBreakType,
        Map<String, String> multiResolutionBias,
        List<OrderBlockView> activeOrderBlocks,
        List<EqualLowView> equalLows
    ) {
        this(vwap, vwapLowerBand, null, bbPct, cmf, currentZone, swingBias, lastInternalBreakType,
             multiResolutionBias, activeOrderBlocks, equalLows, List.of());
    }

    /** Single active order block — only the dimensions used by the evaluator. */
    public record OrderBlockView(String type, String status, Double low, Double high) {}

    /** Single equal-lows liquidity pool. */
    public record EqualLowView(Double price, int touchCount) {}

    /** Single equal-highs liquidity pool — LONG mirror of {@link EqualLowView}. */
    public record EqualHighView(Double price, int touchCount) {}
}

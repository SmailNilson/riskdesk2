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
 * @param vwap                   VWAP value, {@code null} when unavailable
 * @param vwapLowerBand          VWAP lower band ({@code vwap - σ})
 * @param bbPct                  Bollinger position [0, 1] — &lt; 0.15 is oversold
 * @param cmf                    Chaikin Money Flow [-1, 1]
 * @param currentZone            Premium / Discount / Equilibrium zone label
 * @param swingBias              swing market structure bias
 * @param lastInternalBreakType  last internal BOS/CHoCH type, e.g. "CHOCH_BEARISH"
 * @param multiResolutionBias    nested swing bias map (5 lookback scales)
 * @param activeOrderBlocks      active order blocks (BULLISH / BEARISH)
 * @param equalLows              equal-lows liquidity pools near the price
 */
public record IndicatorsSnapshot(
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
    public IndicatorsSnapshot {
        multiResolutionBias = multiResolutionBias == null ? Map.of() : Map.copyOf(multiResolutionBias);
        activeOrderBlocks  = activeOrderBlocks  == null ? List.of() : List.copyOf(activeOrderBlocks);
        equalLows          = equalLows          == null ? List.of() : List.copyOf(equalLows);
    }

    /** Single active order block — only the dimensions used by the evaluator. */
    public record OrderBlockView(String type, String status, Double low, Double high) {}

    /** Single equal-lows liquidity pool. */
    public record EqualLowView(Double price, int touchCount) {}
}

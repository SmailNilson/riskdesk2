package com.riskdesk.domain.engine.indicators;

/**
 * Central source of truth for RSI interpretation thresholds used by
 * {@code AgentContext.MomentumSnapshot} and other momentum consumers.
 *
 * <p>These are <em>semantic interpretation bands</em> — not per-indicator
 * Wilder tuning parameters. For the RSI calculation itself, see
 * {@link RSIIndicator} (which has its own oversold/neutral/overbought fields
 * used to emit the OVERSOLD/WEAK/NEUTRAL/OVERBOUGHT signal strings).
 *
 * <p>The thresholds form a coherent band:
 * <pre>
 *   OVERSOLD (30) &lt; CONFIRM_LONG_MIN (40) &lt; BULLISH_DIVERGENCE_MIN (45)
 *     &lt; BEARISH_DIVERGENCE_MAX (55) &lt; CONFIRM_SHORT_MAX (60) &lt; OVERBOUGHT (70)
 * </pre>
 *
 * <ul>
 *   <li><b>30 / 70</b>: classical Wilder overbought / oversold cut-offs.
 *       Anchor the outer edges of both confirmation and divergence bands.</li>
 *   <li><b>40 / 60</b>: "neutral range" boundaries. RSI values inside
 *       [40, 60] are directionless; values outside carry directional
 *       momentum weight. Used for trade-direction confirmation.</li>
 *   <li><b>45 / 55</b>: divergence leniency cut-offs. When price swings
 *       bullish but RSI drops below 55 (or bearish with RSI above 45), the
 *       momentum is failing to confirm the structural move — a classic
 *       divergence warning.</li>
 * </ul>
 *
 * <p>Before centralisation, these numbers were scattered as magic literals
 * across four methods in {@code MomentumSnapshot} (lines 136, 141, 165, 170).
 * PR-6 unifies them here so audits and back-tests can tune a single source.
 */
public final class MomentumThresholds {

    // ── Classical Wilder overbought / oversold ──────────────────────────

    /** Below this level, RSI is in classical oversold territory. */
    public static final double RSI_OVERSOLD = 30.0;

    /** Above this level, RSI is in classical overbought territory. */
    public static final double RSI_OVERBOUGHT = 70.0;

    // ── Directional confirmation range ──────────────────────────────────

    /**
     * Lower bound of the "neutral" RSI range. A LONG setup is confirmed
     * when RSI is strictly above this value (and below {@link #RSI_OVERBOUGHT}).
     */
    public static final double RSI_CONFIRM_LONG_MIN = 40.0;

    /**
     * Upper bound for a LONG confirmation — mirrors {@link #RSI_OVERBOUGHT}.
     * Above this, RSI is overheated and confirmation is revoked.
     */
    public static final double RSI_CONFIRM_LONG_MAX = RSI_OVERBOUGHT;

    /**
     * Lower bound for a SHORT confirmation — mirrors {@link #RSI_OVERSOLD}.
     * Below this, RSI is oversold and confirmation is revoked.
     */
    public static final double RSI_CONFIRM_SHORT_MIN = RSI_OVERSOLD;

    /**
     * Upper bound of the "neutral" RSI range. A SHORT setup is confirmed
     * when RSI is strictly below this value (and above {@link #RSI_OVERSOLD}).
     */
    public static final double RSI_CONFIRM_SHORT_MAX = 60.0;

    // ── Divergence detection ────────────────────────────────────────────

    /**
     * When structural bias is bullish, RSI below this value signals a
     * bearish divergence (price up, momentum failing to follow).
     */
    public static final double RSI_BEARISH_DIVERGENCE_MAX = 55.0;

    /**
     * When structural bias is bearish, RSI above this value signals a
     * bullish divergence (price down, momentum failing to follow).
     */
    public static final double RSI_BULLISH_DIVERGENCE_MIN = 45.0;

    private MomentumThresholds() {
        // static constants only
    }
}

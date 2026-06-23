package com.riskdesk.domain.quant.simulation;

/**
 * Per-instrument direction-inversion mode for the Quant 7-Gates simulation.
 *
 * <p>The entry CONDITION is unchanged in every mode — the gates still qualify a
 * LONG or SHORT setup and the HTF filter still checks the SIGNAL direction. Only
 * the trade that gets OPENED is transformed:
 *
 * <ul>
 *   <li>{@link #NONE} — trade the signal as-is (default).</li>
 *   <li>{@link #MIRROR} — flip the direction AND swap SL&harr;TP1 (the old stop
 *       becomes the new target, the old target the new stop). R:R inverts from
 *       1.5 to ~0.67; needs a high win-rate. This is the literal "le SL devient
 *       le TP" idea.</li>
 *   <li>{@link #FADE} — flip the direction but KEEP the original SL/TP sizing
 *       (tight stop, far target). R:R 1.5 is preserved; only the side changes.</li>
 * </ul>
 *
 * <p>Used to paper-validate (and, once proven, optionally trade live) the
 * hypothesis that a given instrument's qualified setups are contrarian — see the
 * MNQ inverse study. Held at runtime by {@code QuantSimInvertState}; defaults to
 * {@link #NONE} and resets to it on restart.
 */
public enum QuantSimInvertMode {
    NONE,
    MIRROR,
    FADE
}

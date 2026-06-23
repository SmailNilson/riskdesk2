package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.simulation.QuantSimInvertMode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instrument runtime inversion mode for the Quant 7-Gates simulation
 * (NONE / MIRROR / FADE). Modelled on {@link QuantSimExecutionState}: in-memory
 * only, so a process restart resets every instrument back to {@link
 * QuantSimInvertMode#NONE} (trade the signal as-is) — the safe default. Written
 * from the controller thread, read from the scan/tick thread, backed by a
 * concurrent map.
 *
 * <p>Independent of the Auto-IBKR mirror toggle ({@link QuantSimExecutionState}):
 * this picks the trade DIRECTION, that decides paper vs live. Both compose — an
 * inverted setup routes a real order iff its instrument's mirror is also armed.
 */
@Component
public class QuantSimInvertState {

    private final Map<Instrument, QuantSimInvertMode> modes = new ConcurrentHashMap<>();

    /** Current mode for {@code instrument}; {@link QuantSimInvertMode#NONE} when unset. */
    public QuantSimInvertMode mode(Instrument instrument) {
        return instrument == null ? QuantSimInvertMode.NONE
            : modes.getOrDefault(instrument, QuantSimInvertMode.NONE);
    }

    /** Set the mode for {@code instrument}. NONE/null clears it (back to default). */
    public void setMode(Instrument instrument, QuantSimInvertMode mode) {
        if (instrument == null) return;
        if (mode == null || mode == QuantSimInvertMode.NONE) modes.remove(instrument);
        else modes.put(instrument, mode);
    }

    /** Snapshot of every instrument's mode for the status endpoint. */
    public Map<Instrument, QuantSimInvertMode> snapshot() {
        Map<Instrument, QuantSimInvertMode> out = new EnumMap<>(Instrument.class);
        for (Instrument i : Instrument.values()) out.put(i, mode(i));
        return out;
    }
}

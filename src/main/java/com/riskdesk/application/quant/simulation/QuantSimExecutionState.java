package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instrument runtime toggle for the Quant 7-Gates Auto-IBKR mirror.
 *
 * <p>Default is OFF for every instrument. The state is in-memory only: a process
 * restart resets all toggles to OFF, which is the safe default for a feature
 * that places live orders (post-restart you must re-arm explicitly). Written
 * from the controller thread, read from the scheduler thread — backed by a
 * concurrent set.</p>
 */
@Component
public class QuantSimExecutionState {

    private final Set<Instrument> enabled = ConcurrentHashMap.newKeySet();

    /** True when the Auto-IBKR mirror is armed for {@code instrument}. */
    public boolean isEnabled(Instrument instrument) {
        return instrument != null && enabled.contains(instrument);
    }

    /** Arm / disarm the mirror for {@code instrument}. Idempotent. */
    public void setEnabled(Instrument instrument, boolean on) {
        if (instrument == null) return;
        if (on) enabled.add(instrument);
        else enabled.remove(instrument);
    }

    /** Snapshot of the per-instrument toggle state for the status endpoint. */
    public Map<Instrument, Boolean> snapshot() {
        Map<Instrument, Boolean> out = new EnumMap<>(Instrument.class);
        for (Instrument i : Instrument.values()) out.put(i, enabled.contains(i));
        return out;
    }
}

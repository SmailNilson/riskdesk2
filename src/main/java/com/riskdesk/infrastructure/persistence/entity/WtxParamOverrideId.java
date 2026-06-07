package com.riskdesk.infrastructure.persistence.entity;

import java.io.Serializable;
import java.util.Objects;

/** Composite primary key for {@link WtxParamOverrideEntity} — overrides are scoped per (instrument, timeframe). */
public class WtxParamOverrideId implements Serializable {

    private String instrument;
    private String timeframe;

    public WtxParamOverrideId() {}

    public WtxParamOverrideId(String instrument, String timeframe) {
        this.instrument = instrument;
        this.timeframe = timeframe;
    }

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WtxParamOverrideId that)) return false;
        return Objects.equals(instrument, that.instrument)
                && Objects.equals(timeframe, that.timeframe);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instrument, timeframe);
    }
}

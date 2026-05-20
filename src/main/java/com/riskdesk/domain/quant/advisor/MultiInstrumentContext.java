package com.riskdesk.domain.quant.advisor;

import com.riskdesk.domain.model.Instrument;

import java.util.List;

/**
 * Cross-instrument view at evaluation time. Fed to the advisor so it can
 * detect macro divergences (e.g. MNQ short setup invalidated by MGC + DXY
 * confirming risk-off).
 */
public record MultiInstrumentContext(List<InstrumentSnapshot> instruments) {

    public record InstrumentSnapshot(
        Instrument instrument,
        String cyclePhase,
        Double delta,
        double dayMove,
        Double price,
        int score
    ) {}

    public static MultiInstrumentContext empty() {
        return new MultiInstrumentContext(List.of());
    }
}

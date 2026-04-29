package com.riskdesk.presentation.quant.dto;

import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.QuantSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire representation of one Quant evaluation tick. Field names are part of
 * the public API contract — the frontend dashboard reads them by name.
 */
public record QuantSnapshotResponse(
    String instrument,
    int score,
    Double price,
    String priceSource,
    double dayMove,
    String scanTime,
    Double entry,
    Double sl,
    Double tp1,
    Double tp2,
    boolean shortSetup7_7,
    boolean shortAlert6_7,
    List<QuantGateView> gates
) {

    public static QuantSnapshotResponse from(QuantSnapshot snapshot) {
        List<QuantGateView> gateList = new ArrayList<>(Gate.values().length);
        for (Gate g : Gate.values()) {
            var r = snapshot.gates().get(g);
            if (r == null) continue;
            gateList.add(new QuantGateView(g.name(), r.ok(), r.reason()));
        }
        return new QuantSnapshotResponse(
            snapshot.instrument().name(),
            snapshot.score(),
            snapshot.price(),
            snapshot.priceSource(),
            snapshot.dayMove(),
            snapshot.scanTime() != null ? snapshot.scanTime().toString() : null,
            snapshot.suggestedEntry(),
            snapshot.suggestedSL(),
            snapshot.suggestedTP1(),
            snapshot.suggestedTP2(),
            snapshot.isShortSetup7_7(),
            snapshot.isShortAlert6_7(),
            gateList
        );
    }
}

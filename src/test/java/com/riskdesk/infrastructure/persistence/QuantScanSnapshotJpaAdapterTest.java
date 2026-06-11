package com.riskdesk.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.scanlog.QuantScanSnapshot;
import com.riskdesk.infrastructure.persistence.entity.QuantScanSnapshotEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Entity ↔ domain mapping round-trip, including the gates-JSON column. */
class QuantScanSnapshotJpaAdapterTest {

    private final QuantScanSnapshotJpaAdapter adapter =
        new QuantScanSnapshotJpaAdapter(null, new ObjectMapper());

    @Test
    void roundTripsAllFields() {
        Map<String, String> gates = new LinkedHashMap<>();
        gates.put("G1_ABS_BEAR", "PASS — n8=9 BEAR Δ=-260");
        gates.put("L3_DELTA_POS", "ABSTAIN — Δ=ABSTAIN (feed down)");

        QuantScanSnapshot original = new QuantScanSnapshot(
            Instrument.MNQ, Instant.parse("2026-06-11T14:30:00Z"),
            28518.25, "LIVE_PUSH",
            -260.0, 46.5, "REAL_TICKS",
            9, 1, 5, 8.7, "BEAR",
            "DISTRIBUTION", 72,
            "MARKDOWN", "LATE",
            5, 2,
            "DISTRIBUTION_SILENCIEUSE", "Distribution silencieuse", "HIGH", "TRADE",
            gates);

        QuantScanSnapshotEntity entity = adapter.toEntity(original);
        assertThat(entity.getGatesJson()).contains("G1_ABS_BEAR").contains("ABSTAIN");

        QuantScanSnapshot back = adapter.toDomain(entity);
        assertThat(back).isEqualTo(original);
    }

    @Test
    void nullableFieldsSurviveRoundTrip() {
        QuantScanSnapshot original = new QuantScanSnapshot(
            Instrument.MCL, Instant.parse("2026-06-11T14:30:00Z"),
            null, "",
            null, null, null,
            0, 0, 0, 0.0, "MIX",
            null, null,
            null, null,
            0, 0,
            null, null, null, null,
            Map.of());

        QuantScanSnapshot back = adapter.toDomain(adapter.toEntity(original));
        assertThat(back).isEqualTo(original);
    }
}

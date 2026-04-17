package com.riskdesk.domain.contract;

import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ActiveContractRegistryTest {

    private ActiveContractRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ActiveContractRegistry();
    }

    @Test
    void getContractMonth_emptyBeforeInit() {
        assertEquals(Optional.empty(), registry.getContractMonth(Instrument.MCL));
    }

    @Test
    void initialize_setsMonth() {
        registry.initialize(Instrument.MCL, "202506");

        assertEquals(Optional.of("202506"), registry.getContractMonth(Instrument.MCL));
    }

    @Test
    void initialize_multipleInstruments() {
        registry.initialize(Instrument.MCL, "202505");
        registry.initialize(Instrument.MGC, "202506");
        registry.initialize(Instrument.MNQ, "202506");
        registry.initialize(Instrument.E6, "202506");

        assertEquals(Optional.of("202505"), registry.getContractMonth(Instrument.MCL));
        assertEquals(Optional.of("202506"), registry.getContractMonth(Instrument.MGC));
        assertEquals(Optional.of("202506"), registry.getContractMonth(Instrument.MNQ));
        assertEquals(Optional.of("202506"), registry.getContractMonth(Instrument.E6));
    }

    @Test
    void confirmRollover_switchesMonth() {
        registry.initialize(Instrument.MCL, "202505");

        registry.confirmRollover(Instrument.MCL, "202506");

        assertEquals(Optional.of("202506"), registry.getContractMonth(Instrument.MCL));
    }

    @Test
    void confirmRollover_doesNotAffectOtherInstruments() {
        registry.initialize(Instrument.MCL, "202505");
        registry.initialize(Instrument.MGC, "202506");

        registry.confirmRollover(Instrument.MCL, "202506");

        assertEquals(Optional.of("202506"), registry.getContractMonth(Instrument.MCL));
        assertEquals(Optional.of("202506"), registry.getContractMonth(Instrument.MGC));
    }

    @Test
    void snapshot_returnsImmutableCopy() {
        registry.initialize(Instrument.MCL, "202505");
        registry.initialize(Instrument.MGC, "202506");

        Map<Instrument, String> snap = registry.snapshot();

        assertEquals(2, snap.size());
        assertEquals("202505", snap.get(Instrument.MCL));
        assertEquals("202506", snap.get(Instrument.MGC));
        assertThrows(UnsupportedOperationException.class, () -> snap.put(Instrument.MNQ, "202506"));
    }

    @Test
    void snapshot_reflectsRollover() {
        registry.initialize(Instrument.MCL, "202505");
        registry.confirmRollover(Instrument.MCL, "202506");

        Map<Instrument, String> snap = registry.snapshot();
        assertEquals("202506", snap.get(Instrument.MCL));
    }
}

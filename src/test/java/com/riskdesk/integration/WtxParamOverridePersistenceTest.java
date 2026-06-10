package com.riskdesk.integration;

import com.riskdesk.domain.engine.strategy.wtx.WtxParamOverride;
import com.riskdesk.infrastructure.persistence.JpaWtxParamOverrideAdapter;
import com.riskdesk.infrastructure.persistence.JpaWtxParamOverrideRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip of the extended {@link WtxParamOverride} (zone-entry fields included) through the JPA
 * adapter. A fresh adapter instance is used for the read-back so the in-memory cache cannot mask a
 * mapping gap — the value must come from the H2 row itself.
 */
@DataJpaTest
@ActiveProfiles("test")
class WtxParamOverridePersistenceTest {

    @Autowired
    private JpaWtxParamOverrideRepository repository;

    @Test
    void topTrainZ35Preset_roundTripsThroughTheDatabase() {
        new JpaWtxParamOverrideAdapter(repository)
                .save("MNQ", "10m", WtxParamOverride.TOP_TRAIN_Z35);

        // New adapter = empty cache → forces the DB read path.
        WtxParamOverride reloaded = new JpaWtxParamOverrideAdapter(repository).load("MNQ", "10m");

        assertEquals(WtxParamOverride.TOP_TRAIN_Z35.n1(), reloaded.n1());
        assertEquals(WtxParamOverride.TOP_TRAIN_Z35.n2(), reloaded.n2());
        assertEquals(WtxParamOverride.TOP_TRAIN_Z35.signalPeriod(), reloaded.signalPeriod());
        assertEquals(0, WtxParamOverride.TOP_TRAIN_Z35.slAtrMult().compareTo(reloaded.slAtrMult()));
        assertEquals(0, WtxParamOverride.TOP_TRAIN_Z35.nsc().compareTo(reloaded.nsc()));
        assertEquals(0, WtxParamOverride.TOP_TRAIN_Z35.nsv().compareTo(reloaded.nsv()));
        assertEquals(Boolean.FALSE, reloaded.useCompra1());
        assertEquals(Boolean.FALSE, reloaded.useVenta1());
        assertEquals(Boolean.FALSE, reloaded.sessionFilterEnabled());
    }

    @Test
    void clearingTheOverride_persistsAllNulls() {
        JpaWtxParamOverrideAdapter writer = new JpaWtxParamOverrideAdapter(repository);
        writer.save("MNQ", "10m", WtxParamOverride.TOP_TRAIN_Z35);
        writer.save("MNQ", "10m", WtxParamOverride.NONE);

        WtxParamOverride reloaded = new JpaWtxParamOverrideAdapter(repository).load("MNQ", "10m");
        assertTrue(reloaded.isEmpty());
    }
}

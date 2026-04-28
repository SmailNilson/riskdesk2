package com.riskdesk.externalsetup;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.ExternalSetupProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSetupPropertiesTest {

    @Test
    void perInstrumentTtlOverridesDefault() {
        ExternalSetupProperties p = new ExternalSetupProperties();
        p.setDefaultTtl(Duration.ofMinutes(5));
        p.setTtl(Map.of(
            "MNQ", Duration.ofMinutes(3),
            "E6", Duration.ofMinutes(8)
        ));

        assertThat(p.ttlFor(Instrument.MNQ)).isEqualTo(Duration.ofMinutes(3));
        assertThat(p.ttlFor(Instrument.E6)).isEqualTo(Duration.ofMinutes(8));
        assertThat(p.ttlFor(Instrument.MGC)).isEqualTo(Duration.ofMinutes(5)); // default
        assertThat(p.ttlFor(null)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void resolvedTtlsCoverAllInstruments() {
        ExternalSetupProperties p = new ExternalSetupProperties();
        p.setDefaultTtl(Duration.ofMinutes(7));
        Map<Instrument, Duration> all = p.resolvedTtls();
        assertThat(all).containsKeys(Instrument.MNQ, Instrument.MGC, Instrument.MCL, Instrument.E6, Instrument.DXY);
        for (Duration d : all.values()) {
            assertThat(d).isEqualTo(Duration.ofMinutes(7));
        }
    }
}

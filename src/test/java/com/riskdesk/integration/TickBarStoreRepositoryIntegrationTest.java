package com.riskdesk.integration;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.TickBar;
import com.riskdesk.infrastructure.persistence.JpaTickBarRepository;
import com.riskdesk.infrastructure.persistence.JpaTickBarStoreAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class TickBarStoreRepositoryIntegrationTest {

    @Autowired
    private JpaTickBarRepository repository;

    private JpaTickBarStoreAdapter store;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        store = new JpaTickBarStoreAdapter(repository);
    }

    @Test
    void saveAndLoadRecent_returnsBarsOldestFirst_withFieldFidelity() {
        store.saveCompleted(List.of(bar(Instrument.MNQ, 200, 0), bar(Instrument.MNQ, 200, 1)));

        List<TickBar> loaded = store.loadRecent(Instrument.MNQ, 200, 10);

        assertEquals(2, loaded.size());
        assertEquals(0, loaded.get(0).seq());
        assertEquals(1, loaded.get(1).seq());

        TickBar b = loaded.get(0);
        assertEquals(Instrument.MNQ.name(), b.instrument());
        assertEquals(200, b.ticksPerBar());
        assertEquals(100.0, b.open());
        assertEquals(101.0, b.high());
        assertEquals(99.0, b.low());
        assertEquals(100.5, b.close());
        assertEquals(200, b.volume());
        assertEquals(150, b.buyVolume());
        assertEquals(50, b.sellVolume());
        assertEquals(100, b.delta());
        assertEquals(200, b.tickCount());
        assertTrue(b.complete());
        assertEquals(t0().getEpochSecond(), b.openTime());
    }

    @Test
    void loadRecent_respectsLimit_keepingNewestSeq() {
        for (long s = 0; s < 5; s++) {
            store.saveCompleted(List.of(bar(Instrument.MNQ, 200, s)));
        }
        List<TickBar> loaded = store.loadRecent(Instrument.MNQ, 200, 2);
        assertEquals(2, loaded.size());
        assertEquals(3, loaded.get(0).seq());
        assertEquals(4, loaded.get(1).seq());
    }

    @Test
    void loadRecent_filtersByTicksPerBar() {
        store.saveCompleted(List.of(bar(Instrument.MNQ, 200, 0), bar(Instrument.MNQ, 1000, 1)));
        assertEquals(1, store.loadRecent(Instrument.MNQ, 200, 10).size());
        assertEquals(1, store.loadRecent(Instrument.MNQ, 1000, 10).size());
    }

    @Test
    void purgeInstrument_dropsOnlyThatInstrument() {
        store.saveCompleted(List.of(bar(Instrument.MNQ, 200, 0), bar(Instrument.MCL, 100, 0)));
        store.purgeInstrument(Instrument.MNQ);
        assertTrue(store.loadRecent(Instrument.MNQ, 200, 10).isEmpty());
        assertEquals(1, store.loadRecent(Instrument.MCL, 100, 10).size());
    }

    @Test
    void purgeOlderThan_deletesByCloseInstant() {
        store.saveCompleted(List.of(
            barAt(Instrument.MNQ, 200, 0, Instant.parse("2026-06-01T00:00:00Z")),
            barAt(Instrument.MNQ, 200, 1, Instant.parse("2026-06-10T00:00:00Z"))));

        int deleted = store.purgeOlderThan(Instant.parse("2026-06-05T00:00:00Z"));

        assertEquals(1, deleted);
        List<TickBar> remaining = store.loadRecent(Instrument.MNQ, 200, 10);
        assertEquals(1, remaining.size());
        assertEquals(1, remaining.get(0).seq());
    }

    private static Instant t0() {
        return Instant.parse("2026-06-10T14:30:00Z");
    }

    private static TickBar bar(Instrument instrument, int ticksPerBar, long seq) {
        return barAt(instrument, ticksPerBar, seq, t0().plusSeconds(seq * 10));
    }

    private static TickBar barAt(Instrument instrument, int ticksPerBar, long seq, Instant close) {
        long closeTime = close.getEpochSecond();
        long openTime = t0().getEpochSecond();
        return new TickBar(instrument.name(), ticksPerBar, seq,
            openTime, closeTime, 100.0, 101.0, 99.0, 100.5,
            200, 150, 50, 100, 200, true);
    }
}

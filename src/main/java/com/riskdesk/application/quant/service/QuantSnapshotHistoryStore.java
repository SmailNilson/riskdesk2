package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory ring buffer of past Quant snapshots, keyed by instrument.
 * <p>
 * Holds the last {@link #CAPACITY_PER_INSTRUMENT} entries per instrument
 * (≈ 4 hours at one scan per minute). Survives across requests but resets on
 * application restart — that is acceptable for dashboard "recent history"
 * use cases where long-term retention is not required.
 */
@Component
public class QuantSnapshotHistoryStore {

    /** Keep ~4 hours at 60 s/scan. */
    public static final int CAPACITY_PER_INSTRUMENT = 240;

    private final Map<Instrument, Deque<Entry>> buffers = new EnumMap<>(Instrument.class);

    public synchronized void add(Instrument instrument, QuantSnapshot snapshot) {
        Deque<Entry> deque = buffers.computeIfAbsent(instrument, k -> new ArrayDeque<>());
        deque.addLast(new Entry(Instant.now(), snapshot));
        while (deque.size() > CAPACITY_PER_INSTRUMENT) {
            deque.removeFirst();
        }
    }

    public synchronized List<QuantSnapshot> recent(Instrument instrument, Duration window) {
        Deque<Entry> deque = buffers.get(instrument);
        if (deque == null || deque.isEmpty()) return List.of();
        Instant cutoff = Instant.now().minus(window);
        List<QuantSnapshot> out = new ArrayList<>(deque.size());
        for (Entry e : deque) {
            if (!e.recordedAt.isBefore(cutoff)) {
                out.add(e.snapshot);
            }
        }
        return out;
    }

    private record Entry(Instant recordedAt, QuantSnapshot snapshot) {}
}

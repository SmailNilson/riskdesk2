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
import java.util.Optional;

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

    /**
     * Returns the most recent snapshot for the instrument <b>regardless of age</b>.
     * Used by {@code GET /api/quant/snapshot/{instr}} so a scheduler stall longer
     * than the dashboard's history window does not erase the trader's last
     * known view (PR #297 follow-up review). Returns empty only when no scan
     * has ever been recorded for the instrument.
     */
    public synchronized Optional<QuantSnapshot> latest(Instrument instrument) {
        Deque<Entry> deque = buffers.get(instrument);
        if (deque == null || deque.isEmpty()) return Optional.empty();
        return Optional.of(deque.peekLast().snapshot);
    }

    private record Entry(Instant recordedAt, QuantSnapshot snapshot) {}
}

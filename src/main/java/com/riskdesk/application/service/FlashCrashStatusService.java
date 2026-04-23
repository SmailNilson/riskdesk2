package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.JpaFlashCrashEventRepository;
import com.riskdesk.infrastructure.persistence.entity.FlashCrashEventEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Exposes the last-known flash-crash FSM phase per instrument to the REST
 * layer. {@link com.riskdesk.application.service.OrderFlowEventPersistenceService}
 * writes every phase transition the FSM emits; this service reads the most
 * recent row per instrument so that a fresh page load can seed the UI before
 * the {@code /topic/flash-crash} WebSocket stream delivers live pushes.
 * <p>
 * Kept in the application layer so that the presentation controller does not
 * depend on infrastructure — the hexagonal {@code ArchUnit} rule forbids a
 * controller from importing a JPA repository directly.
 */
@Service
public class FlashCrashStatusService {

    private final JpaFlashCrashEventRepository repository;

    public FlashCrashStatusService(JpaFlashCrashEventRepository repository) {
        this.repository = repository;
    }

    /** Snapshot of the latest FSM transition for one instrument, or empty if none exists. */
    public Optional<FlashCrashStatusSnapshot> latestForInstrument(Instrument instrument) {
        return repository.findFirstByInstrumentOrderByTimestampDesc(instrument)
            .map(FlashCrashStatusService::toSnapshot);
    }

    /**
     * Snapshot per exchange-traded future (DXY is synthetic and has no FSM).
     * Instruments without any persisted event are omitted from the map.
     */
    public Map<Instrument, FlashCrashStatusSnapshot> latestPerInstrument() {
        Map<Instrument, FlashCrashStatusSnapshot> out = new EnumMap<>(Instrument.class);
        for (Instrument inst : Instrument.values()) {
            if (!inst.isExchangeTradedFuture()) continue;
            repository.findFirstByInstrumentOrderByTimestampDesc(inst)
                .ifPresent(entity -> out.put(inst, toSnapshot(entity)));
        }
        return out;
    }

    private static FlashCrashStatusSnapshot toSnapshot(FlashCrashEventEntity entity) {
        return new FlashCrashStatusSnapshot(
            entity.getInstrument(),
            entity.getCurrentPhase(),
            entity.getPreviousPhase(),
            entity.getConditionsMet(),
            entity.getReversalScore(),
            entity.getTimestamp()
        );
    }

    /**
     * Immutable, infrastructure-free view of a flash-crash FSM transition.
     * Individual condition booleans are not persisted; callers render an
     * empty indicator row for REST-seeded cards.
     */
    public record FlashCrashStatusSnapshot(
        Instrument instrument,
        String phase,
        String previousPhase,
        int conditionsMet,
        double reversalScore,
        Instant timestamp
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instrument", instrument.name());
            m.put("phase", phase);
            m.put("previousPhase", previousPhase);
            m.put("conditionsMet", conditionsMet);
            m.put("conditions", new boolean[0]);
            m.put("reversalScore", reversalScore);
            m.put("timestamp", timestamp.toString());
            return m;
        }
    }
}

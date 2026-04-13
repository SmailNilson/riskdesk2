package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.AbsorptionDetected;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of the latest absorption signal per instrument.
 * Listens to {@link AbsorptionDetected} domain events published by the
 * OrderFlowOrchestrator and makes them available to other application services
 * (e.g. IndicatorService for Live Score computation).
 *
 * <p>Signals expire after {@link #MAX_AGE} to avoid stale data influencing scores.</p>
 */
@Service
public class AbsorptionCache {

    /** Absorption signals older than 10 minutes are considered stale. */
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    private final ConcurrentHashMap<Instrument, AbsorptionSignal> latest = new ConcurrentHashMap<>();

    @EventListener
    public void onAbsorptionDetected(AbsorptionDetected event) {
        latest.put(event.instrument(), event.signal());
    }

    /**
     * Returns the latest absorption signal for the given instrument,
     * or empty if no signal was detected or the signal is stale.
     */
    public Optional<AbsorptionSignal> latest(Instrument instrument) {
        AbsorptionSignal signal = latest.get(instrument);
        if (signal == null) {
            return Optional.empty();
        }
        if (signal.timestamp().plus(MAX_AGE).isBefore(Instant.now())) {
            latest.remove(instrument);
            return Optional.empty();
        }
        return Optional.of(signal);
    }
}

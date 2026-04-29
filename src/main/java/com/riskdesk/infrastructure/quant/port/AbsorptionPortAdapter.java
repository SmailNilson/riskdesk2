package com.riskdesk.infrastructure.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.quant.port.AbsorptionPort;
import com.riskdesk.infrastructure.persistence.JpaAbsorptionEventRepository;
import com.riskdesk.infrastructure.persistence.entity.AbsorptionEventEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the Quant absorption port to the persisted order-flow event stream.
 * Fetches the latest {@code FETCH_LIMIT} rows then filters in memory by
 * {@code since} — sufficient given the 3-minute aggregation window.
 */
@Component
public class AbsorptionPortAdapter implements AbsorptionPort {

    /** Pull a generous slice; the {@code since} cutoff filters down to a handful of events. */
    private static final int FETCH_LIMIT = 100;

    private final JpaAbsorptionEventRepository repository;

    public AbsorptionPortAdapter(JpaAbsorptionEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AbsorptionSignal> recent(Instrument instrument, Instant since) {
        List<AbsorptionEventEntity> rows =
            repository.findByInstrumentOrderByTimestampDesc(instrument, PageRequest.of(0, FETCH_LIMIT));
        List<AbsorptionSignal> out = new ArrayList<>();
        for (AbsorptionEventEntity e : rows) {
            if (since != null && e.getTimestamp() != null && e.getTimestamp().isBefore(since)) continue;
            out.add(new AbsorptionSignal(
                e.getInstrument(),
                parseSide(e.getSide()),
                e.getAbsorptionScore(),
                e.getAggressiveDelta(),
                e.getPriceMoveTicks(),
                e.getTotalVolume(),
                e.getTimestamp()
            ));
        }
        return out;
    }

    private static AbsorptionSignal.AbsorptionSide parseSide(String raw) {
        if (raw == null) return AbsorptionSignal.AbsorptionSide.BULLISH_ABSORPTION;
        try {
            return AbsorptionSignal.AbsorptionSide.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return AbsorptionSignal.AbsorptionSide.BULLISH_ABSORPTION;
        }
    }
}

package com.riskdesk.infrastructure.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.quant.port.DistributionPort;
import com.riskdesk.infrastructure.persistence.JpaDistributionEventRepository;
import com.riskdesk.infrastructure.persistence.entity.DistributionEventEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class DistributionPortAdapter implements DistributionPort {

    private static final int FETCH_LIMIT = 50;

    private final JpaDistributionEventRepository repository;

    public DistributionPortAdapter(JpaDistributionEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DistributionSignal> recent(Instrument instrument, Instant since) {
        List<DistributionEventEntity> rows =
            repository.findByInstrumentOrderByTimestampDesc(instrument, PageRequest.of(0, FETCH_LIMIT));
        List<DistributionSignal> out = new ArrayList<>();
        for (DistributionEventEntity e : rows) {
            if (since != null && e.getTimestamp() != null && e.getTimestamp().isBefore(since)) continue;
            out.add(new DistributionSignal(
                e.getInstrument(),
                parseType(e.getType()),
                e.getConsecutiveCount(),
                e.getAvgScore(),
                e.getTotalDurationSeconds(),
                e.getPriceAtDetection(),
                e.getResistanceLevel(),
                e.getConfidenceScore(),
                e.getTimestamp(),
                e.getTimestamp()
            ));
        }
        return out;
    }

    private static DistributionSignal.DistributionType parseType(String raw) {
        if (raw == null) return DistributionSignal.DistributionType.DISTRIBUTION;
        try {
            return DistributionSignal.DistributionType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return DistributionSignal.DistributionType.DISTRIBUTION;
        }
    }
}

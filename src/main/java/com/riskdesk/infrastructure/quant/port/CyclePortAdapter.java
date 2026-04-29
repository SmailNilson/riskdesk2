package com.riskdesk.infrastructure.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;
import com.riskdesk.domain.quant.port.CyclePort;
import com.riskdesk.infrastructure.persistence.JpaCycleEventRepository;
import com.riskdesk.infrastructure.persistence.entity.CycleEventEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class CyclePortAdapter implements CyclePort {

    private static final int FETCH_LIMIT = 50;

    private final JpaCycleEventRepository repository;

    public CyclePortAdapter(JpaCycleEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SmartMoneyCycleSignal> recent(Instrument instrument, Instant since) {
        List<CycleEventEntity> rows =
            repository.findByInstrumentOrderByTimestampDesc(instrument, PageRequest.of(0, FETCH_LIMIT));
        List<SmartMoneyCycleSignal> out = new ArrayList<>();
        for (CycleEventEntity e : rows) {
            if (since != null && e.getTimestamp() != null && e.getTimestamp().isBefore(since)) continue;
            out.add(new SmartMoneyCycleSignal(
                e.getInstrument(),
                parseCycleType(e.getCycleType()),
                parsePhase(e.getCurrentPhase()),
                e.getPriceAtPhase1(),
                e.getPriceAtPhase2(),
                e.getPriceAtPhase3(),
                e.getTotalPriceMove(),
                e.getTotalDurationMinutes(),
                e.getConfidence(),
                e.getStartedAt(),
                e.getCompletedAt()
            ));
        }
        return out;
    }

    private static SmartMoneyCycleSignal.CycleType parseCycleType(String raw) {
        if (raw == null) return SmartMoneyCycleSignal.CycleType.BEARISH_CYCLE;
        try {
            return SmartMoneyCycleSignal.CycleType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return SmartMoneyCycleSignal.CycleType.BEARISH_CYCLE;
        }
    }

    private static SmartMoneyCycleSignal.CyclePhase parsePhase(String raw) {
        if (raw == null) return SmartMoneyCycleSignal.CyclePhase.PHASE_1;
        try {
            return SmartMoneyCycleSignal.CyclePhase.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return SmartMoneyCycleSignal.CyclePhase.PHASE_1;
        }
    }
}

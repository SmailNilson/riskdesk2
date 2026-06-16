package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.TickBar;
import com.riskdesk.domain.orderflow.port.TickBarStorePort;
import com.riskdesk.infrastructure.persistence.entity.TickBarEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JPA adapter implementing {@link TickBarStorePort}: durable storage of completed
 * tick-chart bars so the chart survives a redeploy (UC tick-chart persistence).
 */
@Component
public class JpaTickBarStoreAdapter implements TickBarStorePort {

    private static final Logger log = LoggerFactory.getLogger(JpaTickBarStoreAdapter.class);

    private final JpaTickBarRepository repository;

    public JpaTickBarStoreAdapter(JpaTickBarRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void saveCompleted(List<TickBar> bars) {
        if (bars == null || bars.isEmpty()) {
            return;
        }
        List<TickBarEntity> entities = new ArrayList<>(bars.size());
        for (TickBar b : bars) {
            if (b == null || !b.complete()) {
                continue;
            }
            entities.add(new TickBarEntity(
                Instrument.valueOf(b.instrument()), b.ticksPerBar(), b.seq(),
                b.openTime(), b.closeTime(),
                b.open(), b.high(), b.low(), b.close(),
                b.volume(), b.buyVolume(), b.sellVolume(), b.delta(), b.tickCount()));
        }
        if (!entities.isEmpty()) {
            repository.saveAll(entities);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<TickBar> loadRecent(Instrument instrument, int ticksPerBar, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<TickBarEntity> rows = repository.findByInstrumentAndTicksPerBarOrderBySeqDesc(
            instrument, ticksPerBar, PageRequest.of(0, limit));
        List<TickBar> bars = new ArrayList<>(rows.size());
        for (TickBarEntity e : rows) {
            bars.add(toDomain(e));
        }
        Collections.reverse(bars); // oldest-first
        return bars;
    }

    @Override
    @Transactional
    public void purgeInstrument(Instrument instrument) {
        int deleted = repository.deleteByInstrument(instrument);
        if (deleted > 0) {
            log.info("TickChart: purged {} persisted bars for {} (rollover)", deleted, instrument);
        }
    }

    @Override
    @Transactional
    public int purgeOlderThan(Instant cutoff) {
        return repository.deleteByCloseAtBefore(cutoff);
    }

    private static TickBar toDomain(TickBarEntity e) {
        return new TickBar(
            e.getInstrument().name(), e.getTicksPerBar(), e.getSeq(),
            e.getOpenTime(), e.getCloseTime(),
            e.getOpen(), e.getHigh(), e.getLow(), e.getClose(),
            e.getVolume(), e.getBuyVolume(), e.getSellVolume(), e.getDelta(), e.getTickCount(),
            true);
    }
}

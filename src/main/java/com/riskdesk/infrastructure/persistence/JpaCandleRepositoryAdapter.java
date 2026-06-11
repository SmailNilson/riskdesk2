package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.infrastructure.persistence.entity.CandleEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JPA adapter that bridges the domain port to the Spring Data repository.
 */
@Component
public class JpaCandleRepositoryAdapter implements CandleRepositoryPort {

    private final CandleRepository springDataRepo;

    public JpaCandleRepositoryAdapter(CandleRepository springDataRepo) {
        this.springDataRepo = springDataRepo;
    }

    @Override
    public List<Candle> findCandles(Instrument instrument, String timeframe, Instant from) {
        return springDataRepo.findByInstrumentAndTimeframeAndTimestampGreaterThanEqualOrderByTimestampAsc(
                instrument, timeframe, from).stream()
            .map(CandleEntityMapper::toDomain)
            .toList();
    }

    @Override
    public List<Candle> findRecentCandles(Instrument instrument, String timeframe, int limit) {
        // Use Pageable to push the LIMIT to the database — avoids fetching up to 500 rows in-memory
        return springDataRepo.findByInstrumentAndTimeframeOrderByTimestampDesc(
                instrument, timeframe, PageRequest.of(0, limit)).stream()
            .map(CandleEntityMapper::toDomain)
            .toList();
    }

    @Override
    public List<Candle> findCandlesBetween(Instrument instrument, String timeframe, Instant from, Instant to) {
        return springDataRepo.findByInstrumentAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
                instrument, timeframe, from, to).stream()
            .map(CandleEntityMapper::toDomain)
            .toList();
    }

    @Override
    public List<Candle> findCandlesBetweenPaged(Instrument instrument, String timeframe,
                                                Instant from, Instant to, int limit) {
        return springDataRepo.findByInstrumentAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
                instrument, timeframe, from, to, PageRequest.of(0, limit)).stream()
            .map(CandleEntityMapper::toDomain)
            .toList();
    }

    @Override
    public Optional<Instant> findLatestTimestamp(Instrument instrument, String timeframe) {
        return springDataRepo.findMaxTimestamp(instrument, timeframe);
    }

    /**
     * Upsert on the natural key (instrument, timeframe, timestamp). Two writers produce the
     * same bar — the live accumulator and the IBKR backfill — and at boot the gap-fill races
     * the live writer on just-closed bars; a plain insert dies on uk_candle_instrument_tf_ts
     * AND aborts the whole backfill batch. Last writer wins.
     */
    @Override
    public Candle save(Candle candle) {
        CandleEntity entity = CandleEntityMapper.toEntity(candle);
        if (entity.getId() == null) {
            adoptExistingId(entity);
        }
        try {
            return CandleEntityMapper.toDomain(springDataRepo.save(entity));
        } catch (DataIntegrityViolationException e) {
            // Lost the insert race: the row exists now — retry once as an update of it.
            CandleEntity retry = CandleEntityMapper.toEntity(candle);
            if (!adoptExistingId(retry)) {
                throw e;
            }
            return CandleEntityMapper.toDomain(springDataRepo.save(retry));
        }
    }

    @Override
    public List<Candle> saveAll(List<Candle> candles) {
        List<CandleEntity> entities = candles.stream().map(CandleEntityMapper::toEntity).toList();
        attachExistingIds(entities);
        try {
            return springDataRepo.saveAll(entities).stream()
                .map(CandleEntityMapper::toDomain)
                .toList();
        } catch (DataIntegrityViolationException e) {
            // A live bar landed between the id lookup and the flush — redo row-by-row as upserts
            // (fresh entities: the rolled-back batch may have left ids on these instances).
            return candles.stream().map(this::save).toList();
        }
    }

    /** Points the entity at the stored row with the same natural key, if any. */
    private boolean adoptExistingId(CandleEntity entity) {
        return springDataRepo.findByInstrumentAndTimeframeAndTimestamp(
                entity.getInstrument(), entity.getTimeframe(), entity.getTimestamp())
            .map(existing -> {
                entity.setId(existing.getId());
                return true;
            })
            .orElse(false);
    }

    /**
     * Batch variant of {@link #adoptExistingId}: one range query per (instrument, timeframe)
     * group instead of one lookup per row.
     */
    private void attachExistingIds(List<CandleEntity> entities) {
        Map<InstrumentTimeframe, List<CandleEntity>> groups = entities.stream()
            .filter(e -> e.getId() == null)
            .collect(Collectors.groupingBy(e -> new InstrumentTimeframe(e.getInstrument(), e.getTimeframe())));

        groups.forEach((group, rows) -> {
            Instant min = rows.get(0).getTimestamp();
            Instant max = min;
            for (CandleEntity row : rows) {
                if (row.getTimestamp().isBefore(min)) min = row.getTimestamp();
                if (row.getTimestamp().isAfter(max))  max = row.getTimestamp();
            }
            Map<Instant, Long> existingIds = springDataRepo
                .findByInstrumentAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
                    group.instrument(), group.timeframe(), min, max).stream()
                .collect(Collectors.toMap(CandleEntity::getTimestamp, CandleEntity::getId));
            for (CandleEntity row : rows) {
                Long id = existingIds.get(row.getTimestamp());
                if (id != null) row.setId(id);
            }
        });
    }

    private record InstrumentTimeframe(Instrument instrument, String timeframe) {}

    @Override
    @Transactional
    public void deleteAll() {
        springDataRepo.deleteAll();
    }

    @Override
    @Transactional
    public void deleteByInstrumentAndTimeframe(Instrument instrument, String timeframe) {
        springDataRepo.deleteByInstrumentAndTimeframe(instrument, timeframe);
    }

    @Override
    @Transactional
    public int deleteRange(Instrument instrument, String timeframe, Instant from, Instant to) {
        return springDataRepo.deleteRange(instrument, timeframe, from, to);
    }

    @Override
    public List<Candle> findRecentCandlesByContractMonth(Instrument instrument, String timeframe,
                                                         String contractMonth, int limit) {
        return springDataRepo.findByInstrumentAndTimeframeAndContractMonthOrderByTimestampDesc(
                instrument, timeframe, contractMonth, PageRequest.of(0, limit)).stream()
            .map(CandleEntityMapper::toDomain)
            .toList();
    }

    @Override
    public long count() {
        return springDataRepo.count();
    }
}

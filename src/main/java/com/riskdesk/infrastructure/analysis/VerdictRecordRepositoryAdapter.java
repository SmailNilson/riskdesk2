package com.riskdesk.infrastructure.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.analysis.model.LiveAnalysisSnapshot;
import com.riskdesk.domain.analysis.model.LiveVerdict;
import com.riskdesk.domain.analysis.port.VerdictRecordRepositoryPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import com.riskdesk.infrastructure.persistence.JpaVerdictRecordRepository;
import com.riskdesk.infrastructure.persistence.entity.VerdictRecordEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Component
public class VerdictRecordRepositoryAdapter implements VerdictRecordRepositoryPort {

    private final JpaVerdictRecordRepository repo;
    private final ObjectMapper objectMapper;

    public VerdictRecordRepositoryAdapter(JpaVerdictRecordRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public long save(LiveAnalysisSnapshot snapshot, LiveVerdict verdict) {
        try {
            VerdictRecordEntity e = new VerdictRecordEntity(
                snapshot.instrument(),
                snapshot.timeframe().label(),
                snapshot.decisionTimestamp(),
                snapshot.captureTimestamp(),
                snapshot.scoringEngineVersion(),
                snapshot.currentPrice(),
                verdict.bias().primary().name(),
                verdict.bias().confidence(),
                verdict.bias().structure().value(),
                verdict.bias().orderFlow().value(),
                verdict.bias().momentum().value(),
                objectMapper.writeValueAsString(snapshot),
                objectMapper.writeValueAsString(verdict)
            );
            return repo.save(e).getId();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialise verdict for persistence", ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LiveVerdict> findLatest(Instrument instrument, Timeframe timeframe) {
        return repo.findFirstByInstrumentAndTimeframeOrderByDecisionTimestampDesc(
                instrument, timeframe.label())
            .map(this::deserialiseVerdict);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LiveVerdict> findRecent(Instrument instrument, Timeframe timeframe, int limit) {
        int capped = Math.min(Math.max(1, limit), 500);
        return repo.findByInstrumentAndTimeframeOrderByDecisionTimestampDesc(
                instrument, timeframe.label(), PageRequest.of(0, capped))
            .stream().map(this::deserialiseVerdict).toList();
    }

    @Override
    @Deprecated
    @Transactional(readOnly = true)
    public List<RecordedAnalysis> findBetween(Instrument instrument, Timeframe timeframe,
                                                Instant from, Instant to) {
        var entities = repo.findByInstrumentAndTimeframeAndDecisionTimestampBetweenOrderByDecisionTimestampAsc(
            instrument, timeframe.label(), from, to);
        List<RecordedAnalysis> out = new ArrayList<>(entities.size());
        for (var e : entities) {
            tryDeserialise(e).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public void streamBetween(Instrument instrument, Timeframe timeframe,
                                Instant from, Instant to,
                                int pageSize, Consumer<RecordedAnalysis> consumer) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be > 0");
        }
        // Iterates pages until the underlying query returns fewer rows than
        // requested (final page) or returns nothing. Each page is held only for
        // the duration of the inner loop, so heap usage stays bounded by
        // pageSize × (snapshotJson + verdictJson size) regardless of total rows.
        // Per-page transactions keep the read connection short-lived.
        int pageIndex = 0;
        while (true) {
            List<VerdictRecordEntity> page = fetchPage(instrument, timeframe, from, to, pageIndex, pageSize);
            if (page.isEmpty()) return;
            for (var entity : page) {
                tryDeserialise(entity).ifPresent(consumer);
            }
            if (page.size() < pageSize) return;
            pageIndex++;
        }
    }

    @Transactional(readOnly = true)
    protected List<VerdictRecordEntity> fetchPage(Instrument instrument, Timeframe timeframe,
                                                    Instant from, Instant to,
                                                    int pageIndex, int pageSize) {
        return repo.findByInstrumentAndTimeframeAndDecisionTimestampBetweenOrderByDecisionTimestampAsc(
            instrument, timeframe.label(), from, to,
            org.springframework.data.domain.PageRequest.of(pageIndex, pageSize));
    }

    private Optional<RecordedAnalysis> tryDeserialise(VerdictRecordEntity e) {
        try {
            LiveAnalysisSnapshot snap = objectMapper.readValue(e.getSnapshotJson(),
                LiveAnalysisSnapshot.class);
            LiveVerdict verdict = objectMapper.readValue(e.getVerdictJson(), LiveVerdict.class);
            return Optional.of(new RecordedAnalysis(snap, verdict));
        } catch (Exception ex) {
            // Skip rows that fail to deserialise — schema drift across versions is allowed
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public int deleteByDecisionTimestampBefore(Instant cutoff) {
        return repo.deleteByDecisionTimestampBefore(cutoff);
    }

    private LiveVerdict deserialiseVerdict(VerdictRecordEntity e) {
        try {
            return objectMapper.readValue(e.getVerdictJson(), LiveVerdict.class);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to deserialise verdict id=" + e.getId(), ex);
        }
    }
}

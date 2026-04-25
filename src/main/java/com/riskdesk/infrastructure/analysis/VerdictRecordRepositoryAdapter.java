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
    @Transactional(readOnly = true)
    public List<RecordedAnalysis> findBetween(Instrument instrument, Timeframe timeframe,
                                                Instant from, Instant to) {
        var entities = repo.findByInstrumentAndTimeframeAndDecisionTimestampBetweenOrderByDecisionTimestampAsc(
            instrument, timeframe.label(), from, to);
        List<RecordedAnalysis> out = new ArrayList<>(entities.size());
        for (var e : entities) {
            try {
                LiveAnalysisSnapshot snap = objectMapper.readValue(e.getSnapshotJson(),
                    LiveAnalysisSnapshot.class);
                LiveVerdict verdict = objectMapper.readValue(e.getVerdictJson(), LiveVerdict.class);
                out.add(new RecordedAnalysis(snap, verdict));
            } catch (Exception ex) {
                // Skip rows that fail to deserialise — schema drift across versions is allowed
            }
        }
        return out;
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

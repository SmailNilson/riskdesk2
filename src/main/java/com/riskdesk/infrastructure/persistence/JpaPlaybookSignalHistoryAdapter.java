package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookSignal;
import com.riskdesk.domain.engine.strategy.playbook.port.PlaybookSignalHistoryPort;
import com.riskdesk.infrastructure.persistence.entity.PlaybookSignalEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JpaPlaybookSignalHistoryAdapter implements PlaybookSignalHistoryPort {

    private final JpaPlaybookSignalHistoryRepository repository;

    public JpaPlaybookSignalHistoryAdapter(JpaPlaybookSignalHistoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(PlaybookSignal signal) {
        PlaybookSignalEntity entity = repository.findById(signal.id())
                .orElseGet(PlaybookSignalEntity::new);
        fromDomain(signal, entity);
        repository.save(entity);
    }

    @Override
    public List<PlaybookSignal> findRecent(String instrument, int limit) {
        return repository.findByInstrumentOrderByEvaluatedAtDesc(instrument, PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<PlaybookSignal> findRecent(String instrument, String timeframe, int limit) {
        return repository.findByInstrumentAndTimeframeOrderByEvaluatedAtDesc(instrument, timeframe, PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    private PlaybookSignal toDomain(PlaybookSignalEntity e) {
        WtxRoutingOutcome outcome = e.getRoutingOutcome() != null ? WtxRoutingOutcome.valueOf(e.getRoutingOutcome()) : null;
        return new PlaybookSignal(
                e.getId(),
                e.getInstrument(),
                e.getTimeframe(),
                e.getEvaluatedAt(),
                e.getDirection(),
                e.getChecklistScore(),
                e.getSetupType(),
                e.getEntryPrice(),
                e.getStopLoss(),
                e.getTakeProfit1(),
                e.getTakeProfit2(),
                outcome,
                e.getRoutingErrorMessage()
        );
    }

    private void fromDomain(PlaybookSignal s, PlaybookSignalEntity e) {
        e.setId(s.id());
        e.setInstrument(s.instrument());
        e.setTimeframe(s.timeframe());
        e.setEvaluatedAt(s.evaluatedAt());
        e.setDirection(s.direction());
        e.setChecklistScore(s.checklistScore());
        e.setSetupType(s.setupType());
        e.setEntryPrice(s.entryPrice());
        e.setStopLoss(s.stopLoss());
        e.setTakeProfit1(s.takeProfit1());
        e.setTakeProfit2(s.takeProfit2());
        e.setRoutingOutcome(s.routingOutcome() != null ? s.routingOutcome().name() : null);
        e.setRoutingErrorMessage(s.routingErrorMessage());
    }
}

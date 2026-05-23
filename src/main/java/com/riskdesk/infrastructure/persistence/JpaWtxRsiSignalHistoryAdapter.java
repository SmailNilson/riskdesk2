package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.engine.strategy.wtx.WtxRoutingOutcome;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignal;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSignalRecord;
import com.riskdesk.domain.engine.strategy.wtxrsi.port.WtxRsiSignalHistoryPort;
import com.riskdesk.infrastructure.persistence.entity.WtxRsiSignalHistoryEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JpaWtxRsiSignalHistoryAdapter implements WtxRsiSignalHistoryPort {

    private final JpaWtxRsiSignalHistoryRepository repository;

    public JpaWtxRsiSignalHistoryAdapter(JpaWtxRsiSignalHistoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(WtxRsiSignalRecord record) {
        WtxRsiSignalHistoryEntity e = new WtxRsiSignalHistoryEntity();
        e.setInstrument(record.instrument());
        e.setTimeframe(record.timeframe());
        e.setSignalTs(record.signalTs());
        e.setSide(record.side().name());
        e.setAction(record.action().name());
        e.setWt1(record.wt1());
        e.setWt2(record.wt2());
        e.setRsi(record.rsi());
        e.setRsiSma(record.rsiSma());
        e.setChaikin(record.chaikin());
        e.setChaikinConfirmed(record.chaikinConfirmed());
        e.setEntryPrice(record.entryPrice());
        e.setStopLoss(record.stopLoss());
        e.setTakeProfit(record.takeProfit());
        e.setContracts(record.contracts());
        e.setRoutingOutcome(record.routingOutcome() != null ? record.routingOutcome().name() : null);
        e.setRoutingErrorMessage(record.routingErrorMessage());
        repository.save(e);
    }

    @Override
    public List<WtxRsiSignalRecord> findRecent(String instrument, int limit) {
        return repository.findByInstrumentOrderBySignalTsDesc(instrument, PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<WtxRsiSignalRecord> findRecent(String instrument, String timeframe, int limit) {
        return repository.findByInstrumentAndTimeframeOrderBySignalTsDesc(
                        instrument, timeframe, PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    private WtxRsiSignalRecord toDomain(WtxRsiSignalHistoryEntity e) {
        WtxRoutingOutcome outcome = null;
        if (e.getRoutingOutcome() != null) {
            try { outcome = WtxRoutingOutcome.valueOf(e.getRoutingOutcome()); }
            catch (IllegalArgumentException ignored) { /* unknown legacy value */ }
        }
        return new WtxRsiSignalRecord(
                e.getInstrument(),
                e.getTimeframe(),
                e.getSignalTs(),
                WtxRsiSignal.Side.valueOf(e.getSide()),
                WtxRsiSignalRecord.Action.valueOf(e.getAction()),
                e.getWt1(), e.getWt2(), e.getRsi(), e.getRsiSma(),
                e.getChaikin(), Boolean.TRUE.equals(e.getChaikinConfirmed()),
                e.getEntryPrice(), e.getStopLoss(), e.getTakeProfit(),
                e.getContracts() != null ? e.getContracts() : 0,
                outcome,
                e.getRoutingErrorMessage()
        );
    }
}

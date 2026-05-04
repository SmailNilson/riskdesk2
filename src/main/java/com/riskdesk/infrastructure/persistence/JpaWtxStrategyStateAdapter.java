package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.infrastructure.persistence.entity.WtxStrategyStateEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

@Component
public class JpaWtxStrategyStateAdapter implements WtxStrategyStatePort {

    private final JpaWtxStrategyStateRepository repository;

    public JpaWtxStrategyStateAdapter(JpaWtxStrategyStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<WtxStrategyState> load(String instrument) {
        return repository.findById(instrument).map(this::toDomain);
    }

    @Override
    public void save(WtxStrategyState state) {
        WtxStrategyStateEntity entity = repository.findById(state.instrument())
                .orElseGet(WtxStrategyStateEntity::new);
        fromDomain(state, entity);
        repository.save(entity);
    }

    private WtxStrategyState toDomain(WtxStrategyStateEntity e) {
        return new WtxStrategyState(
                e.getInstrument(),
                WtxPosition.valueOf(e.getCurrentDirection()),
                e.getEntryPrice(),
                e.getEntryQty() != null ? e.getEntryQty() : BigDecimal.ZERO,
                e.getDayStartEquity() != null ? e.getDayStartEquity() : BigDecimal.valueOf(10000),
                e.getCurrentEquity() != null ? e.getCurrentEquity() : BigDecimal.valueOf(10000),
                e.getDailyRealizedPnl() != null ? e.getDailyRealizedPnl() : BigDecimal.ZERO,
                e.isMaxLossHit(),
                e.getLastCandleTs(),
                e.getUpdatedAt()
        );
    }

    private void fromDomain(WtxStrategyState s, WtxStrategyStateEntity e) {
        e.setInstrument(s.instrument());
        e.setCurrentDirection(s.currentPosition().name());
        e.setEntryPrice(s.entryPrice());
        e.setEntryQty(s.entryQty());
        e.setDayStartEquity(s.dayStartEquity());
        e.setCurrentEquity(s.currentEquity());
        e.setDailyRealizedPnl(s.dailyRealizedPnl());
        e.setMaxLossHit(s.maxLossHit());
        e.setLastCandleTs(s.lastCandleTs());
        e.setUpdatedAt(Instant.now());
    }
}

package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxProfile;
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
    public Optional<WtxStrategyState> load(String instrument, String timeframe) {
        return repository.findByInstrumentAndTimeframe(instrument, timeframe).map(this::toDomain);
    }

    @Override
    public void save(WtxStrategyState state) {
        WtxStrategyStateEntity entity = repository
                .findByInstrumentAndTimeframe(state.instrument(), state.timeframe())
                .orElseGet(WtxStrategyStateEntity::new);
        fromDomain(state, entity);
        repository.save(entity);
    }

    private WtxStrategyState toDomain(WtxStrategyStateEntity e) {
        WtxProfile profile;
        try {
            profile = e.getActiveProfile() != null ? WtxProfile.valueOf(e.getActiveProfile()) : WtxProfile.BASELINE;
        } catch (IllegalArgumentException ex) {
            profile = WtxProfile.BASELINE;
        }
        return new WtxStrategyState(
                e.getInstrument(),
                e.getTimeframe(),
                WtxPosition.valueOf(e.getCurrentDirection()),
                e.getEntryPrice(),
                e.getEntryQty() != null ? e.getEntryQty() : BigDecimal.ZERO,
                e.getDayStartEquity() != null ? e.getDayStartEquity() : BigDecimal.valueOf(10000),
                e.getCurrentEquity() != null ? e.getCurrentEquity() : BigDecimal.valueOf(10000),
                e.getDailyRealizedPnl() != null ? e.getDailyRealizedPnl() : BigDecimal.ZERO,
                e.isMaxLossHit(),
                e.getLastCandleTs(),
                e.getUpdatedAt(),
                profile,
                Boolean.TRUE.equals(e.getAutoExecutionEnabled()),
                e.getEntryAtr(),
                e.getBestFavorablePrice(),
                e.getTrailingStopPrice(),
                Boolean.TRUE.equals(e.getSwingBiasFilterEnabled()),
                e.getConfiguredOrderQty() != null && e.getConfiguredOrderQty() > 0
                        ? e.getConfiguredOrderQty()
                        : WtxStrategyState.DEFAULT_ORDER_QTY
        );
    }

    private void fromDomain(WtxStrategyState s, WtxStrategyStateEntity e) {
        e.setInstrument(s.instrument());
        e.setTimeframe(s.timeframe());
        e.setCurrentDirection(s.currentPosition().name());
        e.setEntryPrice(s.entryPrice());
        e.setEntryQty(s.entryQty());
        e.setDayStartEquity(s.dayStartEquity());
        e.setCurrentEquity(s.currentEquity());
        e.setDailyRealizedPnl(s.dailyRealizedPnl());
        e.setMaxLossHit(s.maxLossHit());
        e.setLastCandleTs(s.lastCandleTs());
        e.setUpdatedAt(Instant.now());
        e.setActiveProfile(s.activeProfile() != null ? s.activeProfile().name() : WtxProfile.BASELINE.name());
        e.setAutoExecutionEnabled(s.autoExecutionEnabled());
        e.setEntryAtr(s.entryAtr());
        e.setBestFavorablePrice(s.bestFavorablePrice());
        e.setTrailingStopPrice(s.trailingStopPrice());
        e.setSwingBiasFilterEnabled(s.swingBiasFilterEnabled());
        e.setConfiguredOrderQty(s.configuredOrderQty());
    }
}

package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookProfile;
import com.riskdesk.domain.engine.strategy.playbook.PlaybookStrategyState;
import com.riskdesk.domain.engine.strategy.playbook.port.PlaybookStrategyStatePort;
import com.riskdesk.infrastructure.persistence.entity.PlaybookStrategyStateEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

@Component
public class JpaPlaybookStrategyStateAdapter implements PlaybookStrategyStatePort {

    private final JpaPlaybookStrategyStateRepository repository;

    public JpaPlaybookStrategyStateAdapter(JpaPlaybookStrategyStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PlaybookStrategyState> load(String instrument, String timeframe) {
        return repository.findByInstrumentAndTimeframe(instrument, timeframe).map(this::toDomain);
    }

    @Override
    public void save(PlaybookStrategyState state) {
        PlaybookStrategyStateEntity entity = repository
                .findByInstrumentAndTimeframe(state.instrument(), state.timeframe())
                .orElseGet(PlaybookStrategyStateEntity::new);
        fromDomain(state, entity);
        repository.save(entity);
    }

    private PlaybookStrategyState toDomain(PlaybookStrategyStateEntity e) {
        PlaybookProfile profile;
        try {
            profile = e.getActiveProfile() != null ? PlaybookProfile.valueOf(e.getActiveProfile()) : PlaybookProfile.BASELINE;
        } catch (IllegalArgumentException ex) {
            profile = PlaybookProfile.BASELINE;
        }
        return new PlaybookStrategyState(
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
                e.getConfiguredOrderQty() != null && e.getConfiguredOrderQty() > 0
                        ? e.getConfiguredOrderQty()
                        : PlaybookStrategyState.DEFAULT_ORDER_QTY
        );
    }

    private void fromDomain(PlaybookStrategyState s, PlaybookStrategyStateEntity e) {
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
        e.setActiveProfile(s.activeProfile() != null ? s.activeProfile().name() : PlaybookProfile.BASELINE.name());
        e.setAutoExecutionEnabled(s.autoExecutionEnabled());
        e.setEntryAtr(s.entryAtr());
        e.setBestFavorablePrice(s.bestFavorablePrice());
        e.setTrailingStopPrice(s.trailingStopPrice());
        e.setConfiguredOrderQty(s.configuredOrderQty());
    }
}

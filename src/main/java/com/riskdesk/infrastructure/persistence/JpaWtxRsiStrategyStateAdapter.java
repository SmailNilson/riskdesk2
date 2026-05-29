package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiPosition;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiStrategyState;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSwingBias;
import com.riskdesk.domain.engine.strategy.wtxrsi.port.WtxRsiStrategyStatePort;
import com.riskdesk.infrastructure.config.WtxRsiStrategyProperties;
import com.riskdesk.infrastructure.persistence.entity.WtxRsiStrategyStateEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class JpaWtxRsiStrategyStateAdapter implements WtxRsiStrategyStatePort {

    private final JpaWtxRsiStrategyStateRepository repository;
    private final WtxRsiStrategyProperties properties;

    public JpaWtxRsiStrategyStateAdapter(
            JpaWtxRsiStrategyStateRepository repository,
            WtxRsiStrategyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public Optional<WtxRsiStrategyState> load(String instrument, String timeframe) {
        return repository.findByInstrumentAndTimeframe(instrument, timeframe).map(this::toDomain);
    }

    @Override
    public void save(WtxRsiStrategyState state) {
        WtxRsiStrategyStateEntity entity = repository
                .findByInstrumentAndTimeframe(state.instrument(), state.timeframe())
                .orElseGet(WtxRsiStrategyStateEntity::new);
        fromDomain(state, entity);
        repository.save(entity);
    }

    @Override
    public List<WtxRsiStrategyState> findAllOpen() {
        return repository.findByCurrentDirectionNot(WtxRsiPosition.FLAT.name())
                .stream().map(this::toDomain).toList();
    }

    private WtxRsiStrategyState toDomain(WtxRsiStrategyStateEntity e) {
        WtxRsiSwingBias bias = WtxRsiSwingBias.NEUTRAL;
        if (e.getLastSwingBias() != null) {
            try { bias = WtxRsiSwingBias.valueOf(e.getLastSwingBias()); }
            catch (IllegalArgumentException ignored) { /* legacy / unknown value */ }
        }
        return new WtxRsiStrategyState(
                e.getInstrument(),
                e.getTimeframe(),
                WtxRsiPosition.valueOf(e.getCurrentDirection()),
                e.getEntryPrice(),
                e.getEntryQty() != null ? e.getEntryQty() : BigDecimal.ZERO,
                e.getStopLoss(),
                e.getTakeProfit(),
                e.getCumulativeRealizedPnl() != null ? e.getCumulativeRealizedPnl() : BigDecimal.ZERO,
                e.getLastCandleTs(),
                e.getUpdatedAt(),
                Boolean.TRUE.equals(e.getAutoExecutionEnabled()),
                e.getConfiguredOrderQty() != null && e.getConfiguredOrderQty() > 0
                        ? e.getConfiguredOrderQty()
                        : WtxRsiStrategyState.DEFAULT_ORDER_QTY,
                Boolean.TRUE.equals(e.getSwingBiasFilterEnabled()),
                bias,
                // Null (pre-migration rows / never set) inherits the global default.
                e.getChaikinRequired() != null
                        ? e.getChaikinRequired()
                        : properties.isChaikinRequired()
        );
    }

    private void fromDomain(WtxRsiStrategyState s, WtxRsiStrategyStateEntity e) {
        e.setInstrument(s.instrument());
        e.setTimeframe(s.timeframe());
        e.setCurrentDirection(s.currentPosition().name());
        e.setEntryPrice(s.entryPrice());
        e.setEntryQty(s.entryQty());
        e.setStopLoss(s.stopLoss());
        e.setTakeProfit(s.takeProfit());
        e.setCumulativeRealizedPnl(s.cumulativeRealizedPnl());
        e.setLastCandleTs(s.lastCandleTs());
        e.setUpdatedAt(Instant.now());
        e.setAutoExecutionEnabled(s.autoExecutionEnabled());
        e.setConfiguredOrderQty(s.configuredOrderQty());
        e.setSwingBiasFilterEnabled(s.swingBiasFilterEnabled());
        e.setLastSwingBias(s.lastSwingBias() != null ? s.lastSwingBias().name() : null);
        e.setChaikinRequired(s.chaikinRequired());
    }
}

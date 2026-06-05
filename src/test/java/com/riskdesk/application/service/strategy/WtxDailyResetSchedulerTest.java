package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WtxDailyResetSchedulerTest {

    /** In-memory state port keyed by instrument|timeframe. */
    private static final class FakeStatePort implements WtxStrategyStatePort {
        final Map<String, WtxStrategyState> store = new HashMap<>();

        @Override public Optional<WtxStrategyState> load(String instrument, String timeframe) {
            return Optional.ofNullable(store.get(instrument + "|" + timeframe));
        }
        @Override public void save(WtxStrategyState state) {
            store.put(state.instrument() + "|" + state.timeframe(), state);
        }
    }

    private static WtxStrategyProperties props(boolean enabled, boolean resetEnabled) {
        WtxStrategyProperties p = new WtxStrategyProperties();
        p.setEnabled(enabled);
        p.setInstruments(List.of("MNQ"));
        p.setTimeframes(List.of("5m"));
        p.setDailyResetEnabled(resetEnabled);
        return p;
    }

    // States under test carry no pending close, so the settler is a pass-through (mock repo never queried).
    private static WtxClosePnlSettler settler() {
        return new WtxClosePnlSettler(Mockito.mock(TradeExecutionRepositoryPort.class));
    }

    @Test
    void clearsMaxLossLatch_onBlockedState() {
        FakeStatePort port = new FakeStatePort();
        WtxStrategyState blocked = WtxStrategyState.initial("MNQ", "5m", BigDecimal.valueOf(10_000))
                .withMaxLossHit();
        assertTrue(blocked.maxLossHit());
        port.save(blocked);

        int n = new WtxDailyResetScheduler(port, props(true, true), settler()).resetAllDailyStates();

        assertEquals(1, n);
        assertFalse(port.load("MNQ", "5m").orElseThrow().maxLossHit(),
                "17:00 ET reset must clear the max-loss latch so the new day is tradeable");
    }

    @Test
    void respectsDisabledFlag_latchStays() {
        FakeStatePort port = new FakeStatePort();
        port.save(WtxStrategyState.initial("MNQ", "5m", BigDecimal.valueOf(10_000)).withMaxLossHit());

        int n = new WtxDailyResetScheduler(port, props(true, false), settler()).resetAllDailyStates();

        assertEquals(0, n);
        assertTrue(port.load("MNQ", "5m").orElseThrow().maxLossHit(),
                "daily-reset-enabled=false must leave the latch untouched");
    }

    @Test
    void noOp_whenWtxDisabled() {
        FakeStatePort port = new FakeStatePort();
        port.save(WtxStrategyState.initial("MNQ", "5m", BigDecimal.valueOf(10_000)).withMaxLossHit());

        assertEquals(0, new WtxDailyResetScheduler(port, props(false, true), settler()).resetAllDailyStates());
    }
}

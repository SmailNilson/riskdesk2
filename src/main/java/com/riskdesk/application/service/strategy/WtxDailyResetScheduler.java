package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Time-driven daily reset for WTX strategy states at the 17:00 ET CME day boundary.
 *
 * <p>The {@code maxLossHit} latch (and the day's realized P&amp;L baseline) is normally cleared by
 * {@code WtxStrategyService} when the first candle of the new trading day is processed. If a state
 * hit its daily max-loss and then no fresh candle crosses the boundary (feed lag, restart, a quiet
 * instrument at session open), the latch stays set and the strategy is "blocked on a new day".
 *
 * <p>This scheduler is the safety net: at 17:00 America/New_York (DST-safe) it rebaselines equity
 * and clears the latch for every configured (instrument, timeframe), so a stuck state self-heals
 * without waiting for a candle. Gated by {@code riskdesk.wtx.daily-reset-enabled}.
 */
@Component
@ConditionalOnProperty(name = "riskdesk.wtx.enabled", havingValue = "true")
public class WtxDailyResetScheduler {

    private static final Logger log = LoggerFactory.getLogger(WtxDailyResetScheduler.class);

    private final WtxStrategyStatePort statePort;
    private final WtxStrategyProperties properties;
    private final WtxClosePnlSettler closePnlSettler;

    public WtxDailyResetScheduler(WtxStrategyStatePort statePort, WtxStrategyProperties properties,
                                  WtxClosePnlSettler closePnlSettler) {
        this.statePort = statePort;
        this.properties = properties;
        this.closePnlSettler = closePnlSettler;
    }

    /** Fires at 17:00 ET every day — the CME session boundary. */
    @Scheduled(cron = "0 0 17 * * *", zone = "America/New_York")
    public void resetDailyStatesAtSessionBoundary() {
        resetAllDailyStates();
    }

    /**
     * Rebaseline equity + clear the max-loss latch for every configured WTX state. No-op when WTX
     * is disabled or the daily reset is turned off. Position side and entry are preserved — only the
     * day P&amp;L baseline and the latch are touched (a position open across 17:00 ET carries over).
     *
     * @return number of states rebaselined (visible for tests / manual invocation).
     */
    public int resetAllDailyStates() {
        if (!properties.isEnabled()) {
            return 0;
        }
        WtxConfig config = properties.toConfig();
        if (!config.dailyResetEnabled()) {
            log.debug("WTX daily reset disabled (riskdesk.wtx.daily-reset-enabled=false) — skipping");
            return 0;
        }
        int reset = 0;
        for (String instrument : config.instruments()) {
            for (String timeframe : config.timeframes()) {
                reset += resetOne(instrument, timeframe);
            }
        }
        // Variant panels carry their own daily equity / max-loss latch — rebaseline them too.
        for (var variant : properties.getVariants() == null
                ? java.util.List.<com.riskdesk.infrastructure.config.WtxStrategyProperties.Variant>of()
                : properties.getVariants()) {
            reset += resetOne(variant.getInstrument(), variant.getPanelKey());
        }
        log.info("WTX daily reset @17:00 ET — rebaselined {} state(s)", reset);
        return reset;
    }

    private int resetOne(String instrument, String timeframe) {
        Optional<WtxStrategyState> loaded = statePort.load(instrument, timeframe);
        if (loaded.isEmpty()) {
            return 0;
        }
        // Settle any optimistic close P&L against execution-row truth BEFORE the reset archives
        // the day's realized P&L — mirrors WtxStrategyService's settle-then-day-reset invariant,
        // so an unconfirmed pendingClosePnl is never silently dropped by withDayReset.
        WtxStrategyState state = closePnlSettler.settle(loaded.get());
        boolean wasBlocked = state.maxLossHit();
        statePort.save(state.withDayReset(state.currentEquity()));
        if (wasBlocked) {
            log.info("WTX [{} {}] 17:00 ET reset — max-loss latch cleared (was blocked); "
                    + "equity rebaselined to {}", instrument, timeframe, state.currentEquity());
        }
        return 1;
    }
}

package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Enforces mandatory position closure before the end of the NY trading session.
 * Mirrors Pine Script's forceCloseWindow logic but driven by a scheduler rather
 * than bar evaluation, to handle gaps in candle delivery.
 */
@Component
@ConditionalOnProperty(name = "riskdesk.wtx.enabled", havingValue = "true")
public class WtxNySessionCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(WtxNySessionCloseScheduler.class);
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private final WtxStrategyService wtxStrategyService;
    private final WtxStrategyProperties properties;

    public WtxNySessionCloseScheduler(WtxStrategyService wtxStrategyService,
                                       WtxStrategyProperties properties) {
        this.wtxStrategyService = wtxStrategyService;
        this.properties = properties;
    }

    /** Checks every minute during the 15h–16h59 ET window (Mon–Fri). */
    @Scheduled(cron = "0 * 15,16 * * MON-FRI", zone = "America/New_York")
    public void checkNyClose() {
        WtxConfig config = properties.toConfig();
        if (!config.forceCloseNy()) return;

        ZonedDateTime nyNow = ZonedDateTime.now(NY);
        int nowMin = nyNow.getHour() * 60 + nyNow.getMinute();

        if (nowMin >= config.nyCloseLimit() && nowMin <= config.nySessionEndMinutes()) {
            log.info("WTX NY session close window reached ({}:{}) — force-closing all positions",
                    nyNow.getHour(), nyNow.getMinute());
            wtxStrategyService.forceCloseAll("Fermeture obligatoire avant fin session NY");
        }
    }
}

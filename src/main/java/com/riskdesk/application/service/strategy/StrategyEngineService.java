package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.application.service.PlaybookService;
import com.riskdesk.domain.engine.strategy.StrategyEngine;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Application-layer orchestrator for the new strategy engine. Builds the three
 * context layers from live services, assembles a {@link StrategyInput}, and
 * delegates to the pure-domain {@link StrategyEngine}.
 *
 * <p><b>Coexistence rule</b>: while the legacy {@code PlaybookService} is still live,
 * this service is <i>read-only</i> — it never touches {@code ExecutionManagerService},
 * {@code SignalConfluenceBuffer}, or the {@code mentor_signal_reviews} table. That
 * isolation is the whole point of slicing the migration.
 */
@Service
public class StrategyEngineService {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngineService.class);

    private final IndicatorService indicatorService;
    private final PlaybookService playbookService;
    private final MarketContextBuilder contextBuilder;
    private final ZoneContextBuilder zoneBuilder;
    private final TriggerContextBuilder triggerBuilder;
    private final StrategyEngine engine;

    public StrategyEngineService(IndicatorService indicatorService,
                                  PlaybookService playbookService,
                                  MarketContextBuilder contextBuilder,
                                  ZoneContextBuilder zoneBuilder,
                                  TriggerContextBuilder triggerBuilder,
                                  StrategyEngine engine) {
        this.indicatorService = indicatorService;
        this.playbookService = playbookService;
        this.contextBuilder = contextBuilder;
        this.zoneBuilder = zoneBuilder;
        this.triggerBuilder = triggerBuilder;
        this.engine = engine;
    }

    public StrategyDecision evaluate(Instrument instrument, String timeframe) {
        IndicatorSnapshot snapshot = indicatorService.computeSnapshot(instrument, timeframe);
        // ATR is reused from PlaybookService to keep the ATR source consistent across
        // the two engines during coexistence (same number on both panels).
        BigDecimal atr = playbookService.computeAtr(instrument, timeframe);

        MarketContext context = contextBuilder.build(instrument, timeframe, snapshot, atr);
        ZoneContext zones = zoneBuilder.build(snapshot, atr);
        TriggerContext trigger = triggerBuilder.build(instrument, timeframe, snapshot);

        StrategyInput input = new StrategyInput(context, zones, trigger, null);
        StrategyDecision decision = engine.evaluate(input);

        log.debug("Strategy {} {} — playbook={}, decision={}, score={}",
            instrument, timeframe,
            decision.candidatePlaybookId().orElse("NONE"),
            decision.decision(), String.format("%.1f", decision.finalScore()));
        return decision;
    }
}

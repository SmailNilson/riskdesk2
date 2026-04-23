package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.service.AgentOrchestratorService;
import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.application.service.PlaybookService;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.engine.playbook.agent.AgentContext;
import com.riskdesk.domain.engine.playbook.model.FinalVerdict;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.model.Instrument;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/playbook")
public class PlaybookController {

    private final PlaybookService playbookService;
    private final AgentOrchestratorService orchestratorService;
    private final IndicatorService indicatorService;

    public PlaybookController(PlaybookService playbookService,
                              AgentOrchestratorService orchestratorService,
                              IndicatorService indicatorService) {
        this.playbookService = playbookService;
        this.orchestratorService = orchestratorService;
        this.indicatorService = indicatorService;
    }

    @GetMapping("/{instrument}/{timeframe}")
    public ResponseEntity<PlaybookEvaluation> getPlaybook(
            @PathVariable Instrument instrument,
            @PathVariable String timeframe) {
        PlaybookEvaluation evaluation = playbookService.evaluate(instrument, timeframe);
        return ResponseEntity.ok(evaluation);
    }

    @GetMapping("/{instrument}/{timeframe}/full")
    public ResponseEntity<FinalVerdict> getFullPlaybook(
            @PathVariable Instrument instrument,
            @PathVariable String timeframe) {
        PlaybookEvaluation playbook = playbookService.evaluate(instrument, timeframe);
        IndicatorSnapshot snapshot = indicatorService.computeSnapshot(instrument, timeframe);
        // Compute the real ATR (NOT BigDecimal.ONE) — required for correct size
        // multipliers, trap-zone detection and zone_size_atr in ZoneQualityAIAgent.
        BigDecimal atr = playbookService.computeAtr(instrument, timeframe);
        // Use the playbook-aware buildContext overload so ZoneQualityAIAgent targets
        // the OB/FVG referenced by the best setup, not just the first active zone.
        AgentContext context = orchestratorService.buildContext(
            instrument, timeframe, snapshot,
            atr != null ? atr : BigDecimal.ONE,
            playbook);
        FinalVerdict verdict = orchestratorService.orchestrate(playbook, context);
        return ResponseEntity.ok(verdict);
    }
}

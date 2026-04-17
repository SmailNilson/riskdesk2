package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.PlaybookService;
import com.riskdesk.application.service.strategy.StrategyEngineService;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.presentation.dto.DecisionComparisonView;
import com.riskdesk.presentation.dto.StrategyDecisionView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only endpoint for the new probabilistic strategy engine.
 *
 * <p>Deliberately separate from {@link PlaybookController} so the legacy 7/7
 * playbook and the new engine can be compared side-by-side on the frontend during
 * the migration. No execution, no persistence, no side effects — just a fresh
 * evaluation on every call.
 */
@RestController
@RequestMapping("/api/strategy")
public class StrategyController {

    private final StrategyEngineService strategyEngineService;
    private final PlaybookService playbookService;

    public StrategyController(StrategyEngineService strategyEngineService,
                               PlaybookService playbookService) {
        this.strategyEngineService = strategyEngineService;
        this.playbookService = playbookService;
    }

    @GetMapping("/{instrument}/{timeframe}")
    public ResponseEntity<StrategyDecisionView> evaluate(
            @PathVariable Instrument instrument,
            @PathVariable String timeframe) {
        StrategyDecision decision = strategyEngineService.evaluate(instrument, timeframe);
        return ResponseEntity.ok(StrategyDecisionView.from(decision));
    }

    /**
     * A/B comparison endpoint — evaluates both engines on the same (instrument,
     * timeframe) and returns their outputs side-by-side plus a coarse
     * {@link DecisionComparisonView.Agreement} classification. Used by the
     * frontend comparison view and by operators during the coexistence window.
     */
    @GetMapping("/{instrument}/{timeframe}/compare")
    public ResponseEntity<DecisionComparisonView> compare(
            @PathVariable Instrument instrument,
            @PathVariable String timeframe) {
        PlaybookEvaluation legacy = playbookService.evaluate(instrument, timeframe);
        StrategyDecision decision = strategyEngineService.evaluate(instrument, timeframe);
        return ResponseEntity.ok(DecisionComparisonView.build(
            instrument.name(), timeframe, legacy, decision));
    }
}

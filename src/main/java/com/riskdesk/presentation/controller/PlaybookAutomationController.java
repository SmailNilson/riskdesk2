package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.PlaybookAutomationDecisionView;
import com.riskdesk.application.dto.PlaybookAutomationProfitabilitySummaryView;
import com.riskdesk.application.dto.PlaybookAutomationStateView;
import com.riskdesk.application.dto.PlaybookAutomationUpdateRequest;
import com.riskdesk.application.service.PlaybookAutomationService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.playbook.automation.PlaybookAutomationState;
import com.riskdesk.domain.playbook.automation.PlaybookDecision;
import com.riskdesk.domain.simulation.TradeSimulation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/playbook/automation")
public class PlaybookAutomationController {

    private final PlaybookAutomationService automationService;

    public PlaybookAutomationController(PlaybookAutomationService automationService) {
        this.automationService = automationService;
    }

    @GetMapping("/{instrument}/{timeframe}")
    public ResponseEntity<PlaybookAutomationStateView> getState(@PathVariable String instrument,
                                                                @PathVariable String timeframe) {
        return ResponseEntity.ok(PlaybookAutomationStateView.from(
            automationService.getState(instrument, timeframe)));
    }

    @PutMapping("/{instrument}/{timeframe}")
    public ResponseEntity<?> updateState(@PathVariable String instrument,
                                         @PathVariable String timeframe,
                                         @RequestBody(required = false) PlaybookAutomationUpdateRequest request) {
        PlaybookAutomationUpdateRequest body = request == null
            ? new PlaybookAutomationUpdateRequest(null, null, null, null, null, null)
            : request;
        try {
            PlaybookAutomationState state = automationService.updateState(
                instrument,
                timeframe,
                body.paperEnabled(),
                body.autoIbkrEnabled(),
                body.quantity(),
                body.brokerAccountId());
            return ResponseEntity.ok(PlaybookAutomationStateView.from(state));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{instrument}/{timeframe}/decisions")
    public ResponseEntity<List<PlaybookAutomationDecisionView>> getDecisions(@PathVariable String instrument,
                                                                             @PathVariable String timeframe,
                                                                             @RequestParam(defaultValue = "20") int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        PlaybookAutomationState state = automationService.getState(instrument, timeframe);
        List<PlaybookDecision> decisions = automationService.recentDecisions(instrument, timeframe, safeLimit);
        ProfitabilityAccumulator accumulator = new ProfitabilityAccumulator();
        List<DecisionWithSimulation> rows = decisions.stream()
            .map(decision -> {
                TradeSimulation sim = automationService.simulationFor(decision).orElse(null);
                BigDecimal pnl = simulationPnl(decision, sim);
                accumulator.accept(decision, sim, pnl);
                return new DecisionWithSimulation(decision, sim, pnl);
            })
            .toList();
        PlaybookAutomationProfitabilitySummaryView summary = accumulator.toView(decisions.size());
        return ResponseEntity.ok(rows.stream()
            .map(row -> PlaybookAutomationDecisionView.from(
                row.decision(),
                state.paperThreshold(),
                state.liveThreshold(),
                state.autoExecutionEnabled(),
                state.configuredOrderQty(),
                state.brokerAccountId(),
                row.simulation() == null ? null : row.simulation().simulationStatus(),
                row.pnl(),
                summary))
            .toList());
    }

    private static BigDecimal simulationPnl(PlaybookDecision decision, TradeSimulation sim) {
        if (decision == null || sim == null || sim.simulationStatus() == null || decision.entryPrice() == null) {
            return null;
        }
        BigDecimal exit = null;
        if (sim.trailingExitPrice() != null) {
            exit = sim.trailingExitPrice();
        } else if (sim.simulationStatus() == TradeSimulationStatus.WIN) {
            exit = decision.takeProfit1();
        } else if (sim.simulationStatus() == TradeSimulationStatus.LOSS) {
            exit = decision.stopLoss();
        } else if (sim.simulationStatus() == TradeSimulationStatus.MISSED
            || sim.simulationStatus() == TradeSimulationStatus.CANCELLED
            || sim.simulationStatus() == TradeSimulationStatus.REVERSED) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (exit == null) {
            return null;
        }
        BigDecimal points = "SHORT".equalsIgnoreCase(decision.direction())
            ? decision.entryPrice().subtract(exit)
            : exit.subtract(decision.entryPrice());
        try {
            Instrument instrument = Instrument.valueOf(decision.instrument());
            return points.multiply(instrument.getContractMultiplier()).setScale(2, RoundingMode.HALF_UP);
        } catch (IllegalArgumentException e) {
            return points.setScale(2, RoundingMode.HALF_UP);
        }
    }

    private record DecisionWithSimulation(PlaybookDecision decision, TradeSimulation simulation, BigDecimal pnl) {
    }

    private static final class ProfitabilityAccumulator {
        private int paperCount;
        private int liveCount;
        private int wins;
        private int losses;
        private int missed;
        private BigDecimal grossWins = BigDecimal.ZERO;
        private BigDecimal grossLosses = BigDecimal.ZERO;
        private BigDecimal totalPnl = BigDecimal.ZERO;
        private int pnlCount;

        void accept(PlaybookDecision decision, TradeSimulation sim, BigDecimal pnl) {
            if (sim != null) {
                paperCount++;
                if (sim.simulationStatus() == TradeSimulationStatus.WIN) {
                    wins++;
                } else if (sim.simulationStatus() == TradeSimulationStatus.LOSS) {
                    losses++;
                } else if (sim.simulationStatus() == TradeSimulationStatus.MISSED) {
                    missed++;
                }
            }
            if (decision != null && decision.executionId() != null) {
                liveCount++;
            }
            if (pnl != null) {
                pnlCount++;
                totalPnl = totalPnl.add(pnl);
                if (pnl.signum() > 0) {
                    grossWins = grossWins.add(pnl);
                } else if (pnl.signum() < 0) {
                    grossLosses = grossLosses.add(pnl.abs());
                }
            }
        }

        PlaybookAutomationProfitabilitySummaryView toView(int totalDecisions) {
            int resolved = wins + losses;
            double winRate = resolved == 0 ? 0.0 : (double) wins / resolved;
            BigDecimal average = pnlCount == 0
                ? null
                : totalPnl.divide(BigDecimal.valueOf(pnlCount), 2, RoundingMode.HALF_UP);
            BigDecimal profitFactor = grossLosses.signum() == 0
                ? (grossWins.signum() > 0 ? grossWins : null)
                : grossWins.divide(grossLosses, 4, RoundingMode.HALF_UP);
            return new PlaybookAutomationProfitabilitySummaryView(
                totalDecisions,
                paperCount,
                liveCount,
                wins,
                losses,
                missed,
                winRate,
                pnlCount == 0 ? null : totalPnl.setScale(2, RoundingMode.HALF_UP),
                average,
                profitFactor,
                null
            );
        }
    }
}

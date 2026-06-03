package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Safety net for the Quant 7-Gates Auto-IBKR mirror: re-flattens orphaned
 * positions whose paper simulation has already closed but whose live IBKR
 * position is still open after a failed or skipped close routing.
 *
 * <p>Without this, a single transient close failure (IBKR briefly disabled, a
 * rejected flatten) would orphan the position forever — the paper harness only
 * processes OPEN simulations, so it never retries the close once the paper row
 * is terminal (Codex P1-A). The reconciler closes that gap by enforcing the
 * invariant directly against broker-truth: a non-terminal {@code QUANT_SIM_AUTO}
 * row with an entry already filled ({@code ACTIVE}) must have a matching OPEN
 * paper simulation — otherwise the position is an orphan and is flattened.</p>
 *
 * <p>Idempotent: {@link Quant7GatesExecutionBridge#flatten} no-ops a row already
 * {@code EXIT_SUBMITTED} or terminal, so re-runs never double-submit. Only
 * {@code ACTIVE} rows (entry filled, a real position open) are reconciled here;
 * a resting unfilled entry is a separate (cancel-vs-flatten) concern owned by
 * the stale-entry path.</p>
 */
@Component
@Profile("!test")
public class QuantSimFlattenReconciler {

    private static final Logger log = LoggerFactory.getLogger(QuantSimFlattenReconciler.class);

    private final TradeExecutionRepositoryPort executionRepository;
    private final Quant7GatesSimulationService simulationService;
    private final QuantSimExecutionProperties props;
    private final LivePricePort livePricePort;
    private final ObjectProvider<Quant7GatesExecutionBridge> bridgeProvider;

    public QuantSimFlattenReconciler(TradeExecutionRepositoryPort executionRepository,
                                     Quant7GatesSimulationService simulationService,
                                     QuantSimExecutionProperties props,
                                     LivePricePort livePricePort,
                                     ObjectProvider<Quant7GatesExecutionBridge> bridgeProvider) {
        this.executionRepository = executionRepository;
        this.simulationService = simulationService;
        this.props = props;
        this.livePricePort = livePricePort;
        this.bridgeProvider = bridgeProvider;
    }

    @Scheduled(fixedDelayString = "${riskdesk.quant.sim-exec.reconcile-interval-ms:30000}",
               initialDelayString = "${riskdesk.quant.sim-exec.reconcile-initial-delay-ms:45000}")
    public void reconcile() {
        if (!props.isEnabled()) return;
        Quant7GatesExecutionBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge == null) return;

        List<TradeExecutionRecord> active = executionRepository
            .findByTriggerSourceAndStatus(ExecutionTriggerSource.QUANT_SIM_AUTO, ExecutionStatus.ACTIVE);
        for (TradeExecutionRecord row : active) {
            try {
                Instrument instrument = parseInstrument(row.getInstrument());
                Quant7GatesSimulation.Direction direction = parseDirection(row.getAction());
                if (instrument == null || direction == null) continue;
                // Still legitimately mirrored — the paper sim that opened it is open.
                if (simulationService.hasOpenSimulation(instrument, direction)) continue;

                log.warn("quant-sim orphan detected — flattening executionId={} instrument={} action={} "
                        + "(paper sim closed but broker position still open)",
                    row.getId(), row.getInstrument(), row.getAction());
                bridge.flatten(row, currentPrice(instrument));
            } catch (RuntimeException e) {
                log.warn("quant-sim flatten reconcile failed for executionId={}: {}", row.getId(), e.toString());
                // Keep going — one bad row must not stop the rest of the batch.
            }
        }
    }

    /** Current market price for a marketable close limit, or null when unavailable. */
    private BigDecimal currentPrice(Instrument instrument) {
        try {
            return livePricePort.current(instrument)
                .map(s -> BigDecimal.valueOf(s.price()))
                .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instrument parseInstrument(String name) {
        try {
            return name == null ? null : Instrument.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Quant7GatesSimulation.Direction parseDirection(String action) {
        if ("LONG".equalsIgnoreCase(action)) return Quant7GatesSimulation.Direction.LONG;
        if ("SHORT".equalsIgnoreCase(action)) return Quant7GatesSimulation.Direction.SHORT;
        return null;
    }
}

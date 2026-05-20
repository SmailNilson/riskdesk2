package com.riskdesk.application.quant.adapter;

import com.riskdesk.application.service.strategy.StrategyEngineService;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.structure.StrategyPort;
import com.riskdesk.domain.quant.structure.StrategyVotes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bridges the Quant {@link StrategyPort} onto the existing
 * {@link StrategyEngineService} for the {@code 5m} timeframe — the only one
 * used by the structural filters.
 *
 * <p>Defensive: the strategy engine can throw when no candle data exists
 * (cold start) — we swallow and return {@link Optional#empty()} so the
 * structural filters degrade to "no strategy-driven block".</p>
 */
@Component
public class StrategyPortAdapter implements StrategyPort {

    private static final Logger log = LoggerFactory.getLogger(StrategyPortAdapter.class);
    private static final String STRUCTURAL_TIMEFRAME = "5m";

    private final ObjectProvider<StrategyEngineService> strategyServiceProvider;

    public StrategyPortAdapter(ObjectProvider<StrategyEngineService> strategyServiceProvider) {
        this.strategyServiceProvider = strategyServiceProvider;
    }

    @Override
    public Optional<StrategyVotes> votes5m(Instrument instrument) {
        StrategyEngineService svc = strategyServiceProvider.getIfAvailable();
        if (svc == null) return Optional.empty();
        try {
            StrategyDecision d = svc.evaluate(instrument, STRUCTURAL_TIMEFRAME);
            if (d == null) return Optional.empty();
            List<StrategyVotes.Vote> votes = new ArrayList<>(d.votes().size());
            for (AgentVote v : d.votes()) {
                votes.add(new StrategyVotes.Vote(v.agentId(), v.evidence()));
            }
            String decision = d.decision() != null ? d.decision().name() : null;
            return Optional.of(new StrategyVotes(decision, votes, d.vetoReasons()));
        } catch (RuntimeException e) {
            log.debug("strategy 5m unavailable for {}: {}", instrument, e.toString());
            return Optional.empty();
        }
    }
}

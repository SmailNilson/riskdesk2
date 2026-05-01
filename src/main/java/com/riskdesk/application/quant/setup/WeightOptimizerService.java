package com.riskdesk.application.quant.setup;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.setup.SetupPhase;
import com.riskdesk.domain.quant.setup.SetupRecommendation;
import com.riskdesk.domain.quant.setup.WeightConfiguration;
import com.riskdesk.domain.quant.setup.port.SetupRepositoryPort;
import com.riskdesk.domain.quant.setup.port.WeightConfigPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Phase 4 — experimental weight optimizer. Disabled by default.
 *
 * <p>When enabled ({@code riskdesk.setup.weight-optimizer.enabled=true})
 * this service periodically inspects closed setups and nudges the per-instrument
 * weight configuration towards the dimensions that better predict WIN vs LOSS
 * outcomes. The algorithm is intentionally simple (gradient-free hill-climbing)
 * to avoid over-fitting on a small sample.</p>
 *
 * <p>Current implementation: stub that logs analytics but does not yet modify
 * weights. The full gradient step is the next slice.</p>
 */
@Service
public class WeightOptimizerService {

    private static final Logger log = LoggerFactory.getLogger(WeightOptimizerService.class);

    private static final double LEARNING_RATE = 0.02;
    private static final int    MIN_SAMPLE    = 20;

    @Value("${riskdesk.setup.weight-optimizer.enabled:false}")
    private boolean enabled;

    private final SetupRepositoryPort repositoryPort;
    private final WeightConfigPort    weightConfigPort;

    public WeightOptimizerService(SetupRepositoryPort repositoryPort,
                                   WeightConfigPort weightConfigPort) {
        this.repositoryPort   = repositoryPort;
        this.weightConfigPort = weightConfigPort;
    }

    /**
     * Runs at 02:00 ET every day. Analyses the closed setups from the last
     * 7 days and logs a recommendation. Does NOT write weights unless the
     * stub is replaced with the gradient step.
     */
    @Scheduled(cron = "0 0 7 * * *", zone = "America/New_York")
    public void optimise() {
        if (!enabled) return;
        log.info("weight-optimizer starting daily run");
        for (Instrument instrument : Instrument.values()) {
            try {
                runForInstrument(instrument);
            } catch (RuntimeException e) {
                log.warn("weight-optimizer failed instrument={}: {}", instrument, e.toString());
            }
        }
    }

    private void runForInstrument(Instrument instrument) {
        List<SetupRecommendation> closed = repositoryPort.findActiveByInstrument(instrument)
            .stream()
            .filter(s -> s.phase() == SetupPhase.CLOSED)
            .toList();

        if (closed.size() < MIN_SAMPLE) {
            log.debug("weight-optimizer instrument={} insufficient sample={} (need {})",
                instrument, closed.size(), MIN_SAMPLE);
            return;
        }

        WeightConfiguration current = weightConfigPort.load(instrument);

        // Stub analytics: log average score of closed setups
        double avgScore = closed.stream()
            .mapToDouble(SetupRecommendation::finalScore)
            .average()
            .orElse(0.0);

        log.info("weight-optimizer instrument={} closedSetups={} avgScore={:.2f} weights={}",
            instrument, closed.size(), avgScore, current);

        // TODO: Implement gradient step — compare WIN vs LOSS gate profiles
        // to identify which gates are most predictive, then nudge weights
        // using LEARNING_RATE. Save via weightConfigPort.save().
    }
}

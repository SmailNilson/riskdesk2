package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.port.LivePricePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Force-closes every mirrored Quant 7-Gates position before the CME daily break.
 *
 * <p>The mirror places no resident broker stop (entry-Limit only), so an open
 * position must never be carried through the close unmanaged. This scheduler
 * flattens all {@code ACTIVE} {@code QUANT_SIM_AUTO} rows in the minutes before
 * the 17:00 ET break (default every minute 16:55–16:59 ET, cron-configurable),
 * independently of the paper simulation — a real position is closed even if its
 * paper twin is still open. Each flatten uses a marketable limit (current price
 * crossed toward the fill side) so a losing position is actually closed.</p>
 *
 * <p>Repeating the sweep every minute catches a resting entry that fills in the
 * final minutes (the bridge stops arming NEW entries after the pre-close cutoff,
 * so the only late fills are pre-existing resting DAY orders, which are swept
 * here or expire at the break). Only {@code ACTIVE} (filled) positions are
 * flattened; idempotent via the bridge's EXIT_SUBMITTED guard.</p>
 */
@Component
@Profile("!test")
public class QuantSimSessionCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuantSimSessionCloseScheduler.class);

    private final TradeExecutionRepositoryPort executionRepository;
    private final QuantSimExecutionProperties props;
    private final LivePricePort livePricePort;
    private final ObjectProvider<Quant7GatesExecutionBridge> bridgeProvider;

    public QuantSimSessionCloseScheduler(TradeExecutionRepositoryPort executionRepository,
                                         QuantSimExecutionProperties props,
                                         LivePricePort livePricePort,
                                         ObjectProvider<Quant7GatesExecutionBridge> bridgeProvider) {
        this.executionRepository = executionRepository;
        this.props = props;
        this.livePricePort = livePricePort;
        this.bridgeProvider = bridgeProvider;
    }

    @Scheduled(cron = "${riskdesk.quant.sim-exec.force-close-cron:0 55-59 16 * * MON-FRI}",
               zone = "America/New_York")
    public void forceCloseBeforeSessionEnd() {
        if (!props.isEnabled() || !props.isForceCloseEnabled()) return;
        Quant7GatesExecutionBridge bridge = bridgeProvider.getIfAvailable();
        if (bridge == null) return;

        List<TradeExecutionRecord> open = executionRepository
            .findByTriggerSourceAndStatus(ExecutionTriggerSource.QUANT_SIM_AUTO, ExecutionStatus.ACTIVE);
        if (open.isEmpty()) return;

        log.info("quant-sim NY session-close — force-flattening {} open QUANT_SIM_AUTO position(s)", open.size());
        for (TradeExecutionRecord row : open) {
            try {
                bridge.flatten(row, currentPrice(parseInstrument(row.getInstrument())));
            } catch (RuntimeException e) {
                log.warn("quant-sim force-close failed for executionId={}: {}", row.getId(), e.toString());
                // Keep going — the next sweep / flatten reconciler retries any that fail here.
            }
        }
    }

    /** Current market price for a marketable close limit, or null when unavailable. */
    private BigDecimal currentPrice(Instrument instrument) {
        if (instrument == null) return null;
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
}

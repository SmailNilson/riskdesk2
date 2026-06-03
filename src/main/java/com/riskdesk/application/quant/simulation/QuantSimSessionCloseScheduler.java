package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Force-closes every mirrored Quant 7-Gates position before the CME daily break.
 *
 * <p>The mirror places no resident broker stop (entry-Limit only), so an open
 * position must never be carried through the close unmanaged. This scheduler
 * flattens all {@code ACTIVE} {@code QUANT_SIM_AUTO} rows shortly before the
 * 17:00 ET break (default 16:55 ET, cron-configurable), independently of the
 * paper simulation — a real position is closed even if its paper twin is still
 * open.</p>
 *
 * <p>Only {@code ACTIVE} (filled) positions are flattened. Unfilled resting
 * entries are DAY limit orders that expire at the break on their own, so they
 * need no opposite order here. Idempotent via the bridge's EXIT_SUBMITTED guard.</p>
 */
@Component
@Profile("!test")
public class QuantSimSessionCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuantSimSessionCloseScheduler.class);

    private final TradeExecutionRepositoryPort executionRepository;
    private final QuantSimExecutionProperties props;
    private final ObjectProvider<Quant7GatesExecutionBridge> bridgeProvider;

    public QuantSimSessionCloseScheduler(TradeExecutionRepositoryPort executionRepository,
                                         QuantSimExecutionProperties props,
                                         ObjectProvider<Quant7GatesExecutionBridge> bridgeProvider) {
        this.executionRepository = executionRepository;
        this.props = props;
        this.bridgeProvider = bridgeProvider;
    }

    @Scheduled(cron = "${riskdesk.quant.sim-exec.force-close-cron:0 55 16 * * MON-FRI}",
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
                bridge.flatten(row);
            } catch (RuntimeException e) {
                log.warn("quant-sim force-close failed for executionId={}: {}", row.getId(), e.toString());
                // Keep going — the flatten reconciler retries any that fail here.
            }
        }
    }
}

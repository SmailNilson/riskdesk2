package com.riskdesk.application.quant.positions;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.port.LivePricePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * App-side auto-exit for MANUAL chart trades. Polls {@code ACTIVE} manual rows and, when the live
 * price crosses the row's VIRTUAL stop-loss or take-profit, closes the position through the very same
 * flatten path the operator's "Fermer" button uses ({@link ActivePositionsService#closePosition}).
 *
 * <p>This makes the chart's virtual SL/TP actually protect the position — but ONLY while the backend
 * is running and connected. It is NOT a broker bracket order: a backend outage / disconnect leaves the
 * position unprotected at IBKR. That residual gap is what real OCO brackets would close (deferred).</p>
 *
 * <p>Scope is deliberately {@link ExecutionTriggerSource#MANUAL_QUANT_PANEL} only — strategy rows
 * (WTX / auto-arm / playbook) own their exits through their own reconcilers and must not be
 * double-exited here. Gated by {@code riskdesk.quant.virtual-stop.enabled} (default false), read every
 * tick. The loop body is wrapped so a single bad row never stops future ticks, and once a close fires
 * the row leaves {@code ACTIVE} so it is not re-triggered.</p>
 */
@Component
public class VirtualStopWatcher {

    private static final Logger log = LoggerFactory.getLogger(VirtualStopWatcher.class);

    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final LivePricePort livePricePort;
    private final ActivePositionsService activePositionsService;
    private final QuantVirtualStopProperties props;

    public VirtualStopWatcher(TradeExecutionRepositoryPort tradeExecutionRepository,
                              LivePricePort livePricePort,
                              ActivePositionsService activePositionsService,
                              QuantVirtualStopProperties props) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.livePricePort = livePricePort;
        this.activePositionsService = activePositionsService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${riskdesk.quant.virtual-stop.poll-ms:1500}")
    public void tick() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("virtual-stop watcher tick failed: {}", e.toString());
        }
    }

    /** Package-private so tests can drive a single iteration without a real scheduler. */
    void sweep() {
        if (!props.isEnabled()) return;
        List<TradeExecutionRecord> active = tradeExecutionRepository.findByTriggerSourceAndStatus(
            ExecutionTriggerSource.MANUAL_QUANT_PANEL, ExecutionStatus.ACTIVE);
        for (TradeExecutionRecord row : active) {
            try {
                String reason = breach(row);
                if (reason == null) continue;
                log.info("virtual-stop {} breached executionId={} instrument={} — auto-closing (app-side)",
                    reason, row.getId(), row.getInstrument());
                activePositionsService.closePosition(row.getId(), "virtual-stop:" + reason);
            } catch (RuntimeException e) {
                // A bad row / failed close must not stop the rest of the batch or the scheduler.
                log.warn("virtual-stop auto-close failed executionId={}: {}", row.getId(), e.toString());
            }
        }
    }

    /**
     * "SL" / "TP" when the live price has crossed that virtual level for the row's side, else null.
     * The stop wins a tie (pessimistic — matches the simulation convention).
     */
    private String breach(TradeExecutionRecord row) {
        BigDecimal sl = row.getVirtualStopLoss();
        BigDecimal tp = row.getVirtualTakeProfit();
        if (sl == null && tp == null) return null;
        Instrument instrument = parseInstrument(row.getInstrument());
        if (instrument == null) return null;
        BigDecimal live = livePricePort.current(instrument)
            .map(snap -> BigDecimal.valueOf(snap.price()))
            .orElse(null);
        if (live == null || live.signum() <= 0) return null;

        boolean shortSide = "SHORT".equalsIgnoreCase(row.getAction()) || "SELL".equalsIgnoreCase(row.getAction());
        if (shortSide) {
            if (sl != null && live.compareTo(sl) >= 0) return "SL";
            if (tp != null && live.compareTo(tp) <= 0) return "TP";
        } else {
            if (sl != null && live.compareTo(sl) <= 0) return "SL";
            if (tp != null && live.compareTo(tp) >= 0) return "TP";
        }
        return null;
    }

    private static Instrument parseInstrument(String name) {
        try {
            return name == null ? null : Instrument.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

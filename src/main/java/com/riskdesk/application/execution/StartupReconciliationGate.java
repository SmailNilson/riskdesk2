package com.riskdesk.application.execution;

import com.riskdesk.application.dto.IbkrAuthStatusView;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Default {@link ExecutionReadinessGate} bean — the execution core's boot gate.
 *
 * <p>Starts <b>CLOSED</b> ({@code ready == false}) so the {@link DefaultOrderRouter} refuses intents
 * with {@code SKIPPED_RECONCILING} during the window between application start and the broker being
 * reachable. Without it, a strategy signal firing immediately after a restart could submit blind —
 * before the core can read what the broker already holds — risking a double position.</p>
 *
 * <p>The gate opens once broker position truth is reachable: {@link IbkrAuthStatusView#connected()}
 * is {@code true}, or IBKR is disabled (nothing to reconcile — the router then short-circuits with
 * {@code SKIPPED_IBKR_DISABLED}). It is checked on {@link ApplicationReadyEvent} and retried on a
 * fixed schedule so a transient boot-time broker outage delays — never permanently bricks — routing.
 * The gate is one-way (CLOSED&rarr;OPEN): a mid-session disconnect is handled at submission time, not
 * here, so we never re-close and silently halt a live strategy.</p>
 *
 * <p><b>Deliberately NOT done here (tracked follow-up — the fill-orchestration slice):</b> replaying
 * pre-existing non-terminal router rows (e.g. {@code ENTRY_SUBMITTED}) against broker truth to resolve
 * fills/phantoms left by the restart. That per-row re-sync is an explicit prerequisite before enabling
 * {@code riskdesk.execution.unified-router} for any live strategy (see docs/PLAN_ORDER_ROUTER_IMPL.md).
 * Until the router is wired, there are no such rows, so connection-readiness is a correct gate today.</p>
 */
@Component
public class StartupReconciliationGate implements ExecutionReadinessGate {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciliationGate.class);

    private final IbkrProperties ibkrProperties;
    private final IbkrPortfolioService portfolioService;

    private volatile boolean ready = false;

    public StartupReconciliationGate(IbkrProperties ibkrProperties,
                                     IbkrPortfolioService portfolioService) {
        this.ibkrProperties = ibkrProperties;
        this.portfolioService = portfolioService;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        log.info("execution boot gate ARMED (CLOSED) — routing refused until broker position truth is reachable");
        attemptOpen();
    }

    /** Retry until the gate opens. After it is open this is a cheap no-op (the {@code ready} short-circuit). */
    @Scheduled(fixedDelayString = "${riskdesk.execution.boot-gate.retry-ms:5000}")
    void retryUntilReady() {
        if (!ready) {
            attemptOpen();
        }
    }

    private void attemptOpen() {
        if (ready) {
            return;
        }
        // IBKR off: nothing to reconcile. Open so the router reaches its clearer SKIPPED_IBKR_DISABLED.
        if (!ibkrProperties.isEnabled()) {
            open("IBKR disabled");
            return;
        }
        try {
            IbkrAuthStatusView auth = portfolioService.getAuthStatus();
            if (auth != null && auth.connected()) {
                open("IBKR connected — broker position truth reachable");
            } else {
                log.debug("execution boot gate still CLOSED — IBKR not connected yet; will retry");
            }
        } catch (RuntimeException e) {
            // Never let a transient auth/portfolio read failure brick the gate — stay CLOSED and retry.
            log.warn("execution boot gate reconciliation attempt failed — staying CLOSED, will retry: {}",
                e.getMessage());
        }
    }

    private void open(String reason) {
        ready = true;
        log.info("execution boot gate OPEN — core may route intents ({})", reason);
    }

    /** Package-private hook for tests. */
    void setReady(boolean ready) {
        this.ready = ready;
    }
}

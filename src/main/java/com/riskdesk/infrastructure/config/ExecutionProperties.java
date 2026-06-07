package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Execution-core configuration (prefix {@code riskdesk.execution}).
 *
 * <p>{@code unified-router.enabled} (default {@code false}) is the strategy-migration kill-switch: when
 * ON, a migrated strategy routes through the unified {@code OrderRouter} instead of its legacy bridge
 * path. Default OFF means the migration wiring has ZERO runtime effect until deliberately enabled — and
 * it must NOT be flipped live until the fill-orchestration prerequisites ship (per-row boot replay,
 * reverse open serialised behind close-FILL, router retry-safe exit-ref discriminator).</p>
 */
@Component
@ConfigurationProperties(prefix = "riskdesk.execution")
public class ExecutionProperties {

    private final UnifiedRouter unifiedRouter = new UnifiedRouter();

    public UnifiedRouter getUnifiedRouter() {
        return unifiedRouter;
    }

    /** Convenience accessor for {@code riskdesk.execution.unified-router.enabled}. */
    public boolean isUnifiedRouterEnabled() {
        return unifiedRouter.isEnabled();
    }

    public static class UnifiedRouter {
        private boolean enabled = false;

        /**
         * Stuck-close retry grace, in seconds — the unified router's mirror of {@code
         * riskdesk.wtx.stale-close-retry-seconds} (PR #409). When a close is stuck in {@code
         * EXIT_SUBMITTED} past this window AND broker truth confirms IBKR still holds the position on the
         * row's side (a dropped ack / fill, or a marketable close that gapped out and died), the router
         * re-fires a FRESH close instead of skipping it as a duplicate — breaking the dead-lock where the
         * instrument is frozen and the live position is left unmanaged until the background
         * {@code StaleCloseReconciler} recovers it. {@code 0} disables the retry → the legacy unconditional
         * duplicate-skip. Default 45 (matches the legacy WTX grace).
         */
        private int staleCloseRetrySeconds = 45;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getStaleCloseRetrySeconds() {
            return staleCloseRetrySeconds;
        }

        public void setStaleCloseRetrySeconds(int staleCloseRetrySeconds) {
            this.staleCloseRetrySeconds = staleCloseRetrySeconds;
        }
    }
}

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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}

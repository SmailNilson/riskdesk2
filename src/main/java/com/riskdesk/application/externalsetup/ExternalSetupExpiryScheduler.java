package com.riskdesk.application.externalsetup;

import com.riskdesk.infrastructure.config.ExternalSetupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps {@code PENDING} setups whose TTL has elapsed and marks them
 * {@link com.riskdesk.domain.externalsetup.ExternalSetupStatus#EXPIRED EXPIRED}.
 *
 * <p>Runs every 30 seconds. Idempotent — safe to run alongside human validation.</p>
 */
@Component
public class ExternalSetupExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExternalSetupExpiryScheduler.class);

    private final ExternalSetupService service;
    private final ExternalSetupProperties properties;

    public ExternalSetupExpiryScheduler(ExternalSetupService service, ExternalSetupProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${riskdesk.external-setup.expiry-check-period-ms:30000}",
               initialDelayString = "${riskdesk.external-setup.expiry-initial-delay-ms:30000}")
    public void sweep() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            int n = service.expirePending();
            if (n > 0) {
                log.debug("ExternalSetup expiry sweep: {} setups marked EXPIRED", n);
            }
        } catch (RuntimeException e) {
            log.warn("ExternalSetup expiry sweep failed: {}", e.getMessage());
        }
    }
}

package com.riskdesk.infrastructure.marketdata.ibkr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Keeps the IBKR Client Portal Gateway session alive by calling /tickle regularly.
 *
 * Without this, the gateway logs out automatically after a short period of inactivity,
 * causing all market-data calls to return 401 / empty.
 *
 * IBKR also requires a re-authentication every ~24 hours regardless.
 * When that happens, navigate to https://localhost:5001 in your browser.
 */
@Component
@ConditionalOnExpression("'${riskdesk.ibkr.enabled:false}' == 'true' and '${riskdesk.ibkr.mode:CLIENT_PORTAL}' == 'CLIENT_PORTAL'")
public class IbkrSessionKeepAlive {

    private static final Logger log = LoggerFactory.getLogger(IbkrSessionKeepAlive.class);

    private final RestTemplate restTemplate;
    private final String       baseUrl;

    public IbkrSessionKeepAlive(IbkrRestClient client) {
        this.restTemplate = client.restTemplate();
        this.baseUrl      = client.baseUrl();
    }

    /** Ping /tickle every minute to reduce idle expiry risk. */
    @Scheduled(fixedDelay = 60 * 1000)
    public void tickle() {
        try {
            restTemplate.postForObject(baseUrl + "/tickle", null, String.class);
            log.debug("IBKR session tickled");
        } catch (Exception e) {
            log.warn("IBKR tickle failed (gateway may be down or re-auth needed): {}", e.getMessage());
        }
    }

    /** Check auth status on startup and every 10 minutes, log a warning if not authenticated. */
    @Scheduled(initialDelay = 5_000, fixedDelay = 10 * 60 * 1000)
    public void checkAuthStatus() {
        try {
            String status = restTemplate.getForObject(baseUrl + "/iserver/auth/status", String.class);
            if (status != null && status.contains("\"authenticated\":true")) {
                log.info("IBKR gateway: authenticated ✓");
            } else {
                log.warn("IBKR gateway: NOT authenticated — open https://localhost:5001 to re-login. Status: {}", status);
            }
        } catch (Exception e) {
            log.warn("IBKR auth status check failed: {} — is the gateway running?", e.getMessage());
        }
    }
}

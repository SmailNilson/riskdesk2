package com.riskdesk.application.execution;

import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.notification.event.DailyLossCapTrippedEvent;
import com.riskdesk.domain.notification.port.NotificationPort;
import com.riskdesk.domain.shared.TradingSessionResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * P4 — broker-truth daily loss cap (root cause R7's hard-stop sibling). Periodically reads IBKR's realized
 * P&L for the current CME trading day (broker truth, not the app's optimistic figure) and, when it falls to
 * or below the configured negative threshold, <b>halts new auto-entries</b> ({@link #blocksNewEntries()} is
 * consulted by every OPEN path) and fires a Telegram alarm. Closes are never blocked — a live position must
 * always be flattenable.
 *
 * <p>This is the safety net the 2-day, ~$1000 bleed lacked: instead of discovering the loss by losing more,
 * the cap stops opening at a line you set. The trip is <b>sticky for the trading day</b> and <b>auto re-arms
 * at the next CME trading-day boundary</b> (17:00 ET), when IBKR's daily realized P&L resets — so a bad day
 * cannot bleed into the next, and a good close mid-day cannot silently re-enable trading. It can also be
 * re-armed manually via {@link #reset()}.</p>
 *
 * <p>Fail-safe on missing data: an unreadable / disconnected snapshot or a null realized P&L is NEVER
 * treated as a trip <i>or</i> an all-clear — the guard simply waits for the next readable evaluation, and an
 * already-tripped state persists. Default OFF ({@code threshold-usd <= 0} or {@code enabled=false}).</p>
 */
@Component
public class DailyLossCapGuard {

    private static final Logger log = LoggerFactory.getLogger(DailyLossCapGuard.class);

    private final IbkrPortfolioService portfolioService;
    private final IbkrProperties ibkrProperties;
    private final NotificationPort notificationPort;
    private final boolean enabled;
    private final BigDecimal threshold;
    private final String account;

    private volatile boolean tripped = false;
    private volatile String reason = null;
    private volatile Instant trippedAt = null;
    private volatile BigDecimal trippedPnl = null;
    private volatile LocalDate trippedTradingDate = null;

    public DailyLossCapGuard(IbkrPortfolioService portfolioService,
                             IbkrProperties ibkrProperties,
                             NotificationPort notificationPort,
                             @Value("${riskdesk.execution.loss-cap.enabled:false}") boolean enabled,
                             @Value("${riskdesk.execution.loss-cap.threshold-usd:0}") BigDecimal thresholdUsd,
                             @Value("${riskdesk.execution.loss-cap.account:}") String account) {
        this.portfolioService = portfolioService;
        this.ibkrProperties = ibkrProperties;
        this.notificationPort = notificationPort;
        this.enabled = enabled;
        this.threshold = thresholdUsd == null ? BigDecimal.ZERO : thresholdUsd.abs();
        this.account = (account == null || account.isBlank()) ? null : account;
    }

    /** True when new auto-entries must be refused. Consulted by every OPEN path; closes ignore it. */
    public boolean blocksNewEntries() {
        return tripped;
    }

    public boolean isTripped() {
        return tripped;
    }

    public String reason() {
        return reason;
    }

    /** Manually re-arm the cap (clear the halt). Logged so a re-enable is auditable. */
    public void reset() {
        if (tripped) {
            log.warn("loss-cap: manually re-armed — auto-entries re-enabled (was: {})", reason);
        }
        clear();
    }

    private void clear() {
        tripped = false;
        reason = null;
        trippedAt = null;
        trippedPnl = null;
        trippedTradingDate = null;
    }

    @Scheduled(fixedDelayString = "${riskdesk.execution.loss-cap.interval-ms:30000}",
               initialDelayString = "${riskdesk.execution.loss-cap.initial-delay-ms:60000}")
    public void evaluate() {
        if (!isActive()) {
            return;
        }
        Instant now = Instant.now();
        LocalDate today = TradingSessionResolver.tradingDate(now);

        // Auto re-arm at the next trading-day boundary: IBKR's daily realized P&L resets, so a fresh day
        // starts with a fresh cap. Sticky within the SAME day (a good close can't silently re-enable).
        if (tripped) {
            if (trippedTradingDate != null && today.isAfter(trippedTradingDate)) {
                log.info("loss-cap: new trading day {} — auto re-armed (was tripped {} on {})",
                    today, trippedPnl, trippedTradingDate);
                clear();
            } else {
                return; // still tripped for the day
            }
        }

        BigDecimal realized = readRealizedPnl();
        if (realized == null) {
            return; // unreadable — never trip or all-clear on missing data
        }
        if (realized.compareTo(threshold.negate()) <= 0) {
            trip(realized, today, now);
        }
    }

    private boolean isActive() {
        return enabled && threshold.signum() > 0 && ibkrProperties.isEnabled();
    }

    /** Today's realized P&L from broker truth, or null when the snapshot is unreadable/disconnected. */
    private BigDecimal readRealizedPnl() {
        IbkrPortfolioSnapshot snapshot;
        try {
            snapshot = portfolioService.getPortfolio(account);
        } catch (RuntimeException e) {
            log.debug("loss-cap: portfolio unreadable — {}", e.toString());
            return null;
        }
        if (snapshot == null || !snapshot.connected()) {
            return null;
        }
        return snapshot.totalRealizedPnl();
    }

    private void trip(BigDecimal realized, LocalDate today, Instant now) {
        tripped = true;
        trippedPnl = realized;
        trippedTradingDate = today;
        trippedAt = now;
        reason = "Daily loss cap hit: realized " + realized.toPlainString()
            + " USD <= -" + threshold.toPlainString() + " (trading day " + today + ")";
        log.warn("loss-cap: TRIPPED — {} — new auto-entries halted until re-arm", reason);
        try {
            notificationPort.sendDailyLossCapTripped(
                new DailyLossCapTrippedEvent(account, realized, threshold, now));
        } catch (RuntimeException e) {
            log.debug("loss-cap: alarm not sent — {}", e.toString());
        }
    }
}

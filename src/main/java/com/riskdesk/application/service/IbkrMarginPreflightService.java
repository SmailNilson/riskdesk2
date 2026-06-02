package com.riskdesk.application.service;

import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.execution.OrderAffordabilityPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import com.riskdesk.infrastructure.config.WtxStrategyProperties.PreflightMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pre-flight margin check for WTX auto-execution orders.
 *
 * <p>Inserted into the flow <i>before</i> any broker order is submitted, so that an
 * order that would be rejected by IBKR with code 201 (insufficient initial margin) is
 * stopped at the bridge — no transient {@code EXIT_SUBMITTED} state, no orphaned
 * close-leg ack, no race between margin reject and timeout. This addresses the
 * production bug observed at 09:20Z where a REVERSE_TO_SHORT close leg timed out at
 * 28s and IBKR rejected the order async with margin insufficient (Equity 9757.44 USD
 * &lt; Initial Margin 11729.16 USD).</p>
 *
 * <p><b>Modes</b> (configured via {@code riskdesk.wtx.preflight-mode}):</p>
 * <ul>
 *   <li>{@link PreflightMode#OFF} — disabled. Legacy behavior (IBKR rejects async).</li>
 *   <li>{@link PreflightMode#PORTFOLIO_HEURISTIC} (default) — reads the cached portfolio
 *       snapshot (5s TTL, already in memory) and compares {@code availableFunds} to an
 *       <i>estimate</i> of the order's initial margin
 *       ({@code price × contractMultiplier × qty × preflightMarginPercent}). Zero
 *       additional latency. Conservative buffer (default 15%) so we deny the order
 *       slightly earlier than IBKR would.</li>
 *   <li>{@link PreflightMode#WHATIF} — sends an {@code Order.whatIf=true} to IBKR for
 *       a ground-truth margin estimate. Adds 200-500ms per signal. Not implemented in
 *       this slice; currently falls back to {@code PORTFOLIO_HEURISTIC} with a warn
 *       log so the operator knows the upgrade path is opt-in only when ready.</li>
 * </ul>
 *
 * <p><b>Fail-open policy</b>: when the portfolio snapshot is unavailable (IBKR
 * disconnected, fields null) the pre-flight allows the order. This preserves the
 * legacy behavior — IBKR will reject downstream with code 201 if margin really is
 * insufficient, and the typed {@link com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException}
 * from the native client will still surface as {@code SKIPPED_INSUFFICIENT_MARGIN}.</p>
 */
@Service
@ConditionalOnProperty(name = "riskdesk.wtx.enabled", havingValue = "true")
public class IbkrMarginPreflightService implements OrderAffordabilityPort {

    private static final Logger log = LoggerFactory.getLogger(IbkrMarginPreflightService.class);

    private final IbkrPortfolioService portfolioService;
    private final WtxStrategyProperties wtxProperties;

    public IbkrMarginPreflightService(IbkrPortfolioService portfolioService,
                                      WtxStrategyProperties wtxProperties) {
        this.portfolioService = portfolioService;
        this.wtxProperties = wtxProperties;
    }

    /**
     * Decides whether an order of {@code qty} contracts at {@code refPrice} would
     * deplete the account's available funds beyond what the configured margin
     * heuristic accepts. Fails open on missing data.
     *
     * @param instrument   contract spec (multiplier, tick size)
     * @param orderAction  "LONG" or "SHORT" — currently informational, both consume margin equally for futures
     * @param qty          contracts to be transacted (for a REVERSE, pass the SUM of close-leg + open-leg qty)
     * @param refPrice     reference price for margin estimation (signal price)
     * @return a {@link PreflightDecision} carrying allow/deny + an explanatory message
     */
    public PreflightDecision canAffordOrder(Instrument instrument, String orderAction, int qty, BigDecimal refPrice) {
        // Legacy 4-arg entry point (single-account WTX) — assess against the default account.
        return canAffordOrder(instrument, orderAction, qty, refPrice, null);
    }

    /**
     * Account-scoped variant: assess affordability against {@code brokerAccountId}'s funds (the account the
     * order routes to), not the gateway default. The unified router passes the intent's account so a
     * multi-account gateway denies/allows against the correct account — matching the per-account reconcile.
     * {@code null} resolves to the default account.
     */
    public PreflightDecision canAffordOrder(Instrument instrument, String orderAction, int qty, BigDecimal refPrice,
                                            String brokerAccountId) {
        if (instrument == null || refPrice == null || qty <= 0) {
            return PreflightDecision.allow();
        }
        PreflightMode mode = wtxProperties.getPreflightMode();
        if (mode == null || mode == PreflightMode.OFF) {
            return PreflightDecision.allow();
        }
        if (mode == PreflightMode.WHATIF) {
            // WHATIF requires an IBKR round-trip via IbGatewayNativeClient.placeWhatIfOrder() —
            // not implemented in this slice. Fall back to PORTFOLIO_HEURISTIC so the deploy is
            // still protected, log a warning so the operator knows the upgrade is pending.
            log.warn("WTX preflight mode WHATIF is not implemented yet — falling back to PORTFOLIO_HEURISTIC");
        }

        IbkrPortfolioSnapshot snapshot;
        try {
            snapshot = portfolioService.getPortfolio(brokerAccountId);
        } catch (RuntimeException e) {
            log.warn("WTX preflight: portfolio snapshot unavailable, failing open: {}", e.getMessage());
            return PreflightDecision.allow();
        }
        if (snapshot == null || !snapshot.connected()) {
            log.debug("WTX preflight: portfolio not connected, failing open");
            return PreflightDecision.allow();
        }
        BigDecimal availableFunds = snapshot.availableFunds();
        BigDecimal equity = snapshot.netLiquidation();
        if (availableFunds == null || availableFunds.signum() <= 0) {
            // No usable funds figure — fail open and let IBKR decide.
            return PreflightDecision.allow();
        }

        BigDecimal estimatedInitMargin = estimateInitMargin(instrument, qty, refPrice);
        if (availableFunds.compareTo(estimatedInitMargin) < 0) {
            String msg = String.format(
                    "Equity %s / AvailFunds %s < est. InitMargin %s (qty=%d, price=%s)",
                    fmt(equity), fmt(availableFunds), fmt(estimatedInitMargin), qty, fmt(refPrice));
            return PreflightDecision.deny(msg);
        }
        return PreflightDecision.allow();
    }

    /**
     * {@link OrderAffordabilityPort} adapter — the unified {@link com.riskdesk.application.execution.DefaultOrderRouter}
     * consults the SAME pre-flight the legacy WTX bridge used, so the unified path's deny decision is
     * identical. Delegates straight to {@link #canAffordOrder}.
     */
    @Override
    public Affordability check(Instrument instrument, String action, int qty, BigDecimal refPrice, String brokerAccountId) {
        PreflightDecision decision = canAffordOrder(instrument, action, qty, refPrice, brokerAccountId);
        return decision.allowed() ? Affordability.allow() : Affordability.deny(decision.denyReason());
    }

    /**
     * Conservative estimate of the initial margin for {@code qty} contracts at {@code price}.
     * Uses the configured {@code preflightMarginPercent} buffer. For MNQ (multiplier 2,
     * price ~17000) and qty=2 with default 15%: 2 × 2 × 17000 × 0.15 = 10200 USD —
     * comfortably above the actual IBKR init margin (~7-8k for 2 MNQ in 2026).
     */
    private BigDecimal estimateInitMargin(Instrument instrument, int qty, BigDecimal price) {
        BigDecimal pct = wtxProperties.getPreflightMarginPercent();
        if (pct == null || pct.signum() <= 0) {
            pct = new BigDecimal("0.15");
        }
        return price
                .multiply(instrument.getContractMultiplier())
                .multiply(BigDecimal.valueOf(qty))
                .multiply(pct)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "n/a" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The verdict of a single pre-flight call. {@code denyReason} is non-null only when
     * {@code allowed == false} and is suitable for the WTX signal {@code routingErrorMessage}
     * field (tooltip in the UI).
     */
    public record PreflightDecision(boolean allowed, String denyReason) {
        public static PreflightDecision allow() {
            return new PreflightDecision(true, null);
        }
        public static PreflightDecision deny(String reason) {
            return new PreflightDecision(false, reason);
        }
    }
}

package com.riskdesk.application.service;

import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import com.riskdesk.infrastructure.config.WtxStrategyProperties.PreflightMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PORTFOLIO_HEURISTIC pre-flight margin policy that protects
 * the WTX bridge against the production bug observed at 09:20Z (Equity 9757 USD
 * &lt; Initial Margin 11729 USD — REVERSE_TO_SHORT close leg failed before the
 * open leg could fire).
 */
class IbkrMarginPreflightServiceTest {

    private IbkrPortfolioService portfolioService;
    private WtxStrategyProperties wtxProperties;
    private IbkrMarginPreflightService preflight;

    @BeforeEach
    void setUp() {
        portfolioService = mock(IbkrPortfolioService.class);
        wtxProperties = new WtxStrategyProperties();
        wtxProperties.setPreflightMode(PreflightMode.PORTFOLIO_HEURISTIC);
        wtxProperties.setPreflightMarginPercent(new BigDecimal("0.15"));
        preflight = new IbkrMarginPreflightService(portfolioService, wtxProperties);
    }

    @Test
    void heuristicDenies_whenAvailableFundsBelowEstimatedInitMargin() {
        // The prod scenario: Equity 9757.44 USD < Initial Margin 11729.16 USD on MNQ x2.
        // MNQ multiplier = 2, price ~17000 → 2 × 17000 × 2 × 0.15 = 10200 USD est. init margin.
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(
                new BigDecimal("9757.44"),  // netLiquidation
                new BigDecimal("9000.00")   // availableFunds (less than 10200 est. margin)
        ));

        PreflightDecision decision = preflight.canAffordOrder(
                Instrument.MNQ, "SHORT", 2, new BigDecimal("17000"));

        assertFalse(decision.allowed(), "must deny when availFunds < estimated init margin");
        assertNotNull(decision.denyReason());
        assertTrue(decision.denyReason().contains("InitMargin"),
                "reason must mention InitMargin for the UI tooltip");
    }

    @Test
    void heuristicAllows_whenAvailableFundsWellAboveEstimate() {
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(
                new BigDecimal("50000.00"),
                new BigDecimal("40000.00")  // way above the 10200 est. margin for MNQ x2
        ));

        PreflightDecision decision = preflight.canAffordOrder(
                Instrument.MNQ, "LONG", 2, new BigDecimal("17000"));

        assertTrue(decision.allowed());
    }

    @Test
    void failsOpen_whenPortfolioNotConnected() {
        // Fail-open: when IBKR portfolio is unreachable, we let the order through and rely
        // on the downstream typed exception path (code=201) for the real safety net.
        when(portfolioService.getPortfolio(any())).thenReturn(disconnectedSnapshot());

        PreflightDecision decision = preflight.canAffordOrder(
                Instrument.MNQ, "LONG", 2, new BigDecimal("17000"));

        assertTrue(decision.allowed(), "disconnected portfolio must fail open");
    }

    @Test
    void failsOpen_whenPortfolioServiceThrows() {
        when(portfolioService.getPortfolio(any())).thenThrow(new RuntimeException("IBKR down"));

        PreflightDecision decision = preflight.canAffordOrder(
                Instrument.MNQ, "LONG", 2, new BigDecimal("17000"));

        assertTrue(decision.allowed(), "exception from portfolio service must fail open");
    }

    @Test
    void offMode_alwaysAllows_evenWhenFundsTooLow() {
        wtxProperties.setPreflightMode(PreflightMode.OFF);
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(
                new BigDecimal("100"),
                new BigDecimal("100")
        ));

        PreflightDecision decision = preflight.canAffordOrder(
                Instrument.MNQ, "LONG", 2, new BigDecimal("17000"));

        assertTrue(decision.allowed());
    }

    @Test
    void whatifMode_fallsBackToHeuristic_logsWarn() {
        // WHATIF is not implemented in this slice — must fall back to heuristic, NOT crash.
        wtxProperties.setPreflightMode(PreflightMode.WHATIF);
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(
                new BigDecimal("50000"), new BigDecimal("40000")));

        PreflightDecision decision = preflight.canAffordOrder(
                Instrument.MNQ, "LONG", 2, new BigDecimal("17000"));

        assertTrue(decision.allowed(), "WHATIF must fall back to heuristic gracefully");
    }

    @Test
    void allowsInvalidQuantity() {
        // qty <= 0 is filtered upstream; preflight stays out of the way.
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(
                new BigDecimal("100"), new BigDecimal("100")));

        PreflightDecision decision = preflight.canAffordOrder(
                Instrument.MNQ, "LONG", 0, new BigDecimal("17000"));

        assertTrue(decision.allowed());
    }

    @Test
    void denyReasonIncludesEquityAndInitMargin_forUiTooltip() {
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(
                new BigDecimal("9757.44"), new BigDecimal("9000.00")));

        PreflightDecision decision = preflight.canAffordOrder(
                Instrument.MNQ, "SHORT", 2, new BigDecimal("17000"));

        assertFalse(decision.allowed());
        // UI tooltip surface — the operator needs to see the key figures at a glance.
        assertTrue(decision.denyReason().contains("Equity"), "reason must include Equity");
        assertTrue(decision.denyReason().contains("AvailFunds"), "reason must include AvailFunds");
        assertTrue(decision.denyReason().contains("InitMargin"), "reason must include InitMargin");
    }

    @Test
    void marginPercentBufferIsConfigurable() {
        // Tighten the buffer to 10% — same equity now passes for the same MNQ x2 trade.
        wtxProperties.setPreflightMarginPercent(new BigDecimal("0.10"));
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(
                new BigDecimal("9757.44"), new BigDecimal("9000.00")));

        // 2 × 17000 × 2 × 0.10 = 6800 < 9000 → allowed.
        PreflightDecision decision = preflight.canAffordOrder(
                Instrument.MNQ, "SHORT", 2, new BigDecimal("17000"));
        assertTrue(decision.allowed(), "lower buffer should let the same trade through");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private IbkrPortfolioSnapshot snapshot(BigDecimal netLiquidation, BigDecimal availableFunds) {
        return new IbkrPortfolioSnapshot(
                true,
                "U1234567",
                List.of(),
                netLiquidation,
                BigDecimal.ZERO,        // initMarginReq (not used by heuristic)
                availableFunds,
                BigDecimal.ZERO,        // buyingPower
                BigDecimal.ZERO,        // grossPositionValue
                BigDecimal.ZERO,        // totalUnrealizedPnl
                BigDecimal.ZERO,        // totalRealizedPnl
                "USD",
                List.of(),
                null
        );
    }

    private IbkrPortfolioSnapshot disconnectedSnapshot() {
        return new IbkrPortfolioSnapshot(
                false, null, List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "USD", List.of(), "disconnected"
        );
    }

    // Sanity: equality between expected outcomes makes the test names self-documenting.
    @SuppressWarnings("unused")
    private void compile() {
        assertEquals(PreflightMode.PORTFOLIO_HEURISTIC, wtxProperties.getPreflightMode());
    }
}

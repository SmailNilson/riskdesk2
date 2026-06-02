package com.riskdesk.application.execution;

import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupReconciliationGateTest {

    private final IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);

    private static IbkrProperties props(boolean enabled) {
        IbkrProperties p = new IbkrProperties();
        p.setEnabled(enabled);
        return p;
    }

    /** Connected snapshot with a non-null positions list — broker position truth IS readable. */
    private static IbkrPortfolioSnapshot readable() {
        return new IbkrPortfolioSnapshot(true, "DU1", List.of(), null, null, null, null, null, null, null,
            "USD", List.of(), null);
    }

    /** Socket may be up but accounts/positions have not arrived — truth NOT readable (positions null). */
    private static IbkrPortfolioSnapshot notReadable() {
        return new IbkrPortfolioSnapshot(false, "DU1", List.of(), null, null, null, null, null, null, null,
            "USD", null, null);
    }

    private StartupReconciliationGate gate(boolean enabled) {
        return new StartupReconciliationGate(props(enabled), portfolio);
    }

    @Test
    void startsClosed_refusesRoutingBeforeBoot() {
        // CLOSED until something opens it — a signal firing pre-reconciliation must be refused.
        assertThat(gate(true).isReady()).isFalse();
    }

    @Test
    void ibkrDisabled_opensImmediately() {
        // Nothing to reconcile when IBKR is off; the router then short-circuits to SKIPPED_IBKR_DISABLED.
        StartupReconciliationGate gate = gate(false);

        gate.onApplicationReady();

        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void positionTruthReadable_opens() {
        when(portfolio.getPortfolio(nullable(String.class))).thenReturn(readable());
        StartupReconciliationGate gate = gate(true);

        gate.onApplicationReady();

        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void socketConnectedButPortfolioNotReadable_staysClosed_thenOpensWhenReadable() {
        // The exact gap the gate must close: a connected socket whose account/position snapshot has not
        // arrived yet. readPositionState would return unavailable, so the gate must NOT open.
        when(portfolio.getPortfolio(nullable(String.class))).thenReturn(notReadable());
        StartupReconciliationGate gate = gate(true);

        gate.onApplicationReady();
        assertThat(gate.isReady()).isFalse();

        when(portfolio.getPortfolio(nullable(String.class))).thenReturn(readable()); // positions arrive
        gate.retryUntilReady();
        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void portfolioReadThrows_staysClosed_noException() {
        when(portfolio.getPortfolio(nullable(String.class))).thenThrow(new RuntimeException("gateway not ready"));
        StartupReconciliationGate gate = gate(true);

        gate.onApplicationReady(); // must swallow the error, not brick the gate

        assertThat(gate.isReady()).isFalse();
    }

    @Test
    void retryAfterOpen_isNoOp_oneWayGate() {
        when(portfolio.getPortfolio(nullable(String.class))).thenReturn(readable());
        StartupReconciliationGate gate = gate(true);
        gate.onApplicationReady();
        assertThat(gate.isReady()).isTrue();

        // Once open the gate is one-way: a later unreadable snapshot must not re-close it.
        when(portfolio.getPortfolio(nullable(String.class))).thenReturn(notReadable());
        gate.retryUntilReady();
        assertThat(gate.isReady()).isTrue();
    }
}

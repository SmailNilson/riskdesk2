package com.riskdesk.application.execution;

import com.riskdesk.application.dto.IbkrAuthStatusView;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupReconciliationGateTest {

    private final IbkrPortfolioService portfolio = mock(IbkrPortfolioService.class);

    private static IbkrProperties props(boolean enabled) {
        IbkrProperties p = new IbkrProperties();
        p.setEnabled(enabled);
        return p;
    }

    private static IbkrAuthStatusView auth(boolean connected) {
        return new IbkrAuthStatusView(connected, connected, connected, false, "socket://x:4003", "msg");
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
    void ibkrEnabledAndConnected_opens() {
        when(portfolio.getAuthStatus()).thenReturn(auth(true));
        StartupReconciliationGate gate = gate(true);

        gate.onApplicationReady();

        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void ibkrEnabledNotConnected_staysClosed_thenOpensOnRetryWhenConnected() {
        when(portfolio.getAuthStatus()).thenReturn(auth(false));
        StartupReconciliationGate gate = gate(true);

        gate.onApplicationReady();
        assertThat(gate.isReady()).isFalse(); // broker not reachable yet — keep refusing

        when(portfolio.getAuthStatus()).thenReturn(auth(true)); // connection comes up
        gate.retryUntilReady();
        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void authReadThrows_staysClosed_noException() {
        when(portfolio.getAuthStatus()).thenThrow(new RuntimeException("gateway not ready"));
        StartupReconciliationGate gate = gate(true);

        gate.onApplicationReady(); // must swallow the error, not brick the gate

        assertThat(gate.isReady()).isFalse();
    }

    @Test
    void retryAfterOpen_isNoOp_doesNotReReadBroker() {
        when(portfolio.getAuthStatus()).thenReturn(auth(true));
        StartupReconciliationGate gate = gate(true);
        gate.onApplicationReady();
        assertThat(gate.isReady()).isTrue();

        // Once open the gate is one-way: a later disconnect must not re-close it (handled at submit time).
        when(portfolio.getAuthStatus()).thenReturn(auth(false));
        gate.retryUntilReady();
        assertThat(gate.isReady()).isTrue();
    }
}

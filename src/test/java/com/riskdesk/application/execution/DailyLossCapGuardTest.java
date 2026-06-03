package com.riskdesk.application.execution;

import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.notification.event.DailyLossCapTrippedEvent;
import com.riskdesk.domain.notification.port.NotificationPort;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailyLossCapGuardTest {

    @Mock IbkrPortfolioService portfolioService;
    @Mock IbkrProperties ibkrProperties;
    @Mock NotificationPort notificationPort;

    private DailyLossCapGuard guard(boolean enabled, String threshold) {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        return new DailyLossCapGuard(portfolioService, ibkrProperties, notificationPort,
            enabled, new BigDecimal(threshold), "");
    }

    private static IbkrPortfolioSnapshot snapshot(boolean connected, String realizedPnl) {
        return new IbkrPortfolioSnapshot(connected, "DU1", List.of(),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, realizedPnl == null ? null : new BigDecimal(realizedPnl), "USD", List.of(), null);
    }

    @Test
    void tripsAndAlarmsWhenRealizedLossBreachesThreshold() {
        DailyLossCapGuard g = guard(true, "500");
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(true, "-500.00"));

        g.evaluate();

        assertThat(g.blocksNewEntries()).isTrue();
        ArgumentCaptor<DailyLossCapTrippedEvent> cap = ArgumentCaptor.forClass(DailyLossCapTrippedEvent.class);
        verify(notificationPort).sendDailyLossCapTripped(cap.capture());
        assertThat(cap.getValue().realizedPnl()).isEqualByComparingTo("-500.00");
        assertThat(cap.getValue().threshold()).isEqualByComparingTo("500");
    }

    @Test
    void doesNotTripWhenLossAboveThreshold() {
        DailyLossCapGuard g = guard(true, "500");
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(true, "-499.99"));

        g.evaluate();

        assertThat(g.blocksNewEntries()).isFalse();
        verify(notificationPort, never()).sendDailyLossCapTripped(any());
    }

    @Test
    void disabledWhenThresholdZero() {
        DailyLossCapGuard g = guard(true, "0");
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(true, "-9999.00"));

        g.evaluate();

        assertThat(g.blocksNewEntries()).isFalse();
        verify(portfolioService, never()).getPortfolio(any());
    }

    @Test
    void disabledWhenFlagOff() {
        DailyLossCapGuard g = guard(false, "500");
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(true, "-9999.00"));

        g.evaluate();

        assertThat(g.blocksNewEntries()).isFalse();
        verify(portfolioService, never()).getPortfolio(any());
    }

    @Test
    void neverTripsOnDisconnectedSnapshot() {
        DailyLossCapGuard g = guard(true, "500");
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(false, "-9999.00"));

        g.evaluate();

        assertThat(g.blocksNewEntries()).isFalse();
    }

    @Test
    void neverTripsOnNullRealizedPnl() {
        DailyLossCapGuard g = guard(true, "500");
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(true, null));

        g.evaluate();

        assertThat(g.blocksNewEntries()).isFalse();
    }

    @Test
    void stickyForTheDay_doesNotReReadAfterTrip() {
        DailyLossCapGuard g = guard(true, "500");
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(true, "-600.00"));
        g.evaluate();
        assertThat(g.blocksNewEntries()).isTrue();

        // A subsequent improved realized P&L the SAME day must NOT silently re-arm.
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(true, "100.00"));
        g.evaluate();
        assertThat(g.blocksNewEntries()).isTrue();
    }

    @Test
    void manualResetReArms() {
        DailyLossCapGuard g = guard(true, "500");
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(true, "-600.00"));
        g.evaluate();
        assertThat(g.blocksNewEntries()).isTrue();

        g.reset();
        assertThat(g.blocksNewEntries()).isFalse();
    }

    @Test
    void alarmFailureDoesNotPreventTrip() {
        DailyLossCapGuard g = guard(true, "500");
        when(portfolioService.getPortfolio(any())).thenReturn(snapshot(true, "-600.00"));
        org.mockito.Mockito.doThrow(new RuntimeException("telegram down"))
            .when(notificationPort).sendDailyLossCapTripped(any());

        g.evaluate();

        assertThat(g.blocksNewEntries()).isTrue();
    }
}

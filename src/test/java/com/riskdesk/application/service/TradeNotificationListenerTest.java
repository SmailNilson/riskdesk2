package com.riskdesk.application.service;

import com.riskdesk.domain.engine.strategy.wtx.WtxAction;
import com.riskdesk.domain.engine.strategy.wtx.WtxEnrichmentSnapshot;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignalType;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.domain.notification.event.TradeValidatedEvent;
import com.riskdesk.domain.notification.event.WtxSignalDetectedEvent;
import com.riskdesk.domain.notification.port.NotificationPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradeNotificationListenerTest {

    @Mock private NotificationPort notificationPort;
    @Mock private WtxStrategyStatePort wtxStatePort;
    @InjectMocks private TradeNotificationListener listener;

    private TradeValidatedEvent sampleEvent() {
        return new TradeValidatedEvent(
            "MCL", "LONG", "10m",
            "Trade Validé",
            "Bullish OB confirmed.",
            70.25, null, 69.50, 72.00, 2.33,
            Instant.parse("2026-04-01T14:00:00Z")
        );
    }

    private WtxSignalDetectedEvent wtxEvent(String instrument, String timeframe) {
        WtxSignal signal = new WtxSignal(
            instrument, timeframe, WtxSignalType.COMPRA_1, "LONG",
            new BigDecimal("-34.70"), new BigDecimal("-30.10"),
            true, WtxAction.REVERSE_TO_LONG,
            WtxEnrichmentSnapshot.empty(),
            Instant.parse("2026-05-20T19:00:00Z"),
            null, null
        );
        return WtxSignalDetectedEvent.from(signal, new BigDecimal("24390.25"));
    }

    private WtxStrategyState state(String instrument, String timeframe, boolean telegramEnabled) {
        WtxStrategyState s = WtxStrategyState.initial(instrument, timeframe, new BigDecimal("10000"));
        return s.withTelegramNotifications(telegramEnabled);
    }

    @Test
    void delegatesToNotificationPort() {
        TradeValidatedEvent event = sampleEvent();
        listener.onTradeValidated(event);
        verify(notificationPort).sendTradeValidated(event);
    }

    @Test
    void swallowsExceptionFromNotificationPort() {
        doThrow(new RuntimeException("Telegram down"))
            .when(notificationPort).sendTradeValidated(any());

        assertThatCode(() -> listener.onTradeValidated(sampleEvent()))
            .doesNotThrowAnyException();

        verify(notificationPort).sendTradeValidated(any());
    }

    @Test
    void wtxNotificationDelegatesWhenTelegramEnabled() {
        when(wtxStatePort.load("MNQ", "10m"))
            .thenReturn(Optional.of(state("MNQ", "10m", true)));

        WtxSignalDetectedEvent event = wtxEvent("MNQ", "10m");
        listener.onWtxSignal(event);

        verify(notificationPort).sendWtxSignal(event);
    }

    @Test
    void wtxNotificationSkippedWhenTelegramDisabled() {
        when(wtxStatePort.load("MNQ", "10m"))
            .thenReturn(Optional.of(state("MNQ", "10m", false)));

        listener.onWtxSignal(wtxEvent("MNQ", "10m"));

        verify(notificationPort, never()).sendWtxSignal(any());
    }

    @Test
    void wtxNotificationDefaultsToEnabledWhenStateMissing() {
        when(wtxStatePort.load("MNQ", "5m")).thenReturn(Optional.empty());

        WtxSignalDetectedEvent event = wtxEvent("MNQ", "5m");
        listener.onWtxSignal(event);

        verify(notificationPort).sendWtxSignal(event);
    }

    @Test
    void wtxNotificationSwallowsAdapterException() {
        when(wtxStatePort.load("MNQ", "10m"))
            .thenReturn(Optional.of(state("MNQ", "10m", true)));
        doThrow(new RuntimeException("Telegram down"))
            .when(notificationPort).sendWtxSignal(any());

        assertThatCode(() -> listener.onWtxSignal(wtxEvent("MNQ", "10m")))
            .doesNotThrowAnyException();
    }
}

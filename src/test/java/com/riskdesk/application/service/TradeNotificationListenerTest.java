package com.riskdesk.application.service;

import com.riskdesk.domain.notification.event.TradeValidatedEvent;
import com.riskdesk.domain.notification.port.NotificationPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradeNotificationListenerTest {

    @Mock private NotificationPort notificationPort;
    @InjectMocks private TradeNotificationListener listener;

    private TradeValidatedEvent sampleEvent() {
        return new TradeValidatedEvent(
            "MCL", "LONG", "10m",
            "Trade Valid\u00e9",
            "Bullish OB confirmed.",
            70.25, null, 69.50, 72.00, 2.33,
            Instant.parse("2026-04-01T14:00:00Z")
        );
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
}

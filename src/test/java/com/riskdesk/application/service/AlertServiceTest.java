package com.riskdesk.application.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.domain.alert.service.AlertDeduplicator;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertServiceTest {

    @Test
    void tenMinuteAlertCanRefireAfterSharedDedupCooldownExpires() throws Exception {
        assertAlertCanRefireAfterSharedCooldown("macd:bullish:MCL:10m");
    }

    @Test
    void hourlyAlertCanRefireAfterSharedDedupCooldownExpires() throws Exception {
        assertAlertCanRefireAfterSharedCooldown("macd:bullish:MCL:1h");
    }

    private void assertAlertCanRefireAfterSharedCooldown(String key) throws Exception {
        AtomicInteger sentMessages = new AtomicInteger();
        MessageChannel channel = new CountingMessageChannel(sentMessages);
        AlertDeduplicator deduplicator = new AlertDeduplicator(1);
        AlertService service = new AlertService(
            null,
            null,
            null,
            null,
            deduplicator,
            null,
            null,
            new SimpMessagingTemplate(channel)
        );

        Method publishWithoutMentor = AlertService.class.getDeclaredMethod("publishAlertWithoutMentor", Alert.class);
        publishWithoutMentor.setAccessible(true);

        Alert alert = new Alert(key, AlertSeverity.INFO, "test message", AlertCategory.MACD, "MCL");

        assertTrue((Boolean) publishWithoutMentor.invoke(service, alert));
        assertFalse((Boolean) publishWithoutMentor.invoke(service, alert));

        Thread.sleep(1100);

        assertTrue((Boolean) publishWithoutMentor.invoke(service, alert));
        assertEquals(2, sentMessages.get());
    }

    private record CountingMessageChannel(AtomicInteger sentMessages) implements MessageChannel {
        @Override
        public boolean send(Message<?> message) {
            sentMessages.incrementAndGet();
            return true;
        }

        @Override
        public boolean send(Message<?> message, long timeout) {
            return send(message);
        }
    }
}

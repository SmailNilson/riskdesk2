package com.riskdesk.infrastructure.notification;

import com.riskdesk.domain.notification.event.TradeValidatedEvent;
import com.riskdesk.infrastructure.config.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramNotificationAdapterTest {

    @Mock private RestTemplate restTemplate;
    private TelegramProperties properties;
    private TelegramNotificationAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new TelegramProperties();
        properties.setEnabled(true);
        properties.setBotToken("test-bot-token");
        properties.setChatId("123456789");

        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.setConnectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.setReadTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);

        adapter = new TelegramNotificationAdapter(properties, builder);
    }

    private TradeValidatedEvent sampleEvent(String action, String instrument) {
        return new TradeValidatedEvent(
            instrument, action, "10m",
            "Trade Valid\u00e9 - Discipline Respect\u00e9e",
            "Strong bearish setup confirmed.",
            24390.25, 24370.00, 24445.25, 24280.25,
            2.0, Instant.parse("2026-04-01T14:30:00Z")
        );
    }

    // ---- Guard clauses ----

    @Nested
    class WhenDisabled {

        @Test
        void skipsNotificationWhenDisabled() {
            properties.setEnabled(false);
            adapter.sendTradeValidated(sampleEvent("SHORT", "MNQ"));
            verifyNoInteractions(restTemplate);
        }

        @Test
        void skipsNotificationWhenBotTokenBlank() {
            properties.setBotToken("  ");
            adapter.sendTradeValidated(sampleEvent("LONG", "MCL"));
            verifyNoInteractions(restTemplate);
        }

        @Test
        void skipsNotificationWhenChatIdBlank() {
            properties.setChatId("");
            adapter.sendTradeValidated(sampleEvent("SHORT", "MGC"));
            verifyNoInteractions(restTemplate);
        }
    }

    // ---- Message formatting ----

    @Nested
    class MessageFormatting {

        @SuppressWarnings("unchecked")
        @Test
        void sendsFormattedLongMessage() {
            TradeValidatedEvent event = sampleEvent("LONG", "MCL");
            adapter.sendTradeValidated(event);

            ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(
                eq("https://api.telegram.org/bottest-bot-token/sendMessage"),
                bodyCaptor.capture(),
                eq(String.class)
            );

            Map<String, Object> body = bodyCaptor.getValue();
            assertThat(body.get("chat_id")).isEqualTo("123456789");
            assertThat(body.get("parse_mode")).isEqualTo("HTML");

            String text = (String) body.get("text");
            assertThat(text).contains("LONG");
            assertThat(text).contains("MCL");
            assertThat(text).contains("Micro WTI Crude");
            // Price formatting is locale-dependent (%,.2f) — assert key fragments
            assertThat(text).contains("24");
            assertThat(text).contains("390");  // entry contains 390
            assertThat(text).contains("445");  // SL contains 445
            assertThat(text).contains("280");  // TP contains 280
            assertThat(text).containsPattern("R:R Ratio.*2"); // R:R ratio present
            assertThat(text).contains("Strong bearish setup confirmed.");
        }

        @SuppressWarnings("unchecked")
        @Test
        void sendsFormattedShortMessage() {
            TradeValidatedEvent event = sampleEvent("SHORT", "MNQ");
            adapter.sendTradeValidated(event);

            ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(anyString(), bodyCaptor.capture(), eq(String.class));

            String text = (String) bodyCaptor.getValue().get("text");
            assertThat(text).contains("SHORT");
            assertThat(text).contains("MNQ");
            assertThat(text).contains("Micro Nasdaq-100");
        }

        @SuppressWarnings("unchecked")
        @Test
        void handlesNullDeepEntryAndRatio() {
            TradeValidatedEvent event = new TradeValidatedEvent(
                "MGC", "LONG", "1h", "Trade Valid\u00e9",
                null, 1920.50, null, 1910.00, 1940.00,
                null, Instant.now()
            );
            adapter.sendTradeValidated(event);

            ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(anyString(), bodyCaptor.capture(), eq(String.class));

            String text = (String) bodyCaptor.getValue().get("text");
            assertThat(text).doesNotContain("Deep Entry");
            assertThat(text).doesNotContain("R:R Ratio");
            assertThat(text).contains("Micro Gold");
        }

        @SuppressWarnings("unchecked")
        @Test
        void instrumentLabels_allKnownInstruments() {
            for (var pair : Map.of("MCL", "Micro WTI Crude", "MGC", "Micro Gold",
                                   "MNQ", "Micro Nasdaq-100", "6E", "Euro FX",
                                   "E6", "Euro FX").entrySet()) {
                reset(restTemplate);
                adapter.sendTradeValidated(sampleEvent("LONG", pair.getKey()));

                ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
                verify(restTemplate).postForEntity(anyString(), cap.capture(), eq(String.class));
                assertThat((String) cap.getValue().get("text")).contains(pair.getValue());
            }
        }

        @SuppressWarnings("unchecked")
        @Test
        void unknownInstrumentFallsBackToSymbol() {
            adapter.sendTradeValidated(sampleEvent("LONG", "ZZZ"));

            ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(anyString(), cap.capture(), eq(String.class));
            String text = (String) cap.getValue().get("text");
            // Should contain the symbol as-is when no label mapping exists
            assertThat(text).contains("(ZZZ)");
        }
    }

    // ---- HTML escaping ----

    @Nested
    class HtmlEscaping {

        @SuppressWarnings("unchecked")
        @Test
        void escapesHtmlCharactersInAnalysis() {
            TradeValidatedEvent event = new TradeValidatedEvent(
                "MCL", "LONG", "10m", "Verdict",
                "Price < 70 & ratio > 2.0",
                70.0, null, 68.0, 75.0, 2.5, Instant.now()
            );
            adapter.sendTradeValidated(event);

            ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(anyString(), cap.capture(), eq(String.class));
            String text = (String) cap.getValue().get("text");
            assertThat(text).contains("&lt;");
            assertThat(text).contains("&gt;");
            assertThat(text).contains("&amp;");
            assertThat(text).doesNotContain("< 70");
        }
    }

    // ---- Error resilience ----

    @Nested
    class ErrorResilience {

        @Test
        void networkFailureDoesNotThrow() {
            when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

            assertThatCode(() -> adapter.sendTradeValidated(sampleEvent("LONG", "MCL")))
                .doesNotThrowAnyException();
        }

        @Test
        void runtimeExceptionDoesNotThrow() {
            when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("Unexpected error"));

            assertThatCode(() -> adapter.sendTradeValidated(sampleEvent("SHORT", "MNQ")))
                .doesNotThrowAnyException();
        }
    }

    // ---- API URL ----

    @Test
    void usesCorrectTelegramApiUrl() {
        adapter.sendTradeValidated(sampleEvent("LONG", "MCL"));

        verify(restTemplate).postForEntity(
            eq("https://api.telegram.org/bottest-bot-token/sendMessage"),
            any(), eq(String.class)
        );
    }
}

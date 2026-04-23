package com.riskdesk.infrastructure.notification;

import com.riskdesk.domain.notification.event.TradeBlockedByStrategyGateEvent;
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

    // ---- D1: Strategy engine line on validated messages ----

    @Nested
    class StrategyEngineEnrichment {

        private TradeValidatedEvent enrichedEvent(String playbookId, String decision,
                                                    Double score, Boolean agrees) {
            return new TradeValidatedEvent(
                "MGC", "LONG", "1h",
                "Trade Valid\u00e9",
                "Strong bull setup at DISCOUNT.",
                2015.50, null, 2010.00, 2025.00, 1.9,
                Instant.parse("2026-04-01T14:30:00Z"),
                playbookId, decision, score, agrees
            );
        }

        @SuppressWarnings("unchecked")
        @Test
        void addsEngineLineWhenEngineEvaluatedAndAgrees() {
            adapter.sendTradeValidated(enrichedEvent("NOR", "HALF_SIZE", 72.3, true));

            ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(anyString(), cap.capture(), eq(String.class));
            String text = (String) cap.getValue().get("text");

            assertThat(text).contains("<b>Engine:</b>");
            assertThat(text).contains("NOR");
            assertThat(text).contains("HALF_SIZE");
            assertThat(text).contains("+72.3");
            // ✅ check mark when engine agrees
            assertThat(text).contains("\u2705");
        }

        @SuppressWarnings("unchecked")
        @Test
        void addsWarningWhenEngineDisagrees() {
            adapter.sendTradeValidated(enrichedEvent("LSAR", "PAPER_TRADE", 38.1, false));

            ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(anyString(), cap.capture(), eq(String.class));
            String text = (String) cap.getValue().get("text");

            assertThat(text).contains("<b>Engine:</b>");
            assertThat(text).contains("LSAR");
            assertThat(text).contains("PAPER_TRADE");
            assertThat(text).contains("+38.1");
            // ⚠️ warning when engine disagrees — the trade still goes ahead but
            // the operator should see the divergence.
            assertThat(text).contains("\u26A0");
        }

        @SuppressWarnings("unchecked")
        @Test
        void omitsEngineLineWhenEngineNotEvaluated() {
            // All strategy* fields null (engine unavailable in that MentorSignalReviewService
            // boot configuration). Back-compat ctor path.
            adapter.sendTradeValidated(sampleEvent("LONG", "MCL"));

            ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(anyString(), cap.capture(), eq(String.class));
            String text = (String) cap.getValue().get("text");

            assertThat(text).doesNotContain("<b>Engine:</b>");
        }

        @SuppressWarnings("unchecked")
        @Test
        void negativeScoreRenderedWithoutPlusSign() {
            adapter.sendTradeValidated(enrichedEvent("LSAR", "PAPER_TRADE", -42.0, false));

            ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(anyString(), cap.capture(), eq(String.class));
            String text = (String) cap.getValue().get("text");

            assertThat(text).contains("-42.0");
            assertThat(text).doesNotContain("+-42");
        }
    }

    // ---- D2: Gate-block notifications ----

    @Nested
    class GateBlockNotification {

        private TradeBlockedByStrategyGateEvent blockedEvent(String playbookId,
                                                               String decision, Double score,
                                                               String reason) {
            return new TradeBlockedByStrategyGateEvent(
                "MGC", "LONG", "1h", 42L,
                reason, playbookId, decision, score,
                Instant.parse("2026-04-17T14:30:00Z")
            );
        }

        @SuppressWarnings("unchecked")
        @Test
        void sendsBlockedMessageWithEngineFields() {
            adapter.sendTradeBlockedByGate(blockedEvent(
                "NOR", "NO_TRADE", 22.0,
                "engine-decision=NO_TRADE score=22.0"));

            ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(anyString(), cap.capture(), eq(String.class));
            String text = (String) cap.getValue().get("text");

            // Red-themed header 🛑
            assertThat(text).contains("\uD83D\uDED1");
            assertThat(text).contains("TRADE BLOQU");
            assertThat(text).contains("MGC");
            assertThat(text).contains("LONG");
            assertThat(text).contains("Review:</b> #42");
            assertThat(text).contains("Mentor legacy:");
            assertThat(text).contains("ELIGIBLE");
            // ✅ for legacy, ❌ for engine (both are expected icons on a block)
            assertThat(text).contains("\u2705");
            assertThat(text).contains("\u274C");
            assertThat(text).contains("NOR");
            assertThat(text).contains("NO_TRADE");
            assertThat(text).contains("+22.0");
            assertThat(text).contains("No IBKR order was placed");
        }

        @SuppressWarnings("unchecked")
        @Test
        void sendsBlockedMessageWhenEngineEvaluationMissing() {
            // Gate blocked for a reason that doesn't involve an engine eval
            // (e.g. strategy-engine-unavailable). All strategy* fields null.
            adapter.sendTradeBlockedByGate(new TradeBlockedByStrategyGateEvent(
                "MGC", "LONG", "1h", 77L,
                "strategy-engine-unavailable", null, null, null,
                Instant.parse("2026-04-17T14:30:00Z")
            ));

            ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
            verify(restTemplate).postForEntity(anyString(), cap.capture(), eq(String.class));
            String text = (String) cap.getValue().get("text");

            assertThat(text).contains("TRADE BLOQU");
            assertThat(text).contains("(no evaluation)");
            assertThat(text).contains("strategy-engine-unavailable");
        }

        @Test
        void skipsBlockedNotificationWhenDisabled() {
            properties.setEnabled(false);
            adapter.sendTradeBlockedByGate(blockedEvent(
                "NOR", "NO_TRADE", 22.0, "engine-decision=NO_TRADE"));
            verifyNoInteractions(restTemplate);
        }

        @Test
        void blockedNetworkFailureDoesNotThrow() {
            when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

            assertThatCode(() -> adapter.sendTradeBlockedByGate(blockedEvent(
                "NOR", "NO_TRADE", 22.0, "engine-decision=NO_TRADE")))
                .doesNotThrowAnyException();
        }
    }
}

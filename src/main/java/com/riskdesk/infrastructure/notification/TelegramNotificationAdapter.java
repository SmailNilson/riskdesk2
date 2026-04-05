package com.riskdesk.infrastructure.notification;

import com.riskdesk.domain.notification.event.TradeValidatedEvent;
import com.riskdesk.domain.notification.port.NotificationPort;
import com.riskdesk.infrastructure.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

@Component
public class TelegramNotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationAdapter.class);
    private static final String TELEGRAM_API = "https://api.telegram.org/bot%s/sendMessage";

    private final TelegramProperties properties;
    private final RestTemplate restTemplate;

    public TelegramNotificationAdapter(TelegramProperties properties, RestTemplateBuilder restTemplateBuilder) {
        this.properties = properties;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void sendTradeValidated(TradeValidatedEvent event) {
        if (!properties.isEnabled()) {
            log.debug("Telegram notifications disabled — skipping trade alert for {}", event.instrument());
            return;
        }
        if (properties.getBotToken().isBlank() || properties.getChatId().isBlank()) {
            log.warn("Telegram bot-token or chat-id not configured — skipping notification");
            return;
        }

        String message = formatMessage(event);
        String url = String.format(TELEGRAM_API, properties.getBotToken());

        try {
            restTemplate.postForEntity(url, Map.of(
                    "chat_id", properties.getChatId(),
                    "text", message,
                    "parse_mode", "HTML"
            ), String.class);
            log.info("Telegram alert sent for {} {} {}", event.instrument(), event.action(), event.timeframe());
        } catch (Exception e) {
            log.error("Failed to send Telegram notification for {} — {}", event.instrument(), e.getMessage());
        }
    }

    private String formatMessage(TradeValidatedEvent e) {
        String directionEmoji = "LONG".equalsIgnoreCase(e.action()) ? "\uD83D\uDFE2" : "\uD83D\uDD34";
        String headerEmoji = "LONG".equalsIgnoreCase(e.action()) ? "\uD83D\uDCC8" : "\uD83D\uDCC9";

        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDEA8 <b>TRADE VALID\u00C9 \u2014 ").append(e.action()).append(" ").append(e.instrument()).append("</b>\n\n");

        sb.append(headerEmoji).append(" <b>Instrument:</b> ").append(e.instrument()).append(" (").append(instrumentLabel(e.instrument())).append(")\n");
        sb.append(directionEmoji).append(" <b>Direction:</b> ").append(e.action()).append("\n");
        sb.append("\u23F1 <b>Timeframe:</b> ").append(e.timeframe()).append("\n\n");

        sb.append("\u2550\u2550\u2550\u2550\u2550 Plan de Trade \u2550\u2550\u2550\u2550\u2550\n");
        sb.append("\u25B6\uFE0F <b>Entry:</b> ").append(formatPrice(e.entryPrice())).append("\n");
        if (e.deepEntryPrice() != null) {
            sb.append("\uD83D\uDD3D <b>Deep Entry:</b> ").append(formatPrice(e.deepEntryPrice())).append("\n");
        }
        sb.append("\uD83D\uDD34 <b>Stop Loss:</b> ").append(formatPrice(e.stopLoss())).append("\n");
        sb.append("\uD83D\uDFE2 <b>Take Profit:</b> ").append(formatPrice(e.takeProfit())).append("\n");
        if (e.rewardToRiskRatio() != null) {
            sb.append("\uD83D\uDCD0 <b>R:R Ratio:</b> ").append(String.format("%.2f", e.rewardToRiskRatio())).append("\n");
        }

        if (e.technicalQuickAnalysis() != null && !e.technicalQuickAnalysis().isBlank()) {
            sb.append("\n\uD83D\uDCA1 <b>Analyse:</b>\n").append(escapeHtml(e.technicalQuickAnalysis())).append("\n");
        }

        sb.append("\n\u26A1\uFE0F <i>RiskDesk \u2014 Mentor IA</i>");
        return sb.toString();
    }

    private static String formatPrice(Double price) {
        return price != null ? String.format("%,.2f", price) : "N/A";
    }

    private static String instrumentLabel(String symbol) {
        return switch (symbol) {
            case "MCL" -> "Micro WTI Crude";
            case "MGC" -> "Micro Gold";
            case "6E", "E6" -> "Euro FX";
            case "MNQ" -> "Micro Nasdaq-100";
            default -> symbol;
        };
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

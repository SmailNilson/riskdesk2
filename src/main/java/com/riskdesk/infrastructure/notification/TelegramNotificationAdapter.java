package com.riskdesk.infrastructure.notification;

import com.riskdesk.domain.notification.event.TradeBlockedByStrategyGateEvent;
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
        int decimals = priceDecimals(e.instrument());

        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDEA8 <b>TRADE VALID\u00C9 \u2014 ").append(e.action()).append(" ").append(e.instrument()).append("</b>\n\n");

        sb.append(headerEmoji).append(" <b>Instrument:</b> ").append(e.instrument()).append(" (").append(instrumentLabel(e.instrument())).append(")\n");
        sb.append(directionEmoji).append(" <b>Direction:</b> ").append(e.action()).append("\n");
        sb.append("\u23F1 <b>Timeframe:</b> ").append(e.timeframe()).append("\n\n");

        sb.append("\u2550\u2550\u2550\u2550\u2550 Plan de Trade \u2550\u2550\u2550\u2550\u2550\n");
        sb.append("\u25B6\uFE0F <b>Entry:</b> ").append(formatPrice(e.entryPrice(), decimals)).append("\n");
        if (e.deepEntryPrice() != null) {
            sb.append("\uD83D\uDD3D <b>Deep Entry:</b> ").append(formatPrice(e.deepEntryPrice(), decimals)).append("\n");
        }
        sb.append("\uD83D\uDD34 <b>Stop Loss:</b> ").append(formatPrice(e.stopLoss(), decimals)).append("\n");
        sb.append("\uD83D\uDFE2 <b>Take Profit:</b> ").append(formatPrice(e.takeProfit(), decimals)).append("\n");
        if (e.rewardToRiskRatio() != null) {
            sb.append("\uD83D\uDCD0 <b>R:R Ratio:</b> ").append(String.format("%.2f", e.rewardToRiskRatio())).append("\n");
        }

        // Strategy engine verdict — appended only when the engine actually
        // evaluated. `strategyAgreesWithReview == null` means the engine did
        // not run (optional collaborator), so we skip the line entirely rather
        // than display a noisy "engine info unavailable".
        if (e.strategyPlaybookId() != null || e.strategyDecision() != null) {
            sb.append("\n\uD83C\uDFAF <b>Engine:</b> ")                 // 🎯
              .append(e.strategyPlaybookId() == null ? "?" : e.strategyPlaybookId())
              .append(" \u00B7 ")
              .append(e.strategyDecision() == null ? "?" : e.strategyDecision());
            if (e.strategyFinalScore() != null) {
                sb.append(" \u00B7 ").append(formatScore(e.strategyFinalScore()));
            }
            sb.append(" ").append(agreementEmoji(e.strategyAgreesWithReview())).append("\n");
        }

        if (e.technicalQuickAnalysis() != null && !e.technicalQuickAnalysis().isBlank()) {
            sb.append("\n\uD83D\uDCA1 <b>Analyse:</b>\n").append(escapeHtml(e.technicalQuickAnalysis())).append("\n");
        }

        sb.append("\n\u26A1\uFE0F <i>RiskDesk \u2014 Mentor IA</i>");
        return sb.toString();
    }

    @Override
    public void sendTradeBlockedByGate(TradeBlockedByStrategyGateEvent event) {
        if (!properties.isEnabled()) {
            log.debug("Telegram notifications disabled — skipping gate-block alert for {}", event.instrument());
            return;
        }
        if (properties.getBotToken().isBlank() || properties.getChatId().isBlank()) {
            log.warn("Telegram bot-token or chat-id not configured — skipping gate-block notification");
            return;
        }

        String message = formatBlockedMessage(event);
        String url = String.format(TELEGRAM_API, properties.getBotToken());

        try {
            restTemplate.postForEntity(url, Map.of(
                    "chat_id", properties.getChatId(),
                    "text", message,
                    "parse_mode", "HTML"
            ), String.class);
            log.info("Telegram gate-block alert sent for {} {} {} (review {})",
                event.instrument(), event.action(), event.timeframe(), event.reviewId());
        } catch (Exception e) {
            log.error("Failed to send Telegram gate-block notification for {} — {}",
                event.instrument(), e.getMessage());
        }
    }

    private String formatBlockedMessage(TradeBlockedByStrategyGateEvent e) {
        StringBuilder sb = new StringBuilder();
        // 🛑 + bold header
        sb.append("\uD83D\uDED1 <b>TRADE BLOQU\u00C9 PAR STRATEGY GATE \u2014 ")
          .append(e.action() == null ? "?" : e.action()).append(" ")
          .append(e.instrument()).append("</b>\n\n");

        sb.append("\uD83D\uDCC8 <b>Instrument:</b> ").append(e.instrument())
          .append(" (").append(instrumentLabel(e.instrument())).append(")\n");
        sb.append("\u23F1 <b>Timeframe:</b> ").append(e.timeframe() == null ? "?" : e.timeframe()).append("\n");
        if (e.reviewId() != null) {
            sb.append("\uD83D\uDCDD <b>Review:</b> #").append(e.reviewId()).append("\n");
        }
        sb.append("\n\u2550\u2550\u2550\u2550\u2550 Concurrence Check \u2550\u2550\u2550\u2550\u2550\n");
        sb.append("\u2705 <b>Mentor legacy:</b> ELIGIBLE\n");  // gate only fires after legacy approved
        sb.append("\u274C <b>Strategy engine:</b> ");
        if (e.strategyPlaybookId() != null || e.strategyDecision() != null) {
            sb.append(e.strategyPlaybookId() == null ? "?" : e.strategyPlaybookId())
              .append(" \u00B7 ")
              .append(e.strategyDecision() == null ? "?" : e.strategyDecision());
            if (e.strategyFinalScore() != null) {
                sb.append(" \u00B7 ").append(formatScore(e.strategyFinalScore()));
            }
            sb.append("\n");
        } else {
            sb.append("(no evaluation)\n");
        }
        sb.append("\uD83D\uDD34 <b>Raison:</b> ").append(escapeHtml(e.gateReason() == null ? "?" : e.gateReason())).append("\n");

        sb.append("\n\u26A0\uFE0F <i>No IBKR order was placed. Trade halted before broker contact.</i>\n");
        sb.append("\u26A1\uFE0F <i>RiskDesk \u2014 Strategy Execution Gate</i>");
        return sb.toString();
    }

    /**
     * "+72.3" or "-55.8" — signed one-decimal. {@link java.util.Locale#ROOT}
     * forces the decimal separator to "." regardless of the JVM default locale;
     * a French-locale JVM was producing "72,3" which broke Telegram parsing
     * on the dashboard and test-string assertions.
     */
    private static String formatScore(double score) {
        String sign = score > 0 ? "+" : "";
        return sign + String.format(java.util.Locale.ROOT, "%.1f", score);
    }

    /**
     * ✅ when the engine agrees with the Mentor review, ⚠️ when it disagrees,
     * 🤷 when the engine didn't evaluate at all (null). We show ⚠️ rather than
     * ❌ for disagreement because on the validated path the trade IS going
     * ahead — the disagreement is information, not a block (the block case
     * has its own {@link #formatBlockedMessage} format).
     */
    private static String agreementEmoji(Boolean agrees) {
        if (agrees == null) return "\uD83E\uDD37";            // 🤷
        return agrees ? "\u2705" : "\u26A0\uFE0F";              // ✅ or ⚠️
    }

    private static String formatPrice(Double price, int decimals) {
        return price != null ? String.format("%,." + decimals + "f", price) : "N/A";
    }

    private static int priceDecimals(String instrument) {
        return switch (instrument) {
            case "E6", "6E" -> 5;
            default -> 2;
        };
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

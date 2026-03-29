package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.HistoricalTradesDTO;
import com.riskdesk.application.dto.MarketStructureDTO;
import com.riskdesk.application.dto.TradeDTO;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.infrastructure.config.HistoricalTradeImportProperties;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import com.riskdesk.domain.shared.TradingSessionResolver;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Order(4)
public class HistoricalTradeImporterService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HistoricalTradeImporterService.class);
    private static final DateTimeFormatter TRADE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final MentorAuditRepositoryPort mentorAuditRepository;
    private final MentorMemoryService mentorMemoryService;
    private final GeminiEmbeddingClient embeddingClient;
    private final HistoricalTradeImportProperties properties;
    private final MentorProperties mentorProperties;

    public HistoricalTradeImporterService(ObjectMapper objectMapper,
                                         MentorAuditRepositoryPort mentorAuditRepository,
                                         MentorMemoryService mentorMemoryService,
                                         GeminiEmbeddingClient embeddingClient,
                                         HistoricalTradeImportProperties properties,
                                         MentorProperties mentorProperties) {
        this.objectMapper = objectMapper;
        this.mentorAuditRepository = mentorAuditRepository;
        this.mentorMemoryService = mentorMemoryService;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.mentorProperties = mentorProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getFilePath() == null || properties.getFilePath().isBlank()) {
            log.warn("Historical trade import enabled but no file path is configured.");
            return;
        }

        ImportSummary summary = importFromFile(Path.of(properties.getFilePath()));
        log.info(
            "Historical trade import finished: {} imported, {} skipped, {} failed from {}.",
            summary.imported(),
            summary.skipped(),
            summary.failed(),
            properties.getFilePath()
        );
    }

    public ImportSummary importFromFile(Path path) {
        try (var inputStream = Files.newInputStream(path)) {
            HistoricalTradesDTO payload = objectMapper.readValue(inputStream, HistoricalTradesDTO.class);
            return importTrades(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read historical trades file: " + path, e);
        }
    }

    public ImportSummary importTrades(HistoricalTradesDTO payload) {
        if (payload == null || payload.trades() == null || payload.trades().isEmpty()) {
            return new ImportSummary(0, 0, 0);
        }

        logModelMismatchIfNeeded(properties.getEmbeddingModel());

        int imported = 0;
        int skipped = 0;
        int failed = 0;

        for (TradeDTO trade : payload.trades()) {
            if (trade == null) {
                skipped++;
                continue;
            }

            String sourceRef = buildSourceRef(trade);
            if (mentorAuditRepository.findBySourceRef(sourceRef).isPresent()) {
                skipped++;
                continue;
            }

            try {
                String semanticText = buildTextForEmbedding(trade);
                List<Double> embedding = embeddingClient.embed(semanticText, properties.getEmbeddingModel());
                MentorAudit saved = mentorAuditRepository.save(toMentorAudit(payload.instrument(), trade, sourceRef, semanticText));
                mentorMemoryService.indexAudit(saved, embedding, properties.getEmbeddingModel());
                imported++;
            } catch (Exception e) {
                failed++;
                log.warn("Historical trade import failed for {}: {}", trade.tradeId(), e.getMessage());
            }
        }

        return new ImportSummary(imported, skipped, failed);
    }

    public String currentEmbeddingModel() {
        return properties.getEmbeddingModel();
    }

    public String buildTextForEmbedding(TradeDTO trade) {
        List<String> sentences = new ArrayList<>();
        String baseSentence = trade.entryPrice() == null
            ? String.format(
                Locale.ROOT,
                "Trade: %s %s on %s.",
                valueOrFallback(trade.instrument(), "Unknown"),
                valueOrFallback(trade.direction(), "Unknown"),
                valueOrFallback(trade.timeframe(), "Unknown")
            )
            : String.format(
                Locale.ROOT,
                "Trade: %s %s on %s at %.4f.",
                valueOrFallback(trade.instrument(), "Unknown"),
                valueOrFallback(trade.direction(), "Unknown"),
                valueOrFallback(trade.timeframe(), "Unknown"),
                trade.entryPrice()
            );
        sentences.add(baseSentence);

        String instrumentContext = joinNonBlank(" | ", trade.symbolDetected(), trade.session(), trade.analysisMode());
        if (!instrumentContext.isBlank()) {
            sentences.add("Execution context: " + instrumentContext + ".");
        }

        String tradePlan = formatTradePlan(trade);
        if (!tradePlan.isBlank()) {
            sentences.add("Plan: " + tradePlan + ".");
        }

        if (trade.aiVerdict() != null || trade.tradeQuality() != null) {
            sentences.add(String.format(
                Locale.ROOT,
                "Verdict: %s%s.",
                valueOrFallback(trade.aiVerdict(), "Unknown"),
                trade.tradeQuality() == null || trade.tradeQuality().isBlank() ? "" : " (" + trade.tradeQuality() + ")"
            ));
        }
        if (trade.confidenceScore() != null || trade.riskReward() != null) {
            sentences.add(String.format(
                Locale.ROOT,
                "Confidence: %s%s.",
                trade.confidenceScore() == null ? "Unknown" : trade.confidenceScore() + "/100",
                trade.riskReward() == null ? "" : " | Risk-reward: " + formatDecimal(trade.riskReward())
            ));
        }

        MarketStructureDTO structure = trade.marketStructure();
        if (structure != null) {
            String structureText = joinNonBlank(" - ", structure.trendFocus(), structure.lastEvent());
            if (!structureText.isBlank()) {
                sentences.add("Structure: " + structureText + ".");
            }
            if (structure.lastEventPrice() != null) {
                sentences.add("Structure trigger price: " + formatDecimal(structure.lastEventPrice()) + ".");
            }
            if (structure.notes() != null && !structure.notes().isBlank()) {
                sentences.add("Structure notes: " + structure.notes().trim() + ".");
            }
        }

        if (trade.rawContextExcerpt() != null && !trade.rawContextExcerpt().isBlank()) {
            sentences.add("Context: " + trade.rawContextExcerpt().trim() + ".");
        }
        List<String> sanitizedReasons = trade.reasons() == null ? List.of() : sanitizeList(trade.reasons());
        if (!sanitizedReasons.isEmpty()) {
            sentences.add("Reasons: " + String.join(" ", sanitizedReasons) + ".");
        }
        if (trade.outcomeIfKnown() != null && !trade.outcomeIfKnown().isBlank()) {
            sentences.add("Outcome: " + trade.outcomeIfKnown().trim() + ".");
        }
        if (trade.hasScreenshot() != null) {
            sentences.add("Screenshot attached: " + (trade.hasScreenshot() ? "yes" : "no") + ".");
        }
        if (trade.notes() != null && !trade.notes().isBlank()) {
            sentences.add("Notes: " + trade.notes().trim() + ".");
        }
        if (trade.tradeId() != null && !trade.tradeId().isBlank()) {
            sentences.add("Trade ID: " + trade.tradeId().trim() + ".");
        }
        if (trade.dedupeKey() != null && !trade.dedupeKey().isBlank()) {
            sentences.add("Dedupe key: " + trade.dedupeKey().trim() + ".");
        }

        return String.join(" ", sentences).replaceAll("\\s+", " ").trim();
    }

    private MentorAudit toMentorAudit(String rootInstrument, TradeDTO trade, String sourceRef, String semanticText) throws IOException {
        MentorAudit audit = new MentorAudit();
        audit.setSourceRef(sourceRef);
        audit.setCreatedAt(parseTimestamp(trade.timestamp()));
        audit.setInstrument(firstNonBlank(trade.instrument(), rootInstrument));
        audit.setTimeframe(trade.timeframe());
        audit.setAction(normalizeDirection(trade.direction()));
        audit.setModel("historical-trade-import");
        audit.setPayloadJson(objectMapper.writeValueAsString(trade));
        audit.setResponseJson(objectMapper.writeValueAsString(buildResponseSummary(trade)));
        audit.setVerdict(trade.aiVerdict());
        audit.setSuccess(true);
        audit.setSemanticText(semanticText);
        return audit;
    }

    private Map<String, Object> buildResponseSummary(TradeDTO trade) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", "historical-trade-import");
        summary.put("tradeId", trade.tradeId());
        summary.put("dedupeKey", trade.dedupeKey());
        summary.put("symbolDetected", trade.symbolDetected());
        summary.put("session", trade.session());
        summary.put("analysisMode", trade.analysisMode());
        summary.put("aiVerdict", trade.aiVerdict());
        summary.put("tradeQuality", trade.tradeQuality());
        summary.put("confidenceScore", trade.confidenceScore());
        summary.put("riskReward", trade.riskReward());
        summary.put("outcomeIfKnown", trade.outcomeIfKnown());
        summary.put("hasScreenshot", trade.hasScreenshot());
        summary.put("notes", trade.notes());
        return summary;
    }

    private Instant parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return Instant.now();
        }

        try {
            return LocalDateTime.parse(timestamp.trim(), TRADE_TIMESTAMP_FORMATTER)
                .atZone(TradingSessionResolver.CME_ZONE)
                .toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(timestamp.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalStateException("Unsupported trade timestamp format: " + timestamp, e);
            }
        }
    }

    private String buildSourceRef(TradeDTO trade) {
        String identity = firstNonBlank(trade.dedupeKey(), trade.tradeId());
        if (identity == null) {
            identity = "unknown";
        }
        return "historical-trade:" + identity;
    }

    private String normalizeDirection(String direction) {
        return direction == null ? null : direction.trim().toUpperCase(Locale.ROOT);
    }

    private void logModelMismatchIfNeeded(String importEmbeddingModel) {
        if (importEmbeddingModel == null || importEmbeddingModel.isBlank()) {
            return;
        }
        String activeModel = mentorProperties.getEmbeddingsModel();
        if (importEmbeddingModel.equals(activeModel)) {
            return;
        }
        log.warn(
            "Historical trade import uses embedding model '{}' while mentor retrieval currently uses '{}'. " +
                "Imported memories will only be retrieved once both models are aligned.",
            importEmbeddingModel,
            activeModel
        );
    }

    private List<String> sanitizeList(List<String> values) {
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .toList();
    }

    private String joinNonBlank(String separator, String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parts.add(value.trim());
            }
        }
        return String.join(separator, parts);
    }

    private String formatTradePlan(TradeDTO trade) {
        List<String> parts = new ArrayList<>();
        if (trade.stopLoss() != null) {
            parts.add("SL " + formatDecimal(trade.stopLoss()));
        }
        if (trade.takeProfit() != null) {
            parts.add("TP " + formatDecimal(trade.takeProfit()));
        }
        if (trade.marketOrder() != null) {
            parts.add(trade.marketOrder() ? "Market order" : "Limit/conditional order");
        }
        return String.join(" | ", parts);
    }

    private String formatDecimal(Double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String valueOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    public record ImportSummary(int imported, int skipped, int failed) {
    }
}

package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.IndicatorSeriesSnapshot;
import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.application.dto.MentorSignalReview;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeSimulationStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@Service
public class MentorSignalReviewService {

    private static final int DEFAULT_RECENT_REVIEWS = 500;
    private static final int MAX_RECENT_REVIEWS = 1000;
    private static final String STATUS_ANALYZING = "ANALYZING";
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_ERROR = "ERROR";
    private static final String TRIGGER_INITIAL = "INITIAL";
    private static final String TRIGGER_MANUAL_REANALYSIS = "MANUAL_REANALYSIS";

    private final MentorAnalysisService mentorAnalysisService;
    private final IndicatorService indicatorService;
    private final MentorIntermarketService mentorIntermarketService;
    private final ObjectProvider<MarketDataService> marketDataServiceProvider;
    private final CandleRepositoryPort candleRepositoryPort;
    private final MentorSignalReviewRepositoryPort reviewRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /** Auto-analysis mode: when false, incoming alerts are NOT forwarded to Gemini. Default OFF. */
    private volatile boolean autoAnalysisEnabled = false;

    public boolean isAutoAnalysisEnabled() { return autoAnalysisEnabled; }
    public void setAutoAnalysisEnabled(boolean enabled) { autoAnalysisEnabled = enabled; }

    public MentorSignalReviewService(MentorAnalysisService mentorAnalysisService,
                                     IndicatorService indicatorService,
                                     MentorIntermarketService mentorIntermarketService,
                                     ObjectProvider<MarketDataService> marketDataServiceProvider,
                                     CandleRepositoryPort candleRepositoryPort,
                                     MentorSignalReviewRepositoryPort reviewRepository,
                                     SimpMessagingTemplate messagingTemplate,
                                     ObjectMapper objectMapper) {
        this.mentorAnalysisService = mentorAnalysisService;
        this.indicatorService = indicatorService;
        this.mentorIntermarketService = mentorIntermarketService;
        this.marketDataServiceProvider = marketDataServiceProvider;
        this.candleRepositoryPort = candleRepositoryPort;
        this.reviewRepository = reviewRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    public void captureInitialReview(Alert alert) {
        captureInitialReview(alert, null);
    }

    /**
     * Batch-capture: groups alerts by direction and creates ONE combined review
     * per direction instead of one per indicator.
     * No-op when auto-analysis is disabled.
     */
    public void captureGroupReview(List<Alert> alerts, IndicatorSnapshot focusSnapshot) {
        if (!autoAnalysisEnabled) return;
        // Group by direction (LONG/SHORT)
        Map<String, List<Alert>> byDirection = new LinkedHashMap<>();
        for (Alert alert : alerts) {
            String action = inferAction(alert.message());
            if (action == null) continue;
            byDirection.computeIfAbsent(action, k -> new ArrayList<>()).add(alert);
        }

        for (Map.Entry<String, List<Alert>> entry : byDirection.entrySet()) {
            List<Alert> group = entry.getValue();
            // Use the first alert as the "primary" for the review record,
            // but include all indicator categories in the message
            Alert primary = group.get(0);
            List<String> categories = group.stream()
                .map(a -> a.category().name())
                .distinct()
                .toList();

            String combinedMessage = primary.message();
            if (categories.size() > 1) {
                // Enrich message with all firing indicators
                combinedMessage = primary.message() + " [+" + String.join(", ",
                    categories.subList(1, categories.size())) + "]";
            }

            // Build a combined alert with enriched message
            Alert combinedAlert = new Alert(
                primary.key(),
                primary.severity(),
                combinedMessage,
                primary.category(),
                primary.instrument()
            );
            captureInitialReview(combinedAlert, focusSnapshot);
        }
    }

    public void captureInitialReview(Alert alert, IndicatorSnapshot focusSnapshot) {
        if (!autoAnalysisEnabled) return;
        AlertReviewCandidate candidate = classify(alert);
        if (candidate == null) {
            return;
        }

        String alertKey = alertKey(alert);
        if (reviewRepository.existsByAlertKey(alertKey)) {
            return;
        }

        JsonNode payload = null;
        String snapshotJson = "{}";
        String snapshotError = null;
        try {
            payload = buildPayload(candidate, alert, focusSnapshot);
            snapshotJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            snapshotError = "Snapshot capture failed: " + errorMessage(e);
        }

        MentorSignalReviewRecord pending = newReviewRecord(
            alertKey,
            1,
            TRIGGER_INITIAL,
            STATUS_ANALYZING,
            alert,
            candidate,
            Instant.now(),
            snapshotJson
        );
        if (snapshotError != null) {
            pending.setStatus(STATUS_ERROR);
            pending.setCompletedAt(Instant.now());
            pending.setErrorMessage(snapshotError);
        }

        MentorSignalReviewRecord saved = reviewRepository.save(pending);
        publish(saved);

        if (snapshotError == null && payload != null) {
            JsonNode reviewPayload = payload;
            CompletableFuture.runAsync(() -> analyzeAndPersist(saved.getId(), reviewPayload));
        }
    }

    public MentorSignalReview reanalyzeAlert(Alert alert) {
        AlertReviewCandidate candidate = classify(alert);
        if (candidate == null) {
            throw new IllegalArgumentException("unsupported alert for mentor review");
        }

        String alertKey = alertKey(alert);
        List<MentorSignalReviewRecord> thread = reviewRepository.findByAlertKeyOrderByRevisionAsc(alertKey);
        if (thread.isEmpty()) {
            throw new IllegalArgumentException("no saved mentor review exists for this alert");
        }

        MentorSignalReviewRecord baseReview = thread.get(0);
        Instant createdAt = Instant.now();
        MentorSignalReviewRecord pending = newReviewRecord(
            alertKey,
            thread.get(thread.size() - 1).getRevision() + 1,
            TRIGGER_MANUAL_REANALYSIS,
            STATUS_ANALYZING,
            alert,
            candidate,
            createdAt,
            "{}"
        );
        MentorSignalReviewRecord saved;
        try {
            JsonNode payload = buildPayload(candidate, alert, null, TRIGGER_MANUAL_REANALYSIS, baseReview);
            pending.setSnapshotJson(writeJson(payload));
            saved = reviewRepository.save(pending);
            publish(saved);
            JsonNode reviewPayload = payload;
            Long savedReviewId = saved.getId();
            CompletableFuture.runAsync(() -> analyzeAndPersist(savedReviewId, reviewPayload));
        } catch (Exception e) {
            pending.setStatus(STATUS_ERROR);
            pending.setCompletedAt(Instant.now());
            pending.setErrorMessage("Reanalysis snapshot capture failed: " + errorMessage(e));
            saved = reviewRepository.save(pending);
            publish(saved);
        }
        return toDto(saved);
    }

    public List<MentorSignalReview> getRecentReviews() {
        return getRecentReviews(DEFAULT_RECENT_REVIEWS);
    }

    public List<MentorSignalReview> getRecentReviews(int requestedLimit) {
        int safeLimit = Math.max(1, Math.min(requestedLimit, MAX_RECENT_REVIEWS));
        return reviewRepository.findRecent(safeLimit).stream()
            .map(this::toDto)
            .toList();
    }

    public List<MentorSignalReview> getReviewsForAlert(Alert alert) {
        return reviewRepository.findByAlertKeyOrderByRevisionAsc(alertKey(alert)).stream()
            .map(this::toDto)
            .toList();
    }

    private MentorSignalReviewRecord newReviewRecord(String alertKey,
                                                     int revision,
                                                     String triggerType,
                                                     String status,
                                                     Alert alert,
                                                     AlertReviewCandidate candidate,
                                                     Instant createdAt,
                                                     String snapshotJson) {
        MentorSignalReviewRecord review = new MentorSignalReviewRecord();
        review.setAlertKey(alertKey);
        review.setRevision(revision);
        review.setTriggerType(triggerType);
        review.setStatus(status);
        review.setSeverity(alert.severity().name());
        review.setCategory(alert.category().name());
        review.setMessage(alert.message());
        review.setInstrument(candidate.instrument().name());
        review.setTimeframe(candidate.timeframe());
        review.setAction(candidate.action());
        review.setAlertTimestamp(alert.timestamp());
        review.setCreatedAt(createdAt);
        review.setSnapshotJson(defaultSnapshotJson(snapshotJson));
        return review;
    }

    private void analyzeAndPersist(Long reviewId, JsonNode payload) {
        if (payload == null) {
            completeWithError(reviewId, "Saved alert snapshot is not available.");
            return;
        }

        try {
            MentorAnalyzeResponse analysis = mentorAnalysisService.analyze(payload, MentorAnalysisService.ALERT_SOURCE_PREFIX + reviewId);
            reviewRepository.findById(reviewId).ifPresent(review -> {
                review.setStatus(STATUS_DONE);
                review.setCompletedAt(Instant.now());
                review.setAnalysisJson(writeJson(analysis));
                review.setVerdict(analysis.analysis() == null ? null : analysis.analysis().verdict());
                review.setErrorMessage(null);
                initializeSimulationState(review, analysis);
                MentorSignalReviewRecord updated = reviewRepository.save(review);
                publish(updated);
            });
        } catch (Exception e) {
            completeWithError(reviewId, errorMessage(e));
        }
    }

    private void completeWithError(Long reviewId, String message) {
        reviewRepository.findById(reviewId).ifPresent(review -> {
            review.setStatus(STATUS_ERROR);
            review.setCompletedAt(Instant.now());
            review.setErrorMessage(message);
            review.setSimulationStatus(TradeSimulationStatus.CANCELLED);
            MentorSignalReviewRecord updated = reviewRepository.save(review);
            publish(updated);
        });
    }

    private void publish(MentorSignalReviewRecord review) {
        try {
            messagingTemplate.convertAndSend("/topic/mentor-alerts", toDto(review));
        } catch (Exception ignored) {
            // best effort only
        }
    }

    public MentorSignalReview toDto(MentorSignalReviewRecord review) {
        MentorAnalyzeResponse analysis = null;
        if (review.getAnalysisJson() != null && !review.getAnalysisJson().isBlank()) {
            try {
                analysis = objectMapper.readValue(review.getAnalysisJson(), MentorAnalyzeResponse.class);
            } catch (Exception ignored) {
                // best effort only
            }
        }

        return new MentorSignalReview(
            review.getId(),
            review.getAlertKey(),
            review.getRevision(),
            review.getTriggerType(),
            review.getStatus(),
            review.getSeverity(),
            review.getCategory(),
            review.getMessage(),
            review.getInstrument(),
            review.getTimeframe(),
            review.getAction(),
            review.getAlertTimestamp() == null ? null : review.getAlertTimestamp().toString(),
            review.getCreatedAt() == null ? null : review.getCreatedAt().toString(),
            review.getSimulationStatus(),
            review.getActivationTime() == null ? null : review.getActivationTime().toString(),
            review.getResolutionTime() == null ? null : review.getResolutionTime().toString(),
            review.getMaxDrawdownPoints() == null ? null : review.getMaxDrawdownPoints().doubleValue(),
            analysis,
            review.getErrorMessage()
        );
    }

    private void initializeSimulationState(MentorSignalReviewRecord review, MentorAnalyzeResponse analysis) {
        if (shouldTrackOutcome(review, analysis)) {
            review.setSimulationStatus(TradeSimulationStatus.PENDING_ENTRY);
        } else {
            review.setSimulationStatus(TradeSimulationStatus.CANCELLED);
        }
        review.setActivationTime(null);
        review.setResolutionTime(null);
        review.setMaxDrawdownPoints(BigDecimal.ZERO);
    }

    private boolean shouldTrackOutcome(MentorSignalReviewRecord review, MentorAnalyzeResponse analysis) {
        if (analysis == null || analysis.analysis() == null || analysis.analysis().proposedTradePlan() == null) {
            return false;
        }
        if (review.getVerdict() == null || !review.getVerdict().contains("Validé")) {
            return false;
        }
        return analysis.analysis().proposedTradePlan().entryPrice() != null
            && analysis.analysis().proposedTradePlan().stopLoss() != null
            && analysis.analysis().proposedTradePlan().takeProfit() != null;
    }

    private JsonNode buildPayload(AlertReviewCandidate candidate,
                                  Alert alert,
                                  IndicatorSnapshot precomputedFocusSnapshot,
                                  String triggerType,
                                  MentorSignalReviewRecord originalReview) {
        IndicatorSnapshot focusSnapshot = precomputedFocusSnapshot != null
            ? precomputedFocusSnapshot
            : indicatorService.computeSnapshot(candidate.instrument(), candidate.timeframe());
        IndicatorSnapshot h1Snapshot = "1h".equals(candidate.timeframe())
            ? focusSnapshot
            : indicatorService.computeSnapshot(candidate.instrument(), "1h");
        IndicatorSeriesSnapshot indicatorSeries = indicatorService.computeSeries(candidate.instrument(), candidate.timeframe(), 500);
        List<Candle> candles = loadCandles(candidate.instrument(), candidate.timeframe(), 120);
        MentorIntermarketSnapshot intermarket = mentorIntermarketService.current(candidate.instrument());
        boolean manualReanalysis = TRIGGER_MANUAL_REANALYSIS.equals(triggerType);
        MarketDataService.StoredPrice livePrice = livePrice(candidate.instrument());
        Instant contextTimestamp = liveTimestamp(livePrice, candles);
        BigDecimal currentPrice = currentPrice(livePrice, candles);
        BigDecimal effectiveEntry = currentPrice;
        BigDecimal atr = computeAtr(candles, 14, candidate.instrument());
        BigDecimal referencePrice = firstNonNull(currentPrice, focusSnapshot.vwap(), focusSnapshot.ema50(), BigDecimal.ZERO);
        Map<String, Object> nearestSupport = findNearestOrderBlock(focusSnapshot, referencePrice, "BULLISH");
        Map<String, Object> nearestResistance = findNearestOrderBlock(focusSnapshot, referencePrice, "BEARISH");
        Map<String, Object> originalAlertContext = manualReanalysis
            ? buildOriginalAlertContext(alert, originalReview)
            : null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("metadata", linkedMap(
            "timestamp", contextTimestamp.toString(),
            "asset", assetAlias(candidate.instrument()),
            "current_price", roundNullable(currentPrice, candidate.instrument()),
            "timeframe_focus", toMentorTimeframe(candidate.timeframe()),
            "market_session", inferMarketSession(contextTimestamp),
            "dashboard_connection_status", "LIVE",
            "selected_timezone", "UTC"
        ));
        payload.put("trade_intention", linkedMap(
            "action", candidate.action(),
            "analysis_mode", "SETUP_REVIEW",
            "review_type", triggerType,
            "entry_price", roundNullable(effectiveEntry, candidate.instrument()),
            "stop_loss", null,
            "take_profit", null,
            "time_to_candle_close_seconds", timeToCandleCloseSeconds(candidate.timeframe(), contextTimestamp),
            "is_market_order", !"ORDER_BLOCK".equals(alert.category().name()),
            "mentor_should_propose_plan", true
        ));
        if (originalAlertContext != null) {
            payload.put("original_alert_context", originalAlertContext);
        }
        payload.put("market_structure_the_king", linkedMap(
            "trend_H1", h1Snapshot.marketStructureTrend(),
            "trend_focus", focusSnapshot.marketStructureTrend(),
            "focus_timeframe", toMentorTimeframe(candidate.timeframe()),
            "last_event", focusSnapshot.lastBreakType(),
            "last_event_price", focusSnapshot.recentBreaks().isEmpty() ? null : focusSnapshot.recentBreaks().get(0).level(),
            "nearest_support_ob", nearestSupport,
            "nearest_resistance_ob", nearestResistance,
            "key_psychological_level_proximity", nearestPsychologicalLevel(currentPrice, candidate.instrument())
        ));
        payload.put("dynamic_levels_and_vwap", linkedMap(
            "vwap_value", focusSnapshot.vwap(),
            "distance_to_vwap_points", distance(currentPrice, focusSnapshot.vwap(), candidate.instrument()),
            "ma_fast_red_value", focusSnapshot.ema50(),
            "ma_slow_blue_value", focusSnapshot.ema200(),
            "distance_to_ma_slow_points", distance(currentPrice, focusSnapshot.ema200(), candidate.instrument())
        ));
        payload.put("momentum_and_flow_the_trigger", linkedMap(
            "money_flow_state", inferMoneyFlowState(focusSnapshot),
            "money_flow_trend", inferMoneyFlowTrend(focusSnapshot),
            "oscillator_value", focusSnapshot.rsi(),
            "oscillator_signal", focusSnapshot.rsiSignal(),
            "divergence_detected", false,
            "divergence_type", null
        ));
        payload.put("intermarket_correlations_the_edge", linkedMap(
            "dxy_pct_change", intermarket.dxyPctChange(),
            "dxy_trend", intermarket.dxyTrend(),
            "silver_si1_pct_change", intermarket.silverSi1PctChange(),
            "gold_mgc1_pct_change", intermarket.goldMgc1PctChange(),
            "plat_pl1_pct_change", intermarket.platPl1PctChange(),
            "metals_convergence_status", intermarket.metalsConvergenceStatus()
        ));
        payload.put("risk_and_emotional_check", linkedMap(
            "current_atr_focus", atr,
            "stop_loss_size_points", null,
            "reward_to_risk_ratio", null,
            "is_sl_structurally_protected", null,
            "price_extension_warning", isPriceExtended(currentPrice, focusSnapshot, atr)
        ));
        payload.put("riskdesk_context", linkedMap(
            "portfolio_state_shared", false,
            "total_unrealized_pnl", null,
            "today_realized_pnl", null,
            "margin_used_pct", null,
            "active_signals", activeSignals(focusSnapshot),
            "recent_alerts", List.of(linkedMap(
                "severity", alert.severity().name(),
                "category", alert.category().name(),
                "message", alert.message(),
                "instrument", alert.instrument(),
                "timestamp", alert.timestamp().toString()
            )),
            "chart_series_summary", linkedMap(
                "candles_loaded", candles.size(),
                "wave_trend_points", indicatorSeries.waveTrend().size()
            )
        ));
        return objectMapper.valueToTree(payload);
    }

    private JsonNode buildPayload(AlertReviewCandidate candidate, Alert alert, IndicatorSnapshot precomputedFocusSnapshot) {
        return buildPayload(candidate, alert, precomputedFocusSnapshot, TRIGGER_INITIAL, null);
    }

    private List<Candle> loadCandles(Instrument instrument, String timeframe, int limit) {
        List<Candle> candles = new ArrayList<>(candleRepositoryPort.findRecentCandles(instrument, timeframe, limit));
        candles.sort(Comparator.comparing(Candle::getTimestamp));
        return candles;
    }

    private BigDecimal currentPrice(MarketDataService.StoredPrice livePrice, List<Candle> candles) {
        if (livePrice != null && livePrice.price() != null) {
            return livePrice.price();
        }
        return candles.isEmpty() ? null : candles.get(candles.size() - 1).getClose();
    }

    private Instant liveTimestamp(MarketDataService.StoredPrice livePrice, List<Candle> candles) {
        if (livePrice != null && livePrice.timestamp() != null) {
            return livePrice.timestamp();
        }
        return candles.isEmpty() ? Instant.now() : candles.get(candles.size() - 1).getTimestamp();
    }

    private MarketDataService.StoredPrice livePrice(Instrument instrument) {
        MarketDataService marketDataService = marketDataServiceProvider.getIfAvailable();
        return marketDataService == null ? null : marketDataService.currentPrice(instrument);
    }

    private List<String> activeSignals(IndicatorSnapshot focusSnapshot) {
        return Stream.of(
                focusSnapshot.rsiSignal(),
                focusSnapshot.macdCrossover(),
                focusSnapshot.wtSignal(),
                focusSnapshot.wtCrossover(),
                focusSnapshot.bbTrendSignal())
            .filter(value -> value != null && !value.isBlank())
            .toList();
    }

    private AlertReviewCandidate classify(Alert alert) {
        if (alert.instrument() == null || alert.instrument().isBlank()) {
            return null;
        }

        Instrument instrument;
        try {
            instrument = Instrument.valueOf(alert.instrument().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }

        if (!isEligibleCategory(alert.category().name(), alert.message())) {
            return null;
        }

        String action = inferAction(alert.message());
        if (action == null) {
            return null;
        }

        return new AlertReviewCandidate(instrument, parseTimeframe(alert.message()), action);
    }

    private String alertKey(Alert alert) {
        return alert.timestamp() + ":" + (alert.instrument() == null || alert.instrument().isBlank() ? "GLOBAL" : alert.instrument())
            + ":" + alert.category().name() + ":" + alert.message();
    }

    private Map<String, Object> buildOriginalAlertContext(Alert alert, MentorSignalReviewRecord originalReview) {
        JsonNode originalSnapshot = parseSnapshot(originalReview == null ? null : originalReview.getSnapshotJson());
        BigDecimal originalAlertPrice = extractOriginalAlertPrice(originalSnapshot);
        return linkedMap(
            "original_alert_time", alert.timestamp().toString(),
            "original_alert_reason", extractOriginalAlertReason(alert, originalSnapshot),
            "original_alert_price", originalAlertPrice
        );
    }

    private BigDecimal extractOriginalAlertPrice(JsonNode originalSnapshot) {
        if (originalSnapshot == null) {
            return null;
        }
        JsonNode metadataPrice = originalSnapshot.path("metadata").path("current_price");
        if (metadataPrice.isNumber()) {
            return metadataPrice.decimalValue();
        }
        JsonNode entryPrice = originalSnapshot.path("trade_intention").path("entry_price");
        if (entryPrice.isNumber()) {
            return entryPrice.decimalValue();
        }
        return null;
    }

    private String extractOriginalAlertReason(Alert alert, JsonNode originalSnapshot) {
        if (originalSnapshot != null) {
            String lastEvent = originalSnapshot.path("market_structure_the_king").path("last_event").asText(null);
            if (lastEvent != null && !lastEvent.isBlank()) {
                return lastEvent;
            }
        }
        String normalized = alert.message();
        int idx = normalized.lastIndexOf(':');
        if (idx >= 0 && idx + 1 < normalized.length()) {
            return normalized.substring(idx + 1).trim();
        }
        return alert.category().name();
    }

    private boolean isEligibleCategory(String category, String message) {
        return switch (category) {
            case "SMC" -> message.contains("BOS") || message.contains("CHoCH");
            case "MACD" -> message.contains("Bullish Cross") || message.contains("Bearish Cross");
            case "WAVETREND" -> message.contains("Bullish Cross")
                || message.contains("Bearish Cross")
                || message.contains("oversold")
                || message.contains("overbought");
            case "RSI" -> message.contains("oversold") || message.contains("overbought");
            case "ORDER_BLOCK" -> message.contains("VWAP inside");
            default -> false;
        };
    }

    private String inferAction(String message) {
        String normalized = message.toUpperCase(Locale.ROOT);
        if (normalized.contains("BULLISH") || normalized.contains("OVERSOLD")) {
            return "LONG";
        }
        if (normalized.contains("BEARISH") || normalized.contains("OVERBOUGHT")) {
            return "SHORT";
        }
        return null;
    }

    private String parseTimeframe(String message) {
        int start = message.indexOf('[');
        int end = message.indexOf(']');
        if (start >= 0 && end > start) {
            String value = message.substring(start + 1, end).toLowerCase(Locale.ROOT);
            if (List.of("5m", "10m", "1h", "1d").contains(value)) {
                return value;
            }
        }
        return "10m";
    }

    private BigDecimal computeAtr(List<Candle> candles, int period, Instrument instrument) {
        if (candles.size() < period + 1) {
            return null;
        }

        List<BigDecimal> trs = new ArrayList<>();
        for (int i = 1; i < candles.size(); i += 1) {
            Candle current = candles.get(i);
            Candle previous = candles.get(i - 1);
            BigDecimal tr = max(
                current.getHigh().subtract(current.getLow()),
                current.getHigh().subtract(previous.getClose()).abs(),
                current.getLow().subtract(previous.getClose()).abs()
            );
            trs.add(tr);
        }

        List<BigDecimal> last = trs.subList(Math.max(0, trs.size() - period), trs.size());
        BigDecimal sum = last.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(BigDecimal.valueOf(last.size()), 6, RoundingMode.HALF_UP);
        return avg.compareTo(BigDecimal.ZERO) > 0 ? round(avg, instrument) : null;
    }

    private Map<String, Object> findNearestOrderBlock(IndicatorSnapshot snapshot, BigDecimal currentPrice, String bias) {
        if (currentPrice == null || snapshot.activeOrderBlocks() == null) {
            return null;
        }

        List<IndicatorSnapshot.OrderBlockView> filtered = snapshot.activeOrderBlocks().stream()
            .filter(block -> "BULLISH".equals(bias)
                ? block.mid().compareTo(currentPrice) <= 0
                : block.mid().compareTo(currentPrice) >= 0)
            .sorted((left, right) -> left.mid().subtract(currentPrice).abs().compareTo(right.mid().subtract(currentPrice).abs()))
            .toList();
        if (!filtered.isEmpty()) {
            IndicatorSnapshot.OrderBlockView nearest = filtered.get(0);
            return Map.of(
                "type", "BULLISH".equals(bias) ? "DEMAND" : "SUPPLY",
                "price_top", nearest.high(),
                "price_bottom", nearest.low(),
                "is_tested", false
            );
        }

        BigDecimal structuralLevel = "BULLISH".equals(bias)
            ? pickNearestLevel(currentPrice, List.of(snapshot.weakLow(), snapshot.strongLow()), true)
            : pickNearestLevel(currentPrice, List.of(snapshot.weakHigh(), snapshot.strongHigh()), false);
        if (structuralLevel == null) {
            return null;
        }

        BigDecimal zonePadding = currentPrice.abs().multiply(BigDecimal.valueOf(0.0005)).max(new BigDecimal("0.0005"));
        return Map.of(
            "type", "BULLISH".equals(bias) ? "DEMAND_SWING" : "SUPPLY_SWING",
            "price_top", "BULLISH".equals(bias) ? structuralLevel.add(zonePadding) : structuralLevel,
            "price_bottom", "BULLISH".equals(bias) ? structuralLevel : structuralLevel.subtract(zonePadding),
            "is_tested", false
        );
    }

    private BigDecimal pickNearestLevel(BigDecimal currentPrice, List<BigDecimal> levels, boolean below) {
        return levels.stream()
            .filter(level -> level != null && (below ? level.compareTo(currentPrice) <= 0 : level.compareTo(currentPrice) >= 0))
            .sorted((left, right) -> left.subtract(currentPrice).abs().compareTo(right.subtract(currentPrice).abs()))
            .findFirst()
            .orElse(null);
    }

    private String inferMoneyFlowState(IndicatorSnapshot snapshot) {
        if (snapshot.cmf() == null && snapshot.buyRatio() == null) {
            return "UNAVAILABLE";
        }
        if ((snapshot.cmf() != null && snapshot.cmf().compareTo(BigDecimal.ZERO) > 0)
            || (snapshot.buyRatio() != null && snapshot.buyRatio().compareTo(new BigDecimal("0.5")) >= 0)) {
            return "GREEN";
        }
        if ((snapshot.cmf() != null && snapshot.cmf().compareTo(BigDecimal.ZERO) < 0)
            || (snapshot.buyRatio() != null && snapshot.buyRatio().compareTo(new BigDecimal("0.5")) < 0)) {
            return "RED";
        }
        return "NEUTRAL";
    }

    private String inferMoneyFlowTrend(IndicatorSnapshot snapshot) {
        if ("BULLISH".equals(snapshot.deltaFlowBias())) {
            return "INCREASING";
        }
        if ("BEARISH".equals(snapshot.deltaFlowBias())) {
            return "DECREASING";
        }
        return "FLAT";
    }

    private boolean isPriceExtended(BigDecimal currentPrice, IndicatorSnapshot snapshot, BigDecimal atr) {
        if (currentPrice == null || snapshot.vwap() == null || atr == null) {
            return false;
        }
        return currentPrice.subtract(snapshot.vwap()).abs().compareTo(atr.multiply(BigDecimal.valueOf(1.5))) > 0;
    }

    private BigDecimal distance(BigDecimal left, BigDecimal right, Instrument instrument) {
        if (left == null || right == null) {
            return null;
        }
        return round(left.subtract(right).abs(), instrument);
    }

    private BigDecimal nearestPsychologicalLevel(BigDecimal price, Instrument instrument) {
        if (price == null) {
            return null;
        }
        BigDecimal step = switch (instrument) {
            case E6 -> new BigDecimal("0.005");
            case MNQ -> new BigDecimal("100");
            case MCL -> new BigDecimal("5");
            default -> new BigDecimal("10");
        };
        BigDecimal rounded = price.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
        return round(rounded, instrument);
    }

    private String assetAlias(Instrument instrument) {
        return switch (instrument) {
            case MCL -> "CL1!";
            case MGC -> "MGC1!";
            case E6 -> "6E1!";
            case MNQ -> "MNQ1!";
            case DXY -> "DX1!";
        };
    }

    private String toMentorTimeframe(String timeframe) {
        return switch (timeframe) {
            case "5m" -> "M5";
            case "10m" -> "M10";
            case "1h" -> "H1";
            default -> "D1";
        };
    }

    private String inferMarketSession(Instant timestamp) {
        int hour = timestamp.atOffset(ZoneOffset.UTC).getHour();
        if (hour >= 22 || hour < 6) {
            return "ASIAN_OPEN";
        }
        if (hour < 12) {
            return "LONDON";
        }
        if (hour < 20) {
            return "NEW_YORK";
        }
        return "OFF_HOURS";
    }

    private int timeToCandleCloseSeconds(String timeframe, Instant timestamp) {
        long minutes = switch (timeframe) {
            case "5m" -> 5L;
            case "10m" -> 10L;
            case "1h" -> 60L;
            default -> 1440L;
        };
        long bucket = minutes * 60L;
        long epochSeconds = timestamp.getEpochSecond();
        long nextClose = ((epochSeconds + bucket - 1) / bucket) * bucket;
        return (int) Math.max(0L, nextClose - epochSeconds);
    }

    private BigDecimal roundNullable(BigDecimal value, Instrument instrument) {
        return value == null ? null : round(value, instrument);
    }

    private BigDecimal round(BigDecimal value, Instrument instrument) {
        int scale = instrument == Instrument.E6 ? 5 : 2;
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    @SafeVarargs
    private final BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal max(BigDecimal... values) {
        BigDecimal current = values[0];
        for (int i = 1; i < values.length; i += 1) {
            if (values[i].compareTo(current) > 0) {
                current = values[i];
            }
        }
        return current;
    }

    private JsonNode parseSnapshot(String snapshotJson) {
        try {
            return objectMapper.readTree(defaultSnapshotJson(snapshotJson));
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String defaultSnapshotJson(String snapshotJson) {
        return snapshotJson == null || snapshotJson.isBlank() ? "{}" : snapshotJson;
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
            ? "Mentor analysis failed."
            : e.getMessage();
    }

    private Map<String, Object> linkedMap(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private record AlertReviewCandidate(Instrument instrument, String timeframe, String action) {
    }
}

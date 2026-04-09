package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.IndicatorSeriesSnapshot;
import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MacroCorrelationSnapshot;
import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.application.dto.MentorSignalReview;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.engine.indicators.MarketRegimeDetector;
import com.riskdesk.domain.engine.indicators.VolumeProfileCalculator;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeSimulationStatus;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.riskdesk.domain.notification.event.TradeValidatedEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@Service
public class MentorSignalReviewService {

    private static final Logger log = LoggerFactory.getLogger(MentorSignalReviewService.class);
    private static final int DEFAULT_RECENT_REVIEWS = 500;
    private static final int MAX_RECENT_REVIEWS = 1000;
    private static final String STATUS_ANALYZING = "ANALYZING";
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_ERROR = "ERROR";
    private static final String TRIGGER_INITIAL = "INITIAL";
    private final MarketRegimeDetector marketRegimeDetector = new MarketRegimeDetector();
    private static final String TRIGGER_MANUAL_REANALYSIS = "MANUAL_REANALYSIS";
    private static final String AUTO_SELECTED_TIMEZONE = "UTC";

    private final MentorAnalysisService mentorAnalysisService;
    private final IndicatorService indicatorService;
    private final MentorIntermarketService mentorIntermarketService;
    private final ObjectProvider<MarketDataService> marketDataServiceProvider;
    private final CandleRepositoryPort candleRepositoryPort;
    private final ActiveContractRegistry contractRegistry;
    private final MentorSignalReviewRepositoryPort reviewRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<TickDataPort> tickDataPortProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final VolumeProfileCalculator volumeProfileCalculator = new VolumeProfileCalculator();

    /** Auto-analysis mode: when false, incoming alerts are NOT forwarded to Gemini. */
    private volatile boolean autoAnalysisEnabled;

    public boolean isAutoAnalysisEnabled() { return autoAnalysisEnabled; }
    public void setAutoAnalysisEnabled(boolean enabled) { autoAnalysisEnabled = enabled; }

    public MentorSignalReviewService(MentorAnalysisService mentorAnalysisService,
                                     IndicatorService indicatorService,
                                     MentorIntermarketService mentorIntermarketService,
                                     ObjectProvider<MarketDataService> marketDataServiceProvider,
                                     CandleRepositoryPort candleRepositoryPort,
                                     ActiveContractRegistry contractRegistry,
                                     MentorSignalReviewRepositoryPort reviewRepository,
                                     SimpMessagingTemplate messagingTemplate,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<TickDataPort> tickDataPortProvider,
                                     ApplicationEventPublisher eventPublisher,
                                     @Value("${riskdesk.mentor.auto-analysis-enabled:true}") boolean autoAnalysisEnabled) {
        this.mentorAnalysisService = mentorAnalysisService;
        this.indicatorService = indicatorService;
        this.mentorIntermarketService = mentorIntermarketService;
        this.marketDataServiceProvider = marketDataServiceProvider;
        this.candleRepositoryPort = candleRepositoryPort;
        this.contractRegistry = contractRegistry;
        this.reviewRepository = reviewRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.tickDataPortProvider = tickDataPortProvider;
        this.eventPublisher = eventPublisher;
        this.autoAnalysisEnabled = autoAnalysisEnabled;
    }

    @PostConstruct
    void cleanupStaleAnalyzingReviews() {
        int cleaned = reviewRepository.markAnalyzingAsError("Server restarted while analysis was in progress.");
        if (cleaned > 0) {
            log.info("MentorSignalReviewService: marked {} stale ANALYZING reviews as ERROR on startup.", cleaned);
        }
    }

    public void captureInitialReview(Alert alert) {
        captureInitialReview(alert, null);
    }

    /**
     * Batch-capture: groups alerts by direction and creates ONE combined review
     * per direction instead of one per indicator.
     * When auto-analysis is disabled, captureInitialReview records ERROR placeholders
     * so the semantic dedup prevents re-review after restart.
     */
    @Deprecated
    public void captureGroupReview(List<Alert> alerts, IndicatorSnapshot focusSnapshot) {
        // Group by direction (LONG/SHORT)
        Map<String, List<Alert>> byDirection = new LinkedHashMap<>();
        for (Alert alert : alerts) {
            String action = inferAction(alert.message());
            if (action == null) continue;
            byDirection.computeIfAbsent(action, k -> new ArrayList<>()).add(alert);
        }

        for (Map.Entry<String, List<Alert>> entry : byDirection.entrySet()) {
            List<Alert> group = entry.getValue();
            Alert primary = group.get(0);
            // Persist the review against the exact primary alert shown in the live feed.
            // The UI correlates reviews back to alert groups through the original
            // timestamp/category/message triple, so rewriting any of those fields
            // breaks review attachment and makes fresh groups appear as "No Review".
            captureInitialReview(primary, focusSnapshot);
        }
    }

    /**
     * Confluence Engine entry point — receives a consolidated batch of signals
     * from {@link SignalConfluenceBuffer} after the weight threshold is reached.
     * Standalone signals (weight 3.0) flush immediately; secondary signals
     * need confluence to reach the threshold.
     */
    public void captureConsolidatedReview(List<Alert> signals,
                                           IndicatorSnapshot snap,
                                           float confluenceWeight,
                                           Alert primary,
                                           float opposingBufferWeight) {
        if (primary == null || signals.isEmpty()) return;
        if (primary.timestamp() != null &&
                primary.timestamp().isBefore(Instant.now().minusSeconds(MAX_ALERT_AGE_SECONDS))) {
            return;
        }

        AlertReviewCandidate candidate = classify(primary);
        if (candidate == null) return;

        String alertKey = primary.timestamp() + ":CONFLUENCE:"
                + primary.instrument() + ":" + primary.category().name() + ":" + candidate.action();
        if (reviewRepository.existsByAlertKey(alertKey)) return;

        // Semantic dedup on quadruplet (instrument, category, direction, timeframe)
        long dedupWindowSeconds = semanticDedupWindowSeconds(candidate.timeframe());
        if (reviewRepository.existsRecentReview(
                candidate.instrument().name(), primary.category().name(),
                candidate.action(), Instant.now().minusSeconds(dedupWindowSeconds))) {
            log.info("Confluence dedup: skipping {} {} {} — already analyzed within {}s window",
                     candidate.instrument(), primary.category(), candidate.action(), dedupWindowSeconds);
            return;
        }

        if (!autoAnalysisEnabled) {
            MentorSignalReviewRecord skipped = newReviewRecord(
                    alertKey, 1, TRIGGER_INITIAL, STATUS_ERROR,
                    primary, candidate, Instant.now(), AUTO_SELECTED_TIMEZONE, "{}");
            skipped.setCompletedAt(Instant.now());
            skipped.setErrorMessage("Auto-analyse desactivee au moment de l'alerte.");
            reviewRepository.save(skipped);
            return;
        }

        JsonNode payload = null;
        String snapshotJson = "{}";
        String snapshotError = null;
        try {
            payload = buildPayload(candidate, primary, snap, AUTO_SELECTED_TIMEZONE);
            // Enrich with confluence data
            if (payload instanceof com.fasterxml.jackson.databind.node.ObjectNode payloadNode) {
                var csArray = objectMapper.createArrayNode();
                for (Alert signal : signals) {
                    var sw = com.riskdesk.domain.alert.model.SignalWeight.fromAlert(signal);
                    var sigNode = objectMapper.createObjectNode();
                    sigNode.put("category", signal.category().name());
                    sigNode.put("message", signal.message());
                    sigNode.put("weight", sw != null ? sw.weight() : 0f);
                    sigNode.put("fired_at", signal.timestamp().toString());
                    csArray.add(sigNode);
                }
                payloadNode.set("confluence_signals", csArray);
                payloadNode.put("confluence_strength", signals.size());
                payloadNode.put("confluence_weight", confluenceWeight);
                payloadNode.put("primary_signal", primary.category().name());
                payloadNode.put("opposing_buffer_weight", opposingBufferWeight);
            }
            snapshotJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            snapshotError = "Confluence review payload failed: " + errorMessage(e);
        }

        String consolidatedMessage = buildConsolidatedMessage(signals, candidate);

        MentorSignalReviewRecord pending = newReviewRecord(
                alertKey, 1, TRIGGER_INITIAL, STATUS_ANALYZING,
                primary, candidate, Instant.now(), AUTO_SELECTED_TIMEZONE, snapshotJson);
        pending.setMessage(consolidatedMessage);
        pending.setTriggerPrice(resolveTriggerPrice(snap));
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

    /**
     * Builds a human-readable consolidated message from all signals in a confluence flush.
     * Example: "MNQ [10m] — Swing CHoCH: CHOCH_BULLISH — WaveTrend Bearish Cross"
     */
    private String buildConsolidatedMessage(List<Alert> signals, AlertReviewCandidate candidate) {
        if (signals.isEmpty()) return "";
        String prefix = candidate.instrument().name() + " [" + candidate.timeframe() + "]";
        // Extract short description from each signal message, removing the instrument/timeframe prefix
        List<String> parts = new ArrayList<>();
        for (Alert signal : signals) {
            String msg = signal.message();
            // Strip common prefix patterns like "Micro E-mini Nasdaq-100 [10m] — "
            int dashIdx = msg.indexOf(" — ");
            if (dashIdx >= 0 && dashIdx < msg.length() - 3) {
                parts.add(msg.substring(dashIdx + 3).trim());
            } else {
                parts.add(msg);
            }
        }
        return prefix + " — " + String.join(" — ", parts);
    }

    /** Maximum age of an alert eligible for auto-analysis (10 minutes). */
    private static final long MAX_ALERT_AGE_SECONDS = 600;

    public void captureInitialReview(Alert alert, IndicatorSnapshot focusSnapshot) {
        // Reject stale alerts (e.g. data bursts replaying old signals)
        if (alert.timestamp() != null &&
                alert.timestamp().isBefore(Instant.now().minusSeconds(MAX_ALERT_AGE_SECONDS))) {
            return;
        }

        AlertReviewCandidate candidate = classify(alert);
        if (candidate == null) {
            return;
        }

        String alertKey = alertKey(alert);
        if (reviewRepository.existsByAlertKey(alertKey)) {
            return;
        }

        // Semantic dedup: same instrument + category + direction recently analyzed?
        long dedupWindowSeconds = semanticDedupWindowSeconds(candidate.timeframe());
        if (reviewRepository.existsRecentReview(
                candidate.instrument().name(), alert.category().name(),
                candidate.action(), Instant.now().minusSeconds(dedupWindowSeconds))) {
            log.info("Semantic dedup: skipping {} {} {} — already analyzed within {}s window",
                     candidate.instrument(), alert.category(), candidate.action(), dedupWindowSeconds);
            return;
        }

        // When auto-analysis is disabled, record as ERROR so the semantic dedup
        // prevents this alert from being reviewed after a restart or reconnect.
        if (!autoAnalysisEnabled) {
            MentorSignalReviewRecord skipped = newReviewRecord(
                alertKey, 1, TRIGGER_INITIAL, STATUS_ERROR,
                alert, candidate, Instant.now(), AUTO_SELECTED_TIMEZONE, "{}"
            );
            skipped.setCompletedAt(Instant.now());
            skipped.setErrorMessage("Auto-analyse desactivee au moment de l'alerte.");
            reviewRepository.save(skipped);
            log.info("Auto-analysis OFF: recorded ERROR placeholder for {} {} {}",
                     candidate.instrument(), alert.category(), candidate.action());
            return;
        }

        JsonNode payload = null;
        String snapshotJson = "{}";
        String snapshotError = null;
        try {
            payload = buildPayload(candidate, alert, focusSnapshot, AUTO_SELECTED_TIMEZONE);
            snapshotJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            snapshotError = "Snapshot capture failed: " + errorMessage(e);
            log.error("buildPayload failed for {}/{}", candidate.instrument(), candidate.timeframe(), e);
        }

        MentorSignalReviewRecord pending = newReviewRecord(
            alertKey,
            1,
            TRIGGER_INITIAL,
            STATUS_ANALYZING,
            alert,
            candidate,
            Instant.now(),
            AUTO_SELECTED_TIMEZONE,
            snapshotJson
        );
        pending.setTriggerPrice(resolveTriggerPrice(focusSnapshot));
        if (snapshotError != null) {
            pending.setStatus(STATUS_ERROR);
            pending.setCompletedAt(Instant.now());
            pending.setErrorMessage(snapshotError);
        }

        MentorSignalReviewRecord saved = reviewRepository.save(pending);
        publish(saved);

        if (snapshotError == null && payload != null) {
            JsonNode reviewPayload = payload;
            CompletableFuture.runAsync(() -> analyzeAndPersist(saved.getId(), reviewPayload))
                .exceptionally(ex -> {
                    log.error("Async analysis failed for review {}", saved.getId(), ex);
                    return null;
                });
        }
    }

    /**
     * Captures a Mentor review for a behaviour alert (EMA proximity, S/R touch, CMF).
     * Behaviour reviews are vigilance-only: always INELIGIBLE for execution arming.
     */
    public void captureBehaviourReview(BehaviourAlertSignal signal,
                                       String timeframe,
                                       IndicatorSnapshot focusSnapshot) {
        Instrument instrument;
        try {
            instrument = Instrument.valueOf(signal.instrument().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return;
        }

        // Convert to a synthetic Alert so we can reuse the canonical alertKey() and payload builder
        AlertCategory category = AlertCategory.valueOf(signal.category().name());
        Alert syntheticAlert = new Alert(
            signal.key(), AlertSeverity.WARNING, signal.message(),
            category, signal.instrument(), signal.timestamp()
        );
        String alertKey = alertKey(syntheticAlert);
        if (reviewRepository.existsByAlertKey(alertKey)) return;
        AlertReviewCandidate candidate = new AlertReviewCandidate(instrument, timeframe, "MONITOR");

        // When auto-analysis is disabled, record as ERROR for dedup protection
        if (!autoAnalysisEnabled) {
            MentorSignalReviewRecord skipped = newReviewRecord(
                alertKey, 1, TRIGGER_INITIAL, STATUS_ERROR,
                syntheticAlert, candidate, Instant.now(), AUTO_SELECTED_TIMEZONE, "{}"
            );
            skipped.setSourceType("BEHAVIOUR");
            skipped.setCompletedAt(Instant.now());
            skipped.setErrorMessage("Auto-analyse desactivee au moment de l'alerte.");
            reviewRepository.save(skipped);
            return;
        }

        JsonNode payload = null;
        String snapshotJson = "{}";
        String snapshotError = null;
        try {
            payload = buildPayload(candidate, syntheticAlert, focusSnapshot, AUTO_SELECTED_TIMEZONE);
            snapshotJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            snapshotError = "Behaviour review snapshot capture failed: " + errorMessage(e);
        }

        MentorSignalReviewRecord pending = newReviewRecord(
            alertKey, 1, TRIGGER_INITIAL, STATUS_DONE,
            syntheticAlert, candidate, Instant.now(), AUTO_SELECTED_TIMEZONE, snapshotJson
        );
        pending.setSourceType("BEHAVIOUR");
        pending.setExecutionEligibilityStatus(ExecutionEligibilityStatus.INELIGIBLE);
        pending.setExecutionEligibilityReason("Behaviour alerts are vigilance-only.");
        pending.setCompletedAt(Instant.now());
        if (snapshotError != null) {
            pending.setStatus(STATUS_ERROR);
            pending.setErrorMessage(snapshotError);
        }

        MentorSignalReviewRecord saved = reviewRepository.save(pending);
        publish(saved);
    }

    public MentorSignalReview reanalyzeAlert(Alert alert) {
        return reanalyzeAlert(alert, AUTO_SELECTED_TIMEZONE, null, null, null);
    }

    public MentorSignalReview reanalyzeAlert(Alert alert,
                                             BigDecimal entryPrice,
                                             BigDecimal stopLoss,
                                             BigDecimal takeProfit) {
        return reanalyzeAlert(alert, AUTO_SELECTED_TIMEZONE, entryPrice, stopLoss, takeProfit);
    }

    public MentorSignalReview reanalyzeAlert(Alert alert,
                                             String selectedTimezone,
                                             BigDecimal entryPrice,
                                             BigDecimal stopLoss,
                                             BigDecimal takeProfit) {
        AlertReviewCandidate candidate = classify(alert);
        if (candidate == null) {
            throw new IllegalArgumentException("unsupported alert for mentor review");
        }

        String alertKey = alertKey(alert);
        List<MentorSignalReviewRecord> thread = reviewRepository.findByAlertKeyOrderByRevisionAsc(alertKey);
        if (thread.isEmpty()) {
            throw new IllegalArgumentException("no saved mentor review exists for this alert");
        }

        // Guard: reject if latest revision is still ANALYZING (prevents duplicate API calls)
        MentorSignalReviewRecord latest = thread.get(thread.size() - 1);
        if (STATUS_ANALYZING.equals(latest.getStatus())) {
            return toDto(latest);
        }

        MentorSignalReviewRecord baseReview = thread.get(0);
        boolean isBehaviourSource = "BEHAVIOUR".equals(baseReview.getSourceType());
        TradePlanValues previousPlan = resolveLatestTradePlan(thread);
        TradePlanValues requestedPlan = new TradePlanValues(
            firstNonNull(entryPrice, previousPlan.entryPrice()),
            firstNonNull(stopLoss, previousPlan.stopLoss()),
            firstNonNull(takeProfit, previousPlan.takeProfit())
        );
        Instant createdAt = Instant.now();
        String normalizedSelectedTimezone = normalizeSelectedTimezone(selectedTimezone);
        MentorSignalReviewRecord pending = newReviewRecord(
            alertKey,
            thread.get(thread.size() - 1).getRevision() + 1,
            TRIGGER_MANUAL_REANALYSIS,
            STATUS_ANALYZING,
            alert,
            candidate,
            createdAt,
            normalizedSelectedTimezone,
            "{}"
        );
        if (isBehaviourSource) {
            pending.setSourceType("BEHAVIOUR");
            pending.setExecutionEligibilityStatus(ExecutionEligibilityStatus.INELIGIBLE);
            pending.setExecutionEligibilityReason("Behaviour alerts are vigilance-only.");
        }
        MentorSignalReviewRecord saved;
        try {
            JsonNode payload = buildPayload(candidate, alert, null, TRIGGER_MANUAL_REANALYSIS, baseReview, requestedPlan, normalizedSelectedTimezone);
            pending.setSnapshotJson(writeJson(payload));
            saved = reviewRepository.save(pending);
            publish(saved);
            JsonNode reviewPayload = payload;
            Long savedReviewId = saved.getId();
            CompletableFuture.runAsync(() -> analyzeAndPersist(savedReviewId, reviewPayload))
                .exceptionally(ex -> {
                    log.error("Async reanalysis failed for review {}", savedReviewId, ex);
                    return null;
                });
        } catch (Exception e) {
            pending.setStatus(STATUS_ERROR);
            pending.setCompletedAt(Instant.now());
            pending.setErrorMessage("Reanalysis snapshot capture failed: " + errorMessage(e));
            log.error("Reanalysis buildPayload failed for {}", pending.getInstrument(), e);
            saved = reviewRepository.save(pending);
            publish(saved);
        }
        return toDto(saved);
    }

    public long deleteByStatuses(List<String> statuses) {
        return reviewRepository.deleteByStatuses(statuses);
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
                                                     String selectedTimezone,
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
        review.setSelectedTimezone(normalizeSelectedTimezone(selectedTimezone));
        review.setSnapshotJson(defaultSnapshotJson(snapshotJson));
        review.setExecutionEligibilityStatus(ExecutionEligibilityStatus.NOT_EVALUATED);
        review.setExecutionEligibilityReason("Mentor analysis pending.");
        return review;
    }

    /**
     * Resolves the trigger price from a snapshot — uses lastPrice (live) first,
     * falls back to latest candle close (ema9 position proxy).
     */
    private BigDecimal resolveTriggerPrice(IndicatorSnapshot snap) {
        if (snap == null) return null;
        if (snap.lastPrice() != null) return snap.lastPrice();
        // fallback: VWAP is close to current price in most sessions
        if (snap.vwap() != null) return snap.vwap();
        return null;
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
                review.setVerdict(truncate(analysis.analysis() == null ? null : analysis.analysis().verdict(), 512));
                if ("BEHAVIOUR".equals(review.getSourceType())) {
                    review.setExecutionEligibilityStatus(ExecutionEligibilityStatus.INELIGIBLE);
                    review.setExecutionEligibilityReason("Behaviour alerts are vigilance-only.");
                    review.setSimulationStatus(TradeSimulationStatus.CANCELLED);
                } else {
                    review.setExecutionEligibilityStatus(resolveExecutionEligibilityStatus(analysis));
                    review.setExecutionEligibilityReason(resolveExecutionEligibilityReason(analysis));
                    initializeSimulationState(review, analysis);
                }
                review.setErrorMessage(null);
                MentorSignalReviewRecord updated = reviewRepository.save(review);
                publish(updated);
                publishTradeValidatedIfEligible(updated, analysis);
            });
        } catch (Exception e) {
            log.error("analyzeAndPersist failed for review {}", reviewId, e);
            completeWithError(reviewId, errorMessage(e));
        }
    }

    private void publishTradeValidatedIfEligible(MentorSignalReviewRecord review, MentorAnalyzeResponse analysis) {
        if (review.getExecutionEligibilityStatus() != ExecutionEligibilityStatus.ELIGIBLE) {
            return;
        }
        if (analysis.analysis() == null || analysis.analysis().proposedTradePlan() == null) {
            return;
        }
        var plan = analysis.analysis().proposedTradePlan();
        try {
            eventPublisher.publishEvent(new TradeValidatedEvent(
                    review.getInstrument(),
                    review.getAction(),
                    review.getTimeframe(),
                    review.getVerdict(),
                    analysis.analysis().technicalQuickAnalysis(),
                    plan.entryPrice(),
                    plan.safeDeepEntry() != null ? plan.safeDeepEntry().entryPrice() : null,
                    plan.stopLoss(),
                    plan.takeProfit(),
                    plan.rewardToRiskRatio(),
                    Instant.now()
            ));
        } catch (Exception e) {
            log.warn("Failed to publish TradeValidatedEvent for review {} — {}", review.getId(), e.getMessage());
        }
    }

    private void completeWithError(Long reviewId, String message) {
        reviewRepository.findById(reviewId).ifPresent(review -> {
            review.setStatus(STATUS_ERROR);
            review.setCompletedAt(Instant.now());
            review.setErrorMessage(message);
            review.setExecutionEligibilityStatus(ExecutionEligibilityStatus.INELIGIBLE);
            review.setExecutionEligibilityReason(message);
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
            review.getSourceType(),
            review.getAlertTimestamp() == null ? null : review.getAlertTimestamp().toString(),
            review.getCreatedAt() == null ? null : review.getCreatedAt().toString(),
            review.getSelectedTimezone(),
            review.getExecutionEligibilityStatus(),
            review.getExecutionEligibilityReason(),
            review.getSimulationStatus(),
            review.getActivationTime() == null ? null : review.getActivationTime().toString(),
            review.getResolutionTime() == null ? null : review.getResolutionTime().toString(),
            review.getMaxDrawdownPoints() == null ? null : review.getMaxDrawdownPoints().doubleValue(),
            review.getTrailingStopResult(),
            review.getTrailingExitPrice() == null ? null : review.getTrailingExitPrice().doubleValue(),
            review.getBestFavorablePrice() == null ? null : review.getBestFavorablePrice().doubleValue(),
            analysis,
            review.getErrorMessage(),
            review.getTriggerPrice() == null ? null : review.getTriggerPrice().doubleValue()
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
        if (review.getExecutionEligibilityStatus() != ExecutionEligibilityStatus.ELIGIBLE) {
            return false;
        }
        return analysis.analysis().proposedTradePlan().entryPrice() != null
            && analysis.analysis().proposedTradePlan().stopLoss() != null
            && analysis.analysis().proposedTradePlan().takeProfit() != null;
    }

    private ExecutionEligibilityStatus resolveExecutionEligibilityStatus(MentorAnalyzeResponse analysis) {
        if (analysis == null || analysis.analysis() == null || analysis.analysis().executionEligibilityStatus() == null) {
            return ExecutionEligibilityStatus.INELIGIBLE;
        }
        return analysis.analysis().executionEligibilityStatus();
    }

    private String resolveExecutionEligibilityReason(MentorAnalyzeResponse analysis) {
        if (analysis == null || analysis.analysis() == null) {
            return "Mentor analysis unavailable.";
        }
        String reason = analysis.analysis().executionEligibilityReason();
        if (reason == null || reason.isBlank()) {
            return analysis.analysis().executionEligibilityStatus() == ExecutionEligibilityStatus.ELIGIBLE
                ? "Mentor explicitly marked the review as execution-eligible."
                : "Mentor did not mark the review as execution-eligible.";
        }
        return truncate(reason, 1024);
    }

    private static String truncate(String value, int maxLen) {
        return value != null && value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    private JsonNode buildPayload(AlertReviewCandidate candidate,
                                  Alert alert,
                                  IndicatorSnapshot precomputedFocusSnapshot,
                                  String triggerType,
                                  MentorSignalReviewRecord originalReview,
                                  TradePlanValues requestedPlan,
                                  String selectedTimezone) {
        IndicatorSnapshot focusSnapshot = precomputedFocusSnapshot != null
            ? precomputedFocusSnapshot
            : indicatorService.computeSnapshot(candidate.instrument(), candidate.timeframe());
        IndicatorSnapshot h1Snapshot = "1h".equals(candidate.timeframe())
            ? focusSnapshot
            : indicatorService.computeSnapshot(candidate.instrument(), "1h");
        IndicatorSeriesSnapshot indicatorSeries = indicatorService.computeSeries(candidate.instrument(), candidate.timeframe(), 500);
        List<Candle> candles = loadCandles(candidate.instrument(), candidate.timeframe(), 120);
        MentorIntermarketSnapshot intermarket = mentorIntermarketService.current(candidate.instrument());
        MacroCorrelationSnapshot macroCorrelation = mentorIntermarketService.currentForAssetClass(
            candidate.instrument(), candidate.instrument().assetClass());
        boolean manualReanalysis = TRIGGER_MANUAL_REANALYSIS.equals(triggerType);
        MarketDataService.StoredPrice livePrice = livePrice(candidate.instrument());
        Instant contextTimestamp = liveTimestamp(livePrice, candles);
        BigDecimal currentPrice = currentPrice(livePrice, candles);
        BigDecimal effectiveEntry = firstNonNull(
            requestedPlan == null ? null : requestedPlan.entryPrice(),
            currentPrice
        );
        BigDecimal effectiveStopLoss = requestedPlan == null ? null : requestedPlan.stopLoss();
        BigDecimal effectiveTakeProfit = requestedPlan == null ? null : requestedPlan.takeProfit();
        BigDecimal atr = computeAtr(candles, 14, candidate.instrument());
        BigDecimal referencePrice = firstNonNull(currentPrice, focusSnapshot.vwap(), focusSnapshot.ema50(), BigDecimal.ZERO);
        BigDecimal stopLossSizePoints = computeDistancePoints(effectiveEntry, effectiveStopLoss, candidate.instrument());
        BigDecimal rewardToRiskRatio = computeRewardToRiskRatio(effectiveEntry, effectiveStopLoss, effectiveTakeProfit, candidate.action(), candidate.instrument());
        Map<String, Object> nearestSupport = findNearestOrderBlock(focusSnapshot, referencePrice, "BULLISH");
        Map<String, Object> nearestResistance = findNearestOrderBlock(focusSnapshot, referencePrice, "BEARISH");
        Map<String, Object> originalAlertContext = manualReanalysis
            ? buildOriginalAlertContext(alert, originalReview)
            : null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("metadata", linkedMap(
            "timestamp", contextTimestamp.toString(),
            "asset", assetAlias(candidate.instrument()),
            "asset_class", candidate.instrument().assetClass() != null ? candidate.instrument().assetClass().name() : null,
            "current_price", roundNullable(currentPrice, candidate.instrument()),
            "timeframe_focus", toMentorTimeframe(candidate.timeframe()),
            "market_session", inferMarketSession(contextTimestamp),
            "dashboard_connection_status", "LIVE",
            "selected_timezone", normalizeSelectedTimezone(selectedTimezone)
        ));
        payload.put("trade_intention", linkedMap(
            "action", candidate.action(),
            "analysis_mode", "SETUP_REVIEW",
            "review_type", triggerType,
            "entry_price", roundNullable(effectiveEntry, candidate.instrument()),
            "stop_loss", roundNullable(effectiveStopLoss, candidate.instrument()),
            "take_profit", roundNullable(effectiveTakeProfit, candidate.instrument()),
            "signal_confirmed_on_candle_close", true,
            "time_to_candle_close_seconds", timeToCandleCloseSeconds(candidate.timeframe(), contextTimestamp),
            "is_market_order", false,
            "mentor_should_propose_plan", true
        ));
        if (originalAlertContext != null) {
            payload.put("original_alert_context", originalAlertContext);
        }
        payload.put("market_structure_smc", linkedMap(
            "trend_H1", h1Snapshot.marketStructureTrend(),
            "trend_focus", focusSnapshot.marketStructureTrend(),
            "focus_timeframe", toMentorTimeframe(candidate.timeframe()),
            "pd_array_zone_session", focusSnapshot.sessionPdZone(),
            "pd_array_zone_structural", focusSnapshot.currentZone(),
            "last_event", focusSnapshot.lastBreakType(),
            "last_event_price", focusSnapshot.recentBreaks().isEmpty() ? null : focusSnapshot.recentBreaks().get(0).level(),
            "nearest_support_ob", nearestSupport,
            "nearest_resistance_ob", nearestResistance,
            "liquidity_pools", buildLiquidityPools(focusSnapshot),
            "nearest_fvg", buildNearestFvg(focusSnapshot, currentPrice),
            "key_psychological_level_proximity", nearestPsychologicalLevel(currentPrice, candidate.instrument())
        ));
        payload.put("dynamic_levels_and_mean_reversion", linkedMap(
            "vwap_value", focusSnapshot.vwap(),
            "distance_to_vwap_points", distance(currentPrice, focusSnapshot.vwap(), candidate.instrument()),
            "ema_9_value", focusSnapshot.ema9(),
            "ema_50_value", focusSnapshot.ema50(),
            "ema_200_value", focusSnapshot.ema200(),
            "distance_to_ema_50_points", distance(currentPrice, focusSnapshot.ema50(), candidate.instrument()),
            "distance_to_ema_200_points", distance(currentPrice, focusSnapshot.ema200(), candidate.instrument()),
            "bollinger_state", focusSnapshot.bbTrendExpanding() ? "EXPANDING" : (focusSnapshot.bbWidth() != null && focusSnapshot.bbWidth().doubleValue() < 0.02 ? "SQUEEZE" : "CONTRACTING"),
            "vwap_upper_band", focusSnapshot.vwapUpperBand(),
            "vwap_lower_band", focusSnapshot.vwapLowerBand(),
            "distance_to_vwap_upper", distance(currentPrice, focusSnapshot.vwapUpperBand(), candidate.instrument()),
            "distance_to_vwap_lower", distance(currentPrice, focusSnapshot.vwapLowerBand(), candidate.instrument())
        ));
        payload.put("momentum_oscillators", linkedMap(
            "wavetrend_signal", focusSnapshot.wtCrossover(),
            "wavetrend_is_overbought", focusSnapshot.wtWt1() != null && focusSnapshot.wtWt1().doubleValue() > 53,
            "wavetrend_is_oversold", focusSnapshot.wtWt1() != null && focusSnapshot.wtWt1().doubleValue() < -53,
            "rsi_value", focusSnapshot.rsi(),
            "rsi_signal", focusSnapshot.rsiSignal(),
            "stochastic_k", focusSnapshot.stochK(),
            "stochastic_d", focusSnapshot.stochD(),
            "stochastic_signal", focusSnapshot.stochSignal(),
            "stochastic_crossover", focusSnapshot.stochCrossover(),
            "chaikin_money_flow_cmf", focusSnapshot.cmf(),
            "money_flow_state", inferMoneyFlowState(focusSnapshot),
            "money_flow_trend", inferMoneyFlowTrend(focusSnapshot)
        ));
        Map<String, Object> orderFlow = new LinkedHashMap<>(buildOrderFlowSection(candidate.instrument(), focusSnapshot));
        VolumeProfileCalculator.VolumeProfileResult vpResult = volumeProfileCalculator.compute(
            candles, candidate.instrument().getTickSize(), 10);
        if (vpResult != null) {
            orderFlow.put("volume_profile", linkedMap(
                "daily_poc_price", vpResult.pocPrice(),
                "value_area_high", vpResult.valueAreaHigh(),
                "value_area_low", vpResult.valueAreaLow(),
                "is_price_in_value_area", currentPrice != null
                    && currentPrice.compareTo(vpResult.valueAreaLow()) >= 0
                    && currentPrice.compareTo(vpResult.valueAreaHigh()) <= 0
            ));
        }
        payload.put("order_flow_and_volume", orderFlow);
        payload.put("macro_correlations_dynamic", linkedMap(
            "dxy_pct_change", macroCorrelation.dxyPctChange(),
            "dxy_trend", macroCorrelation.dxyTrend(),
            "dxy_component_breakdown", macroCorrelation.dxyComponentBreakdown(),
            "sector_leader_symbol", macroCorrelation.sectorLeaderSymbol(),
            "sector_leader_pct_change", macroCorrelation.sectorLeaderPctChange(),
            "sector_leader_trend", macroCorrelation.sectorLeaderTrend(),
            "vix_pct_change", macroCorrelation.vixPctChange(),
            "us10y_yield_pct_change", macroCorrelation.us10yYieldPctChange(),
            "correlation_alignment", macroCorrelation.correlationAlignment(),
            "data_availability", macroCorrelation.dataAvailability()
        ));
        // Market regime from EMA alignment + BB expansion
        String focusRegime = marketRegimeDetector.detect(
            focusSnapshot.ema9(), focusSnapshot.ema50(), focusSnapshot.ema200(),
            Boolean.TRUE.equals(focusSnapshot.bbTrendExpanding()));
        String h1Regime = marketRegimeDetector.detect(
            h1Snapshot.ema9(), h1Snapshot.ema50(), h1Snapshot.ema200(),
            Boolean.TRUE.equals(h1Snapshot.bbTrendExpanding()));
        boolean htfAligned = marketRegimeDetector.htfAligned(focusRegime, h1Regime);
        payload.put("market_regime_context", linkedMap(
            "regime", focusRegime,
            "htf_regime", h1Regime,
            "htf_aligned", htfAligned
        ));

        Integer atrPctRank = computeAtrPercentileRank(candles, 14);
        String volatilityRegime = atrPctRank == null ? null
            : atrPctRank > 80 ? "EXTREME"
            : atrPctRank < 20 ? "LOW"
            : "NORMAL";
        payload.put("risk_management_gatekeeper", linkedMap(
            "current_atr_focus", atr,
            "atr_percentile_rank", atrPctRank,
            "volatility_regime", volatilityRegime,
            "stop_loss_size_points", roundNullable(stopLossSizePoints, candidate.instrument()),
            "reward_to_risk_ratio", rewardToRiskRatio,
            "is_stop_protected_by_structure", isStopProtectedByStructure(effectiveStopLoss, candidate.action(), nearestSupport, nearestResistance),
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

    private JsonNode buildPayload(AlertReviewCandidate candidate,
                                  Alert alert,
                                  IndicatorSnapshot precomputedFocusSnapshot,
                                  String selectedTimezone) {
        return buildPayload(candidate, alert, precomputedFocusSnapshot, TRIGGER_INITIAL, null, null, selectedTimezone);
    }

    private List<Candle> loadCandles(Instrument instrument, String timeframe, int limit) {
        String contractMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        List<Candle> candles;
        if (contractMonth != null) {
            candles = candleRepositoryPort.findRecentCandlesByContractMonth(instrument, timeframe, contractMonth, limit);
            if (candles.isEmpty()) {
                candles = candleRepositoryPort.findRecentCandles(instrument, timeframe, limit);
            }
        } else {
            candles = candleRepositoryPort.findRecentCandles(instrument, timeframe, limit);
        }
        List<Candle> ordered = new ArrayList<>(candles);
        ordered.sort(Comparator.comparing(Candle::getTimestamp));
        return ordered;
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

        String categoryName = alert.category().name();
        if (!isEligibleCategory(categoryName, alert.message())) {
            return null;
        }

        // Behaviour categories (EMA_PROXIMITY, SUPPORT_RESISTANCE, CHAIKIN_BEHAVIOUR) are
        // vigilance-only and don't carry directional bias — use MONITOR instead of LONG/SHORT.
        boolean isBehaviour = "EMA_PROXIMITY".equals(categoryName)
            || "SUPPORT_RESISTANCE".equals(categoryName)
            || "CHAIKIN_BEHAVIOUR".equals(categoryName);

        String action = isBehaviour ? "MONITOR" : inferAction(alert.message());
        if (action == null) {
            return null;
        }

        return new AlertReviewCandidate(instrument, parseTimeframe(alert.message()), action);
    }

    private String alertKey(Alert alert) {
        return alert.timestamp() + ":" + (alert.instrument() == null || alert.instrument().isBlank() ? "GLOBAL" : alert.instrument())
            + ":" + alert.category().name() + ":" + alert.message();
    }

    /** Timeframe-aware semantic dedup window: base duration × 5. */
    /**
     * Semantic dedup window = 2× the timeframe duration.
     * Previous ×5 multiplier was too aggressive — blocked re-evaluation when
     * market conditions changed (e.g. LONG rejected at 02:00, price moved 20pts
     * by 02:40 but dedup still active for 50 min on 10m).
     */
    static long semanticDedupWindowSeconds(String timeframe) {
        long base = switch (timeframe) {
            case "1m"  -> 60;
            case "5m"  -> 300;
            case "10m" -> 600;
            case "15m" -> 900;
            case "30m" -> 1800;
            case "1h"  -> 3600;
            case "4h"  -> 14400;
            case "1d"  -> 86400;
            default    -> 3600;
        };
        return base * 2;
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
            case "ORDER_BLOCK", "ORDER_BLOCK_VWAP" -> message.contains("VWAP inside")
                || message.contains("mitigated")
                || message.contains("invalidated");
            case "EMA_PROXIMITY", "SUPPORT_RESISTANCE", "CHAIKIN_BEHAVIOUR" -> true;
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

    /**
     * Compute ATR percentile rank (0-100) over rolling windows from loaded candles.
     * Uses AtrCalculator (Wilder's smoothing) on sliding windows of the given period.
     */
    private Integer computeAtrPercentileRank(List<Candle> candles, int period) {
        int minWindows = 20;
        if (candles.size() < period * 2 + minWindows) {
            return null;
        }
        List<BigDecimal> atrHistory = new ArrayList<>();
        for (int end = period * 2; end <= candles.size(); end++) {
            BigDecimal atrVal = AtrCalculator.compute(candles.subList(0, end), period);
            if (atrVal != null) {
                atrHistory.add(atrVal);
            }
        }
        if (atrHistory.size() < minWindows) {
            return null;
        }
        BigDecimal currentAtr = atrHistory.get(atrHistory.size() - 1);
        long below = atrHistory.stream().filter(a -> a.compareTo(currentAtr) < 0).count();
        return (int) Math.round(100.0 * below / atrHistory.size());
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
            ? pickNearestLevel(currentPrice, Stream.of(snapshot.weakLow(), snapshot.strongLow()).filter(Objects::nonNull).toList(), true)
            : pickNearestLevel(currentPrice, Stream.of(snapshot.weakHigh(), snapshot.strongHigh()).filter(Objects::nonNull).toList(), false);
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
        return currentPrice.subtract(snapshot.vwap()).abs().compareTo(atr.multiply(BigDecimal.valueOf(2.5))) > 0;
    }

    private BigDecimal distance(BigDecimal left, BigDecimal right, Instrument instrument) {
        if (left == null || right == null) {
            return null;
        }
        return round(left.subtract(right).abs(), instrument);
    }

    private BigDecimal computeDistancePoints(BigDecimal entryPrice, BigDecimal stopLoss, Instrument instrument) {
        if (entryPrice == null || stopLoss == null) {
            return null;
        }
        return round(entryPrice.subtract(stopLoss).abs(), instrument);
    }

    private BigDecimal computeRewardToRiskRatio(BigDecimal entryPrice,
                                                BigDecimal stopLoss,
                                                BigDecimal takeProfit,
                                                String action,
                                                Instrument instrument) {
        if (entryPrice == null || stopLoss == null || takeProfit == null || action == null) {
            return null;
        }

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        BigDecimal reward = "SHORT".equalsIgnoreCase(action)
            ? entryPrice.subtract(takeProfit)
            : takeProfit.subtract(entryPrice);
        if (reward.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        return reward.divide(risk, 2, RoundingMode.HALF_UP);
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

    private Map<String, Object> buildLiquidityPools(IndicatorSnapshot snapshot) {
        Map<String, Object> pools = new LinkedHashMap<>();
        boolean hasEqh = snapshot.equalHighs() != null && !snapshot.equalHighs().isEmpty();
        boolean hasEql = snapshot.equalLows() != null && !snapshot.equalLows().isEmpty();
        pools.put("eqh_present", hasEqh);
        pools.put("eqh_level", hasEqh ? snapshot.equalHighs().get(0).price() : null);
        pools.put("eql_present", hasEql);
        pools.put("eql_level", hasEql ? snapshot.equalLows().get(0).price() : null);
        return pools;
    }

    private Map<String, Object> buildNearestFvg(IndicatorSnapshot snapshot, BigDecimal currentPrice) {
        if (snapshot.activeFairValueGaps() == null || snapshot.activeFairValueGaps().isEmpty() || currentPrice == null) {
            return null;
        }
        // Find the FVG closest to current price
        IndicatorSnapshot.FairValueGapView nearest = null;
        BigDecimal minDistance = null;
        for (var fvg : snapshot.activeFairValueGaps()) {
            BigDecimal mid = fvg.top().add(fvg.bottom()).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
            BigDecimal dist = currentPrice.subtract(mid).abs();
            if (minDistance == null || dist.compareTo(minDistance) < 0) {
                minDistance = dist;
                nearest = fvg;
            }
        }
        if (nearest == null) return null;
        return linkedMap(
            "type", nearest.bias(),
            "price_top", nearest.top(),
            "price_bottom", nearest.bottom(),
            "is_mitigated", false
        );
    }

    private Map<String, Object> buildOrderFlowSection(Instrument instrument, IndicatorSnapshot focusSnapshot) {
        // Try real tick data first
        TickDataPort tickDataPort = tickDataPortProvider.getIfAvailable();
        if (tickDataPort != null && tickDataPort.isRealTickDataAvailable(instrument)) {
            var tickAgg = tickDataPort.currentAggregation(instrument);
            if (tickAgg.isPresent()) {
                TickAggregation agg = tickAgg.get();
                return linkedMap(
                    "delta_flow_current", agg.delta(),
                    "cumulative_delta_trend", agg.deltaTrend(),
                    "buy_ratio_pct", agg.buyRatioPct(),
                    "delta_divergence_detected", agg.divergenceDetected(),
                    "delta_divergence_type", agg.divergenceType(),
                    "source", TickAggregation.SOURCE_REAL_TICKS
                );
            }
        }
        // Fallback to CLV estimation from candle-based indicators
        return linkedMap(
            "delta_flow_current", focusSnapshot.deltaFlow(),
            "cumulative_delta_trend", focusSnapshot.deltaFlowBias(),
            "buy_ratio_pct", focusSnapshot.buyRatio(),
            "delta_divergence_detected", false,
            "source", TickAggregation.SOURCE_CLV_ESTIMATED
        );
    }

    private boolean isStopProtectedByStructure(BigDecimal stopLoss, String action,
                                                Map<String, Object> nearestSupport,
                                                Map<String, Object> nearestResistance) {
        if (stopLoss == null) return false;
        // For LONG: SL should be below the nearest support OB bottom
        if ("LONG".equals(action) && nearestSupport != null) {
            Object obBottom = nearestSupport.get("price_bottom");
            if (obBottom instanceof BigDecimal bd) {
                return stopLoss.compareTo(bd) <= 0;
            }
        }
        // For SHORT: SL should be above the nearest resistance OB top
        if ("SHORT".equals(action) && nearestResistance != null) {
            Object obTop = nearestResistance.get("price_top");
            if (obTop instanceof BigDecimal bd) {
                return stopLoss.compareTo(bd) >= 0;
            }
        }
        return false;
    }

    private String assetAlias(Instrument instrument) {
        return switch (instrument) {
            case MCL -> "CL1!";
            case MGC -> "MGC1!";
            case E6 -> "6E1!";
            case MNQ -> "MNQ1!";
            case DXY -> "DXY";
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

    private TradePlanValues resolveLatestTradePlan(List<MentorSignalReviewRecord> thread) {
        for (int index = thread.size() - 1; index >= 0; index -= 1) {
            TradePlanValues candidate = extractTradePlan(thread.get(index));
            if (candidate != null && (candidate.entryPrice() != null || candidate.stopLoss() != null || candidate.takeProfit() != null)) {
                return candidate;
            }
        }
        return new TradePlanValues(null, null, null);
    }

    private TradePlanValues extractTradePlan(MentorSignalReviewRecord review) {
        if (review == null || review.getAnalysisJson() == null || review.getAnalysisJson().isBlank()) {
            return null;
        }
        try {
            MentorAnalyzeResponse analysis = objectMapper.readValue(review.getAnalysisJson(), MentorAnalyzeResponse.class);
            if (analysis == null || analysis.analysis() == null || analysis.analysis().proposedTradePlan() == null) {
                return null;
            }
            return new TradePlanValues(
                decimalValue(analysis.analysis().proposedTradePlan().entryPrice()),
                decimalValue(analysis.analysis().proposedTradePlan().stopLoss()),
                decimalValue(analysis.analysis().proposedTradePlan().takeProfit())
            );
        } catch (Exception ignored) {
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

    private String normalizeSelectedTimezone(String selectedTimezone) {
        if (selectedTimezone == null || selectedTimezone.isBlank()) {
            return AUTO_SELECTED_TIMEZONE;
        }
        try {
            return ZoneId.of(selectedTimezone).getId();
        } catch (Exception ignored) {
            return AUTO_SELECTED_TIMEZONE;
        }
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
            ? "Mentor analysis failed."
            : e.getMessage();
    }

    private BigDecimal decimalValue(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private Map<String, Object> linkedMap(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException(
                "linkedMap requires even number of arguments (key-value pairs), got " + values.length);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private record AlertReviewCandidate(Instrument instrument, String timeframe, String action) {
    }

    private record TradePlanValues(BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
    }
}

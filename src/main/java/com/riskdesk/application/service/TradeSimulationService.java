package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.TrailingStopStatsResponse;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.model.TrailingStopResult;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import com.riskdesk.infrastructure.config.TrailingStopProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TradeSimulationService {

    private static final Logger log = LoggerFactory.getLogger(TradeSimulationService.class);
    private static final String SIMULATION_TIMEFRAME = "5m";
    private static final Duration PENDING_ENTRY_TIMEOUT = Duration.ofHours(1);
    private static final int ATR_PERIOD = 14;
    private static final BigDecimal BE_TOLERANCE = new BigDecimal("0.0001");

    private final MentorSignalReviewRepositoryPort reviewRepository;
    private final MentorAuditRepositoryPort auditRepository;
    private final CandleRepositoryPort candleRepositoryPort;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MentorSignalReviewService> reviewServiceProvider;
    private final ObjectProvider<SimpMessagingTemplate> messagingProvider;
    private final TrailingStopProperties trailingStopProperties;
    private final TradeSimulationRepositoryPort simulationRepository;

    public TradeSimulationService(MentorSignalReviewRepositoryPort reviewRepository,
                                  MentorAuditRepositoryPort auditRepository,
                                  CandleRepositoryPort candleRepositoryPort,
                                  ObjectMapper objectMapper,
                                  ObjectProvider<MentorSignalReviewService> reviewServiceProvider,
                                  ObjectProvider<SimpMessagingTemplate> messagingProvider,
                                  TrailingStopProperties trailingStopProperties,
                                  TradeSimulationRepositoryPort simulationRepository) {
        this.reviewRepository = reviewRepository;
        this.auditRepository = auditRepository;
        this.candleRepositoryPort = candleRepositoryPort;
        this.objectMapper = objectMapper;
        this.reviewServiceProvider = reviewServiceProvider;
        this.messagingProvider = messagingProvider;
        this.trailingStopProperties = trailingStopProperties;
        this.simulationRepository = simulationRepository;
    }

    public SimulationResult evaluateTradeOutcome(MentorSignalReviewRecord review, List<Candle> subsequentCandles) {
        TradePlan plan = extractPlan(review);
        if (plan == null) {
            return new SimulationResult(
                TradeSimulationStatus.CANCELLED,
                review.getActivationTime(),
                review.getResolutionTime(),
                zero(review.getMaxDrawdownPoints())
            );
        }

        List<Candle> orderedCandles = subsequentCandles.stream()
            .sorted(Comparator.comparing(Candle::getTimestamp))
            .toList();

        TradeSimulationStatus currentStatus = review.getSimulationStatus() == null
            ? TradeSimulationStatus.PENDING_ENTRY
            : review.getSimulationStatus();
        Instant activationTime = review.getActivationTime();
        BigDecimal maxDrawdown = zero(review.getMaxDrawdownPoints());
        boolean active = currentStatus == TradeSimulationStatus.ACTIVE;

        // --- Fixed SL/TP evaluation (unchanged) ---
        SimulationResult fixedResult = null;
        int activationIndex = -1;

        for (int i = 0; i < orderedCandles.size(); i++) {
            Candle candle = orderedCandles.get(i);
            if (!active) {
                if (isMissed(plan, candle)) {
                    fixedResult = new SimulationResult(TradeSimulationStatus.MISSED, null, candle.getTimestamp(), maxDrawdown);
                    break;
                }

                if (touchesEntry(plan, candle)) {
                    active = true;
                    activationTime = candle.getTimestamp();
                    activationIndex = i;
                    maxDrawdown = maxDrawdown.max(adverseMove(plan, candle));

                    if (touchesStopAndTarget(plan, candle)) {
                        fixedResult = new SimulationResult(TradeSimulationStatus.LOSS, activationTime, candle.getTimestamp(), maxDrawdown);
                        break;
                    }
                    if (touchesStop(plan, candle)) {
                        fixedResult = new SimulationResult(TradeSimulationStatus.LOSS, activationTime, candle.getTimestamp(), maxDrawdown);
                        break;
                    }
                    if (touchesTarget(plan, candle)) {
                        fixedResult = new SimulationResult(TradeSimulationStatus.WIN, activationTime, candle.getTimestamp(), maxDrawdown);
                        break;
                    }
                }
                continue;
            }

            maxDrawdown = maxDrawdown.max(adverseMove(plan, candle));
            if (touchesStopAndTarget(plan, candle)) {
                fixedResult = new SimulationResult(TradeSimulationStatus.LOSS, activationTime, candle.getTimestamp(), maxDrawdown);
                break;
            }
            if (touchesStop(plan, candle)) {
                fixedResult = new SimulationResult(TradeSimulationStatus.LOSS, activationTime, candle.getTimestamp(), maxDrawdown);
                break;
            }
            if (touchesTarget(plan, candle)) {
                fixedResult = new SimulationResult(TradeSimulationStatus.WIN, activationTime, candle.getTimestamp(), maxDrawdown);
                break;
            }
        }

        if (fixedResult == null) {
            fixedResult = new SimulationResult(
                active ? TradeSimulationStatus.ACTIVE : TradeSimulationStatus.PENDING_ENTRY,
                activationTime, null, maxDrawdown
            );
        }

        // --- Trailing stop second-pass evaluation ---
        if (!trailingStopProperties.isEnabled() || !active) {
            return withTrailingFromReview(fixedResult, review);
        }

        TrailingResult trailing = evaluateTrailingStop(
                plan, orderedCandles, activationIndex,
                zero(review.getBestFavorablePrice()));
        return new SimulationResult(
            fixedResult.status(), fixedResult.activationTime(), fixedResult.resolutionTime(),
            fixedResult.maxDrawdownPoints(),
            trailing.result(), trailing.exitPrice(), trailing.bestFavorablePrice()
        );
    }

    /**
     * Second-pass trailing stop evaluation. Walks candles from activation forward,
     * tracking best favorable price (MFE) and computing dynamic trailing SL.
     */
    private TrailingResult evaluateTrailingStop(TradePlan plan, List<Candle> orderedCandles,
                                               int activationIndex, BigDecimal existingBestPrice) {
        if (activationIndex < 0) {
            return new TrailingResult(null, null, existingBestPrice);
        }
        BigDecimal atr = computeAtrAtActivation(orderedCandles, activationIndex);
        if (atr == null || atr.compareTo(BigDecimal.ZERO) == 0) {
            return new TrailingResult(null, null, existingBestPrice);
        }

        BigDecimal multiplier = BigDecimal.valueOf(trailingStopProperties.getMultiplier());
        BigDecimal activationThreshold = BigDecimal.valueOf(trailingStopProperties.getActivationThreshold());
        BigDecimal riskSize = plan.entryPrice().subtract(plan.stopLoss()).abs();
        BigDecimal activationDistance = riskSize.multiply(activationThreshold);

        BigDecimal bestPrice = existingBestPrice.compareTo(BigDecimal.ZERO) > 0
                ? existingBestPrice : plan.entryPrice();
        boolean trailingActivated = false;

        for (int i = Math.max(0, activationIndex); i < orderedCandles.size(); i++) {
            Candle candle = orderedCandles.get(i);

            // Update MFE
            if (plan.isLong()) {
                bestPrice = bestPrice.max(candle.getHigh());
            } else {
                bestPrice = bestPrice.min(candle.getLow());
            }

            // Check activation gate
            BigDecimal favorableMove = plan.isLong()
                    ? bestPrice.subtract(plan.entryPrice())
                    : plan.entryPrice().subtract(bestPrice);

            if (!trailingActivated && favorableMove.compareTo(activationDistance) >= 0) {
                trailingActivated = true;
            }

            if (!trailingActivated) {
                continue;
            }

            // Compute trailing SL
            BigDecimal trailingSL = plan.isLong()
                    ? bestPrice.subtract(atr.multiply(multiplier))
                    : bestPrice.add(atr.multiply(multiplier));

            // Only use trailing if it's more favorable than original SL
            BigDecimal effectiveSL;
            if (plan.isLong()) {
                effectiveSL = trailingSL.compareTo(plan.stopLoss()) > 0 ? trailingSL : plan.stopLoss();
            } else {
                effectiveSL = trailingSL.compareTo(plan.stopLoss()) < 0 ? trailingSL : plan.stopLoss();
            }

            // Check if candle hits effective trailing SL
            boolean trailingHit = plan.isLong()
                    ? candle.getLow().compareTo(effectiveSL) <= 0
                    : candle.getHigh().compareTo(effectiveSL) >= 0;

            if (trailingHit) {
                BigDecimal exitPrice = effectiveSL.setScale(6, RoundingMode.HALF_UP);
                TrailingStopResult result = classifyTrailingExit(plan, exitPrice);
                return new TrailingResult(result, exitPrice, bestPrice.setScale(6, RoundingMode.HALF_UP));
            }
        }

        // Trade still active or not resolved by trailing — return best price tracked so far
        return new TrailingResult(null, null, bestPrice.setScale(6, RoundingMode.HALF_UP));
    }

    private BigDecimal computeAtrAtActivation(List<Candle> orderedCandles, int activationIndex) {
        // Use candles before activation to compute ATR
        int endIdx = Math.max(0, activationIndex);
        int startIdx = Math.max(0, endIdx - ATR_PERIOD - 1);
        if (endIdx - startIdx < ATR_PERIOD) {
            // Not enough candles before activation — use all available
            startIdx = 0;
        }
        List<Candle> atrCandles = orderedCandles.subList(startIdx, Math.min(endIdx + 1, orderedCandles.size()));
        return AtrCalculator.compute(atrCandles, ATR_PERIOD);
    }

    private TrailingStopResult classifyTrailingExit(TradePlan plan, BigDecimal exitPrice) {
        BigDecimal diff = exitPrice.subtract(plan.entryPrice());
        if (plan.isLong()) {
            if (diff.abs().compareTo(BE_TOLERANCE) <= 0) {
                return TrailingStopResult.TRAILING_BE;
            }
            return diff.compareTo(BigDecimal.ZERO) > 0
                    ? TrailingStopResult.TRAILING_WIN
                    : TrailingStopResult.TRAILING_LOSS;
        } else {
            BigDecimal inverseDiff = plan.entryPrice().subtract(exitPrice);
            if (inverseDiff.abs().compareTo(BE_TOLERANCE) <= 0) {
                return TrailingStopResult.TRAILING_BE;
            }
            return inverseDiff.compareTo(BigDecimal.ZERO) > 0
                    ? TrailingStopResult.TRAILING_WIN
                    : TrailingStopResult.TRAILING_LOSS;
        }
    }

    private SimulationResult withTrailingFromReview(SimulationResult fixedResult, MentorSignalReviewRecord review) {
        return new SimulationResult(
            fixedResult.status(), fixedResult.activationTime(), fixedResult.resolutionTime(),
            fixedResult.maxDrawdownPoints(),
            review.getTrailingStopResult(),
            review.getTrailingExitPrice(),
            review.getBestFavorablePrice()
        );
    }

    private record TrailingResult(TrailingStopResult result, BigDecimal exitPrice, BigDecimal bestFavorablePrice) {
    }

    @Scheduled(fixedDelayString = "${riskdesk.trade-simulation.poll-ms:60000}")
    public void refreshPendingSimulations() {
        List<MentorSignalReviewRecord> candidates = reviewRepository.findBySimulationStatuses(List.of(
            TradeSimulationStatus.PENDING_ENTRY,
            TradeSimulationStatus.ACTIVE
        ));

        // --- Phase 1 dual-write back-fill: ensure every open review has a matching
        // row in the new trade_simulations table. Reviews created by
        // MentorSignalReviewService.initializeSimulationState() arrive here without
        // a new-side row on their first poll cycle — back-fill is idempotent via
        // the (review_id, review_type) unique constraint. ---
        for (MentorSignalReviewRecord review : candidates) {
            dualWriteSignalSimulation(review);
        }

        // --- Phase 1: Cancel expired PENDING_ENTRY (>1h without activation) ---
        Instant now = Instant.now();
        List<MentorSignalReviewRecord> surviving = cancelExpiredPendingEntries(candidates, now);

        // --- Phase 2: Reverse conflicting trades (same instrument, opposite direction) ---
        surviving = reverseConflictingTrades(surviving, now);

        // --- Phase 3: Normal candle-based evaluation ---
        for (MentorSignalReviewRecord review : surviving) {
            try {
                Instrument instrument = Instrument.valueOf(review.getInstrument());
                Instant from = review.getActivationTime() != null ? review.getActivationTime() : review.getCreatedAt();
                List<Candle> candles = candleRepositoryPort.findCandles(instrument, SIMULATION_TIMEFRAME, from);
                if (candles.isEmpty()) {
                    continue;
                }

                SimulationResult result = evaluateTradeOutcome(review, candles);
                if (hasSimulationChanged(review, result)) {
                    review.setSimulationStatus(result.status());
                    review.setActivationTime(result.activationTime());
                    review.setResolutionTime(result.resolutionTime());
                    review.setMaxDrawdownPoints(result.maxDrawdownPoints());
                    review.setTrailingStopResult(result.trailingStopResult());
                    review.setTrailingExitPrice(result.trailingExitPrice());
                    review.setBestFavorablePrice(result.bestFavorablePrice());
                    reviewRepository.save(review);
                    dualWriteSignalSimulation(review);
                    pushSimulationUpdate(review);
                }
            } catch (Exception e) {
                log.debug("Trade simulation skipped for review {}: {}", review.getId(), e.getMessage());
            }
        }
    }

    /**
     * Cancel PENDING_ENTRY reviews older than 1 hour — the entry was never triggered.
     */
    private List<MentorSignalReviewRecord> cancelExpiredPendingEntries(
            List<MentorSignalReviewRecord> candidates, Instant now) {
        List<MentorSignalReviewRecord> surviving = new ArrayList<>();
        for (MentorSignalReviewRecord review : candidates) {
            if (review.getSimulationStatus() == TradeSimulationStatus.PENDING_ENTRY
                    && review.getCreatedAt() != null
                    && Duration.between(review.getCreatedAt(), now).compareTo(PENDING_ENTRY_TIMEOUT) > 0) {
                review.setSimulationStatus(TradeSimulationStatus.CANCELLED);
                review.setResolutionTime(now);
                reviewRepository.save(review);
                dualWriteSignalSimulation(review);
                pushSimulationUpdate(review);
                log.info("Simulation {} CANCELLED — PENDING_ENTRY expired after 1h (instrument={}, action={})",
                        review.getId(), review.getInstrument(), review.getAction());
            } else {
                surviving.add(review);
            }
        }
        return surviving;
    }

    /**
     * When two open simulations exist for the same instrument with opposite directions,
     * the older one is closed: PENDING_ENTRY → CANCELLED, ACTIVE → REVERSED.
     */
    private List<MentorSignalReviewRecord> reverseConflictingTrades(
            List<MentorSignalReviewRecord> candidates, Instant now) {
        // Group open simulations by instrument
        Map<String, List<MentorSignalReviewRecord>> byInstrument = new HashMap<>();
        for (MentorSignalReviewRecord r : candidates) {
            if (r.getInstrument() != null && r.getAction() != null) {
                byInstrument.computeIfAbsent(r.getInstrument(), k -> new ArrayList<>()).add(r);
            }
        }

        List<MentorSignalReviewRecord> toClose = new ArrayList<>();
        for (List<MentorSignalReviewRecord> group : byInstrument.values()) {
            if (group.size() < 2) continue;

            boolean hasLong = group.stream().anyMatch(r -> "LONG".equalsIgnoreCase(r.getAction()));
            boolean hasShort = group.stream().anyMatch(r -> "SHORT".equalsIgnoreCase(r.getAction()));
            if (!hasLong || !hasShort) continue;

            // Sort by createdAt ascending — older trades first
            group.sort(Comparator.comparing(r -> r.getCreatedAt() != null ? r.getCreatedAt() : Instant.MIN));

            // The newest trade wins; all older trades in the opposite direction are closed
            MentorSignalReviewRecord newest = group.get(group.size() - 1);
            for (MentorSignalReviewRecord older : group) {
                if (older == newest) continue;
                if (older.getAction() != null && !older.getAction().equalsIgnoreCase(newest.getAction())) {
                    toClose.add(older);
                }
            }
        }

        List<MentorSignalReviewRecord> surviving = new ArrayList<>(candidates);
        for (MentorSignalReviewRecord stale : toClose) {
            TradeSimulationStatus closeStatus = stale.getSimulationStatus() == TradeSimulationStatus.ACTIVE
                    ? TradeSimulationStatus.REVERSED
                    : TradeSimulationStatus.CANCELLED;
            stale.setSimulationStatus(closeStatus);
            stale.setResolutionTime(now);
            reviewRepository.save(stale);
            dualWriteSignalSimulation(stale);
            pushSimulationUpdate(stale);
            surviving.remove(stale);
            log.info("Simulation {} {} — directional reversal on {} (was {}, newer {} signal exists)",
                    stale.getId(), closeStatus, stale.getInstrument(), stale.getAction(),
                    "LONG".equalsIgnoreCase(stale.getAction()) ? "SHORT" : "LONG");
        }
        return surviving;
    }

    @Scheduled(fixedDelayString = "${riskdesk.trade-simulation.poll-ms:60000}")
    public void refreshPendingAuditSimulations() {
        List<MentorAudit> candidates = auditRepository.findBySimulationStatuses(List.of(
            TradeSimulationStatus.PENDING_ENTRY,
            TradeSimulationStatus.ACTIVE
        ));

        // --- Phase 1 dual-write back-fill: mirror every open audit simulation
        // into the new trade_simulations table (idempotent via unique constraint). ---
        for (MentorAudit audit : candidates) {
            dualWriteAuditSimulation(audit);
        }

        // --- Phase 1: Cancel expired PENDING_ENTRY (>1h) ---
        Instant now = Instant.now();
        List<MentorAudit> surviving = new ArrayList<>();
        for (MentorAudit audit : candidates) {
            if (audit.getSimulationStatus() == TradeSimulationStatus.PENDING_ENTRY
                    && audit.getCreatedAt() != null
                    && Duration.between(audit.getCreatedAt(), now).compareTo(PENDING_ENTRY_TIMEOUT) > 0) {
                audit.setSimulationStatus(TradeSimulationStatus.CANCELLED);
                audit.setResolutionTime(now);
                auditRepository.save(audit);
                dualWriteAuditSimulation(audit);
                log.info("Audit simulation {} CANCELLED — PENDING_ENTRY expired after 1h", audit.getId());
            } else {
                surviving.add(audit);
            }
        }

        // --- Phase 2: Reverse conflicting trades (same instrument, opposite direction) ---
        Map<String, List<MentorAudit>> byInstrument = new HashMap<>();
        for (MentorAudit a : surviving) {
            if (a.getInstrument() != null && a.getAction() != null) {
                byInstrument.computeIfAbsent(a.getInstrument(), k -> new ArrayList<>()).add(a);
            }
        }
        List<MentorAudit> toClose = new ArrayList<>();
        for (List<MentorAudit> group : byInstrument.values()) {
            if (group.size() < 2) continue;
            boolean hasLong = group.stream().anyMatch(a -> "LONG".equalsIgnoreCase(a.getAction()));
            boolean hasShort = group.stream().anyMatch(a -> "SHORT".equalsIgnoreCase(a.getAction()));
            if (!hasLong || !hasShort) continue;
            group.sort(Comparator.comparing(a -> a.getCreatedAt() != null ? a.getCreatedAt() : Instant.MIN));
            MentorAudit newest = group.get(group.size() - 1);
            for (MentorAudit older : group) {
                if (older == newest) continue;
                if (older.getAction() != null && !older.getAction().equalsIgnoreCase(newest.getAction())) {
                    toClose.add(older);
                }
            }
        }
        for (MentorAudit stale : toClose) {
            TradeSimulationStatus closeStatus = stale.getSimulationStatus() == TradeSimulationStatus.ACTIVE
                    ? TradeSimulationStatus.REVERSED : TradeSimulationStatus.CANCELLED;
            stale.setSimulationStatus(closeStatus);
            stale.setResolutionTime(now);
            auditRepository.save(stale);
            dualWriteAuditSimulation(stale);
            surviving.remove(stale);
            log.info("Audit simulation {} {} — directional reversal on {}", stale.getId(), closeStatus, stale.getInstrument());
        }

        // --- Phase 3: Normal candle-based evaluation ---
        for (MentorAudit audit : surviving) {
            try {
                if (audit.getInstrument() == null) continue;
                Instrument instrument = Instrument.valueOf(audit.getInstrument());
                Instant from = audit.getActivationTime() != null ? audit.getActivationTime() : audit.getCreatedAt();
                List<Candle> candles = candleRepositoryPort.findCandles(instrument, SIMULATION_TIMEFRAME, from);
                if (candles.isEmpty()) continue;

                SimulationResult result = evaluateAuditOutcome(audit, candles);
                if (hasAuditSimulationChanged(audit, result)) {
                    audit.setSimulationStatus(result.status());
                    audit.setActivationTime(result.activationTime());
                    audit.setResolutionTime(result.resolutionTime());
                    audit.setMaxDrawdownPoints(result.maxDrawdownPoints());
                    auditRepository.save(audit);
                    dualWriteAuditSimulation(audit);
                }
            } catch (Exception e) {
                log.debug("Audit simulation skipped for audit {}: {}", audit.getId(), e.getMessage());
            }
        }
    }

    public SimulationResult evaluateAuditOutcome(MentorAudit audit, List<Candle> subsequentCandles) {
        TradePlan plan = extractPlanFromAudit(audit);
        if (plan == null) {
            return new SimulationResult(
                TradeSimulationStatus.CANCELLED,
                audit.getActivationTime(),
                audit.getResolutionTime(),
                zero(audit.getMaxDrawdownPoints())
            );
        }

        List<Candle> orderedCandles = subsequentCandles.stream()
            .sorted(Comparator.comparing(Candle::getTimestamp))
            .toList();

        TradeSimulationStatus currentStatus = audit.getSimulationStatus() == null
            ? TradeSimulationStatus.PENDING_ENTRY
            : audit.getSimulationStatus();
        Instant activationTime = audit.getActivationTime();
        BigDecimal maxDrawdown = zero(audit.getMaxDrawdownPoints());
        boolean active = currentStatus == TradeSimulationStatus.ACTIVE;

        for (Candle candle : orderedCandles) {
            if (!active) {
                if (isMissed(plan, candle)) {
                    return new SimulationResult(TradeSimulationStatus.MISSED, null, candle.getTimestamp(), maxDrawdown);
                }
                if (touchesEntry(plan, candle)) {
                    active = true;
                    activationTime = candle.getTimestamp();
                    maxDrawdown = maxDrawdown.max(adverseMove(plan, candle));
                    if (touchesStopAndTarget(plan, candle)) {
                        return new SimulationResult(TradeSimulationStatus.LOSS, activationTime, candle.getTimestamp(), maxDrawdown);
                    }
                    if (touchesStop(plan, candle)) {
                        return new SimulationResult(TradeSimulationStatus.LOSS, activationTime, candle.getTimestamp(), maxDrawdown);
                    }
                    if (touchesTarget(plan, candle)) {
                        return new SimulationResult(TradeSimulationStatus.WIN, activationTime, candle.getTimestamp(), maxDrawdown);
                    }
                }
                continue;
            }
            maxDrawdown = maxDrawdown.max(adverseMove(plan, candle));
            if (touchesStopAndTarget(plan, candle)) {
                return new SimulationResult(TradeSimulationStatus.LOSS, activationTime, candle.getTimestamp(), maxDrawdown);
            }
            if (touchesStop(plan, candle)) {
                return new SimulationResult(TradeSimulationStatus.LOSS, activationTime, candle.getTimestamp(), maxDrawdown);
            }
            if (touchesTarget(plan, candle)) {
                return new SimulationResult(TradeSimulationStatus.WIN, activationTime, candle.getTimestamp(), maxDrawdown);
            }
        }

        return new SimulationResult(
            active ? TradeSimulationStatus.ACTIVE : TradeSimulationStatus.PENDING_ENTRY,
            activationTime, null, maxDrawdown
        );
    }

    private boolean hasAuditSimulationChanged(MentorAudit audit, SimulationResult result) {
        return audit.getSimulationStatus() != result.status()
            || !sameInstant(audit.getActivationTime(), result.activationTime())
            || !sameInstant(audit.getResolutionTime(), result.resolutionTime())
            || zero(audit.getMaxDrawdownPoints()).compareTo(zero(result.maxDrawdownPoints())) != 0;
    }

    private void pushSimulationUpdate(MentorSignalReviewRecord review) {
        try {
            SimpMessagingTemplate messaging = messagingProvider.getIfAvailable();
            MentorSignalReviewService reviewService = reviewServiceProvider.getIfAvailable();
            if (messaging != null && reviewService != null) {
                // Legacy WS topic — preserved in Phase 1 per Simulation Decoupling Rule.
                messaging.convertAndSend("/topic/mentor-alerts", reviewService.toDto(review));
                log.debug("Pushed simulation update for review {} → {}", review.getId(), review.getSimulationStatus());
            }
        } catch (Exception e) {
            log.debug("Could not push simulation update for review {}: {}", review.getId(), e.getMessage());
        }
    }

    /**
     * Phase 1 dual-write: persist simulation state to the new {@code trade_simulations}
     * aggregate IN ADDITION to the legacy review/audit fields. Wrapped in try/catch so
     * a new-side failure does not break the existing review flow.
     *
     * <p>Also publishes the resulting {@link TradeSimulation} to {@code /topic/simulations}
     * on success. The legacy {@code /topic/mentor-alerts} push stays in place until Phase 2.
     */
    private void dualWriteSignalSimulation(MentorSignalReviewRecord review) {
        if (review == null || review.getId() == null) {
            return;
        }
        try {
            TradeSimulation existing = simulationRepository
                .findByReviewId(review.getId(), ReviewType.SIGNAL)
                .orElse(null);
            Instant createdAt = existing != null && existing.createdAt() != null
                ? existing.createdAt()
                : (review.getCreatedAt() != null ? review.getCreatedAt() : Instant.now());
            Long id = existing != null ? existing.id() : null;
            String action = review.getAction() != null ? review.getAction() : "LONG";
            String instrument = review.getInstrument() != null ? review.getInstrument() : "UNKNOWN";
            TradeSimulationStatus status = review.getSimulationStatus() != null
                ? review.getSimulationStatus()
                : TradeSimulationStatus.PENDING_ENTRY;

            TradeSimulation sim = new TradeSimulation(
                id,
                review.getId(),
                ReviewType.SIGNAL,
                instrument,
                action,
                status,
                review.getActivationTime(),
                review.getResolutionTime(),
                review.getMaxDrawdownPoints(),
                review.getTrailingStopResult() != null ? review.getTrailingStopResult().name() : null,
                review.getTrailingExitPrice(),
                review.getBestFavorablePrice(),
                createdAt
            );
            TradeSimulation saved = simulationRepository.save(sim);
            publishSimulationEvent(saved);
        } catch (Exception e) {
            log.warn("Dual-write to trade_simulations failed for review {} (legacy write succeeded): {}",
                review.getId(), e.getMessage());
        }
    }

    private void dualWriteAuditSimulation(MentorAudit audit) {
        if (audit == null || audit.getId() == null) {
            return;
        }
        try {
            TradeSimulation existing = simulationRepository
                .findByReviewId(audit.getId(), ReviewType.AUDIT)
                .orElse(null);
            Instant createdAt = existing != null && existing.createdAt() != null
                ? existing.createdAt()
                : (audit.getCreatedAt() != null ? audit.getCreatedAt() : Instant.now());
            Long id = existing != null ? existing.id() : null;
            String action = audit.getAction() != null ? audit.getAction() : "LONG";
            String instrument = audit.getInstrument() != null ? audit.getInstrument() : "UNKNOWN";
            TradeSimulationStatus status = audit.getSimulationStatus() != null
                ? audit.getSimulationStatus()
                : TradeSimulationStatus.PENDING_ENTRY;

            TradeSimulation sim = new TradeSimulation(
                id,
                audit.getId(),
                ReviewType.AUDIT,
                instrument,
                action,
                status,
                audit.getActivationTime(),
                audit.getResolutionTime(),
                audit.getMaxDrawdownPoints(),
                null,
                null,
                null,
                createdAt
            );
            TradeSimulation saved = simulationRepository.save(sim);
            publishSimulationEvent(saved);
        } catch (Exception e) {
            log.warn("Dual-write to trade_simulations failed for audit {} (legacy write succeeded): {}",
                audit.getId(), e.getMessage());
        }
    }

    /**
     * Publish the new-side simulation payload to {@code /topic/simulations}.
     * Runs alongside (not instead of) the legacy {@code /topic/mentor-alerts} push.
     */
    private void publishSimulationEvent(TradeSimulation sim) {
        try {
            SimpMessagingTemplate messaging = messagingProvider.getIfAvailable();
            if (messaging != null && sim != null) {
                messaging.convertAndSend("/topic/simulations", sim);
                log.debug("Published simulation event on /topic/simulations for review {}:{} → {}",
                    sim.reviewType(), sim.reviewId(), sim.simulationStatus());
            }
        } catch (Exception e) {
            log.debug("Could not push simulation event on /topic/simulations: {}", e.getMessage());
        }
    }

    private boolean hasSimulationChanged(MentorSignalReviewRecord review, SimulationResult result) {
        return review.getSimulationStatus() != result.status()
            || !sameInstant(review.getActivationTime(), result.activationTime())
            || !sameInstant(review.getResolutionTime(), result.resolutionTime())
            || zero(review.getMaxDrawdownPoints()).compareTo(zero(result.maxDrawdownPoints())) != 0
            || review.getTrailingStopResult() != result.trailingStopResult()
            || zero(review.getTrailingExitPrice()).compareTo(zero(result.trailingExitPrice())) != 0
            || zero(review.getBestFavorablePrice()).compareTo(zero(result.bestFavorablePrice())) != 0;
    }

    private boolean sameInstant(Instant left, Instant right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private TradePlan extractPlan(MentorSignalReviewRecord review) {
        return extractPlanFromJson(review.getAnalysisJson(), review.getAction());
    }

    private TradePlan extractPlanFromAudit(MentorAudit audit) {
        return extractPlanFromJson(audit.getResponseJson(), audit.getAction());
    }

    private TradePlan extractPlanFromJson(String analysisJson, String action) {
        if (analysisJson == null || analysisJson.isBlank()) {
            return null;
        }

        try {
            MentorAnalyzeResponse response = objectMapper.readValue(analysisJson, MentorAnalyzeResponse.class);
            if (response.analysis() == null || response.analysis().proposedTradePlan() == null) {
                return null;
            }
            MentorProposedTradePlan proposedTradePlan = response.analysis().proposedTradePlan();
            if (proposedTradePlan.entryPrice() == null
                || proposedTradePlan.stopLoss() == null
                || proposedTradePlan.takeProfit() == null
                || action == null) {
                return null;
            }
            return new TradePlan(
                "LONG".equalsIgnoreCase(action),
                BigDecimal.valueOf(proposedTradePlan.entryPrice()),
                BigDecimal.valueOf(proposedTradePlan.stopLoss()),
                BigDecimal.valueOf(proposedTradePlan.takeProfit())
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isMissed(TradePlan plan, Candle candle) {
        if (plan.isLong()) {
            return candle.getHigh().compareTo(plan.takeProfit()) >= 0
                && candle.getLow().compareTo(plan.entryPrice()) > 0;
        }
        return candle.getLow().compareTo(plan.takeProfit()) <= 0
            && candle.getHigh().compareTo(plan.entryPrice()) < 0;
    }

    private boolean touchesEntry(TradePlan plan, Candle candle) {
        return plan.isLong()
            ? candle.getLow().compareTo(plan.entryPrice()) <= 0
            : candle.getHigh().compareTo(plan.entryPrice()) >= 0;
    }

    private boolean touchesStop(TradePlan plan, Candle candle) {
        return plan.isLong()
            ? candle.getLow().compareTo(plan.stopLoss()) <= 0
            : candle.getHigh().compareTo(plan.stopLoss()) >= 0;
    }

    private boolean touchesTarget(TradePlan plan, Candle candle) {
        return plan.isLong()
            ? candle.getHigh().compareTo(plan.takeProfit()) >= 0
            : candle.getLow().compareTo(plan.takeProfit()) <= 0;
    }

    private boolean touchesStopAndTarget(TradePlan plan, Candle candle) {
        return touchesStop(plan, candle) && touchesTarget(plan, candle);
    }

    private BigDecimal adverseMove(TradePlan plan, Candle candle) {
        BigDecimal move = plan.isLong()
            ? plan.entryPrice().subtract(candle.getLow())
            : candle.getHigh().subtract(plan.entryPrice());
        if (move.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return move.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP) : value.setScale(6, RoundingMode.HALF_UP);
    }

    public TrailingStopStatsResponse computeTrailingStats(int days) {
        List<MentorSignalReviewRecord> resolved = reviewRepository.findBySimulationStatuses(
                List.of(TradeSimulationStatus.WIN, TradeSimulationStatus.LOSS));

        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        List<MentorSignalReviewRecord> recent = resolved.stream()
                .filter(r -> r.getResolutionTime() != null && r.getResolutionTime().isAfter(cutoff))
                .toList();

        int fixedTrades = 0, fixedWins = 0;
        double fixedNetPnl = 0.0;
        int trailingTrades = 0, trailingWins = 0;
        double trailingNetPnl = 0.0;

        for (MentorSignalReviewRecord r : recent) {
            TradePlan plan = extractPlan(r);
            if (plan == null) continue;

            fixedTrades++;
            double entryPrice = plan.entryPrice().doubleValue();
            double sl = plan.stopLoss().doubleValue();
            double tp = plan.takeProfit().doubleValue();

            if (r.getSimulationStatus() == TradeSimulationStatus.WIN) {
                fixedWins++;
                fixedNetPnl += plan.isLong() ? (tp - entryPrice) : (entryPrice - tp);
            } else {
                fixedNetPnl += plan.isLong() ? (sl - entryPrice) : (entryPrice - sl);
            }

            if (r.getTrailingStopResult() != null) {
                trailingTrades++;
                if (r.getTrailingStopResult() == TrailingStopResult.TRAILING_WIN
                        || r.getTrailingStopResult() == TrailingStopResult.TRAILING_BE) {
                    trailingWins++;
                }
                if (r.getTrailingExitPrice() != null) {
                    double exitPrice = r.getTrailingExitPrice().doubleValue();
                    trailingNetPnl += plan.isLong() ? (exitPrice - entryPrice) : (entryPrice - exitPrice);
                }
            }
        }

        double fixedWinRate = fixedTrades > 0 ? (double) fixedWins / fixedTrades : 0.0;
        double trailingWinRate = trailingTrades > 0 ? (double) trailingWins / trailingTrades : 0.0;

        return new TrailingStopStatsResponse(
            "last_" + days + "_days",
            new TrailingStopStatsResponse.TrackStats(fixedTrades, fixedWins,
                    Math.round(fixedWinRate * 100.0) / 100.0,
                    Math.round(fixedNetPnl * 100.0) / 100.0),
            new TrailingStopStatsResponse.TrackStats(trailingTrades, trailingWins,
                    Math.round(trailingWinRate * 100.0) / 100.0,
                    Math.round(trailingNetPnl * 100.0) / 100.0),
            new TrailingStopStatsResponse.Improvement(
                    Math.round((trailingWinRate - fixedWinRate) * 100.0) / 100.0,
                    Math.round((trailingNetPnl - fixedNetPnl) * 100.0) / 100.0)
        );
    }

    private record TradePlan(boolean isLong, BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
    }

    public record SimulationResult(
        TradeSimulationStatus status,
        Instant activationTime,
        Instant resolutionTime,
        BigDecimal maxDrawdownPoints,
        TrailingStopResult trailingStopResult,
        BigDecimal trailingExitPrice,
        BigDecimal bestFavorablePrice
    ) {
        SimulationResult(TradeSimulationStatus status, Instant activationTime,
                         Instant resolutionTime, BigDecimal maxDrawdownPoints) {
            this(status, activationTime, resolutionTime, maxDrawdownPoints, null, null, null);
        }
    }
}

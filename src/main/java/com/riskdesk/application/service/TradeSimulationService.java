package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeSimulationStatus;
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

    private final MentorSignalReviewRepositoryPort reviewRepository;
    private final MentorAuditRepositoryPort auditRepository;
    private final CandleRepositoryPort candleRepositoryPort;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MentorSignalReviewService> reviewServiceProvider;
    private final ObjectProvider<SimpMessagingTemplate> messagingProvider;

    public TradeSimulationService(MentorSignalReviewRepositoryPort reviewRepository,
                                  MentorAuditRepositoryPort auditRepository,
                                  CandleRepositoryPort candleRepositoryPort,
                                  ObjectMapper objectMapper,
                                  ObjectProvider<MentorSignalReviewService> reviewServiceProvider,
                                  ObjectProvider<SimpMessagingTemplate> messagingProvider) {
        this.reviewRepository = reviewRepository;
        this.auditRepository = auditRepository;
        this.candleRepositoryPort = candleRepositoryPort;
        this.objectMapper = objectMapper;
        this.reviewServiceProvider = reviewServiceProvider;
        this.messagingProvider = messagingProvider;
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
            activationTime,
            null,
            maxDrawdown
        );
    }

    @Scheduled(fixedDelayString = "${riskdesk.trade-simulation.poll-ms:60000}")
    public void refreshPendingSimulations() {
        List<MentorSignalReviewRecord> candidates = reviewRepository.findBySimulationStatuses(List.of(
            TradeSimulationStatus.PENDING_ENTRY,
            TradeSimulationStatus.ACTIVE
        ));

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
                    reviewRepository.save(review);
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
                messaging.convertAndSend("/topic/mentor-alerts", reviewService.toDto(review));
                log.debug("Pushed simulation update for review {} → {}", review.getId(), review.getSimulationStatus());
            }
        } catch (Exception e) {
            log.debug("Could not push simulation update for review {}: {}", review.getId(), e.getMessage());
        }
    }

    private boolean hasSimulationChanged(MentorSignalReviewRecord review, SimulationResult result) {
        return review.getSimulationStatus() != result.status()
            || !sameInstant(review.getActivationTime(), result.activationTime())
            || !sameInstant(review.getResolutionTime(), result.resolutionTime())
            || zero(review.getMaxDrawdownPoints()).compareTo(zero(result.maxDrawdownPoints())) != 0;
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

    private record TradePlan(boolean isLong, BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
    }

    public record SimulationResult(
        TradeSimulationStatus status,
        Instant activationTime,
        Instant resolutionTime,
        BigDecimal maxDrawdownPoints
    ) {
    }
}

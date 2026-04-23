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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.ObjectProvider;
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

/**
 * Drives the simulation lifecycle: PENDING_ENTRY → ACTIVE → WIN/LOSS/MISSED/CANCELLED/REVERSED.
 *
 * <p>Phase 3 of the Simulation Decoupling Rule (see
 * {@code docs/ARCHITECTURE_PRINCIPLES.md}):
 * <ul>
 *   <li>The scheduler queries open simulations via
 *       {@link TradeSimulationRepositoryPort#findByStatuses} — no longer
 *       reads via the legacy review/audit sim-status columns.</li>
 *   <li>On every state transition the service writes ONLY to the simulation
 *       aggregate. The legacy sim columns on {@code mentor_signal_reviews}
 *       and {@code mentor_audits} are no longer touched from production
 *       code paths (physical column drop is a follow-up PR that needs a
 *       schema-migration tool).</li>
 *   <li>Simulation events publish exclusively on {@code /topic/simulations}.
 *       The legacy {@code /topic/mentor-alerts} push for simulation updates
 *       is gone; the review-side still uses that topic for non-simulation
 *       mentor events.</li>
 * </ul>
 *
 * <p>The review/audit repositories are still consulted as read-only lookups
 * for the trade plan (entry / SL / TP) — that's trade-plan data that belongs
 * to the review aggregate, not the simulation aggregate.
 */
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
    private final ObjectProvider<SimpMessagingTemplate> messagingProvider;
    private final TrailingStopProperties trailingStopProperties;
    private final TradeSimulationRepositoryPort simulationRepository;

    public TradeSimulationService(MentorSignalReviewRepositoryPort reviewRepository,
                                  MentorAuditRepositoryPort auditRepository,
                                  CandleRepositoryPort candleRepositoryPort,
                                  ObjectMapper objectMapper,
                                  ObjectProvider<SimpMessagingTemplate> messagingProvider,
                                  TrailingStopProperties trailingStopProperties,
                                  TradeSimulationRepositoryPort simulationRepository) {
        this.reviewRepository = reviewRepository;
        this.auditRepository = auditRepository;
        this.candleRepositoryPort = candleRepositoryPort;
        this.objectMapper = objectMapper;
        this.messagingProvider = messagingProvider;
        this.trailingStopProperties = trailingStopProperties;
        this.simulationRepository = simulationRepository;
    }

    /**
     * Pure domain evaluation of a trade's outcome against a candle stream.
     *
     * <p>Kept on the service because both the scheduler (internally) and legacy
     * {@link TradeSimulationServiceTest} cover the evaluation algorithm itself.
     * The evaluation reads the current simulation state + the trade plan JSON
     * from the review record, but persistence is handled separately.
     */
    public SimulationResult evaluateTradeOutcome(MentorSignalReviewRecord review, List<Candle> subsequentCandles) {
        TradePlan plan = extractPlan(review);
        return evaluateWithPlan(plan, currentSimState(review), subsequentCandles);
    }

    public SimulationResult evaluateAuditOutcome(MentorAudit audit, List<Candle> subsequentCandles) {
        TradePlan plan = extractPlanFromAudit(audit);
        return evaluateWithPlan(plan, currentSimState(audit), subsequentCandles);
    }

    private SimulationResult evaluateWithPlan(TradePlan plan, SimState state, List<Candle> subsequentCandles) {
        if (plan == null) {
            return new SimulationResult(
                TradeSimulationStatus.CANCELLED,
                state.activationTime(),
                state.resolutionTime(),
                zero(state.maxDrawdownPoints())
            );
        }

        List<Candle> orderedCandles = subsequentCandles.stream()
            .sorted(Comparator.comparing(Candle::getTimestamp))
            .toList();

        TradeSimulationStatus currentStatus = state.status() == null
            ? TradeSimulationStatus.PENDING_ENTRY
            : state.status();
        Instant activationTime = state.activationTime();
        BigDecimal maxDrawdown = zero(state.maxDrawdownPoints());
        boolean active = currentStatus == TradeSimulationStatus.ACTIVE;

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

        if (!trailingStopProperties.isEnabled() || !active) {
            return new SimulationResult(
                fixedResult.status(), fixedResult.activationTime(), fixedResult.resolutionTime(),
                fixedResult.maxDrawdownPoints(),
                state.trailingStopResult(),
                state.trailingExitPrice(),
                state.bestFavorablePrice()
            );
        }

        TrailingResult trailing = evaluateTrailingStop(
                plan, orderedCandles, activationIndex,
                zero(state.bestFavorablePrice()));
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

            if (plan.isLong()) {
                bestPrice = bestPrice.max(candle.getHigh());
            } else {
                bestPrice = bestPrice.min(candle.getLow());
            }

            BigDecimal favorableMove = plan.isLong()
                    ? bestPrice.subtract(plan.entryPrice())
                    : plan.entryPrice().subtract(bestPrice);

            if (!trailingActivated && favorableMove.compareTo(activationDistance) >= 0) {
                trailingActivated = true;
            }

            if (!trailingActivated) {
                continue;
            }

            BigDecimal trailingSL = plan.isLong()
                    ? bestPrice.subtract(atr.multiply(multiplier))
                    : bestPrice.add(atr.multiply(multiplier));

            BigDecimal effectiveSL;
            if (plan.isLong()) {
                effectiveSL = trailingSL.compareTo(plan.stopLoss()) > 0 ? trailingSL : plan.stopLoss();
            } else {
                effectiveSL = trailingSL.compareTo(plan.stopLoss()) < 0 ? trailingSL : plan.stopLoss();
            }

            boolean trailingHit = plan.isLong()
                    ? candle.getLow().compareTo(effectiveSL) <= 0
                    : candle.getHigh().compareTo(effectiveSL) >= 0;

            if (trailingHit) {
                BigDecimal exitPrice = effectiveSL.setScale(6, RoundingMode.HALF_UP);
                TrailingStopResult result = classifyTrailingExit(plan, exitPrice);
                return new TrailingResult(result, exitPrice, bestPrice.setScale(6, RoundingMode.HALF_UP));
            }
        }

        return new TrailingResult(null, null, bestPrice.setScale(6, RoundingMode.HALF_UP));
    }

    private BigDecimal computeAtrAtActivation(List<Candle> orderedCandles, int activationIndex) {
        int endIdx = Math.max(0, activationIndex);
        int startIdx = Math.max(0, endIdx - ATR_PERIOD - 1);
        if (endIdx - startIdx < ATR_PERIOD) {
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

    private record TrailingResult(TrailingStopResult result, BigDecimal exitPrice, BigDecimal bestFavorablePrice) {
    }

    /**
     * Ticks the signal-review simulations forward. Reads opens from the
     * {@link TradeSimulationRepositoryPort}, resolves trade plans from the
     * review aggregate, and writes state changes back to the simulation
     * aggregate only.
     */
    @Scheduled(fixedDelayString = "${riskdesk.trade-simulation.poll-ms:60000}")
    public void refreshPendingSimulations() {
        List<TradeSimulation> openSignalSims = simulationRepository.findByStatuses(List.of(
            TradeSimulationStatus.PENDING_ENTRY,
            TradeSimulationStatus.ACTIVE
        )).stream().filter(s -> s.reviewType() == ReviewType.SIGNAL).toList();

        Instant now = Instant.now();
        List<TradeSimulation> surviving = cancelExpiredPendingEntries(openSignalSims, now);
        surviving = reverseConflictingTrades(surviving, now);

        for (TradeSimulation sim : surviving) {
            try {
                MentorSignalReviewRecord review = reviewRepository.findById(sim.reviewId()).orElse(null);
                if (review == null) {
                    continue;
                }
                Instrument instrument;
                try {
                    instrument = Instrument.valueOf(sim.instrument());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                Instant from = sim.activationTime() != null ? sim.activationTime() : sim.createdAt();
                List<Candle> candles = candleRepositoryPort.findCandles(instrument, SIMULATION_TIMEFRAME, from);
                if (candles.isEmpty()) {
                    continue;
                }

                SimulationResult result = evaluateWithPlan(extractPlan(review), currentSimState(sim), candles);
                if (hasSimulationChanged(sim, result)) {
                    TradeSimulation next = applyResult(sim, result);
                    TradeSimulation saved = simulationRepository.save(next);
                    publishSimulationEvent(saved);
                }
            } catch (Exception e) {
                log.debug("Trade simulation skipped for simulation {} (reviewId={}): {}",
                    sim.id(), sim.reviewId(), e.getMessage());
            }
        }
    }

    /**
     * Cancel PENDING_ENTRY simulations older than 1 hour — the entry was never triggered.
     * Writes exclusively to the simulation aggregate.
     */
    private List<TradeSimulation> cancelExpiredPendingEntries(List<TradeSimulation> candidates, Instant now) {
        List<TradeSimulation> surviving = new ArrayList<>();
        for (TradeSimulation sim : candidates) {
            if (sim.simulationStatus() == TradeSimulationStatus.PENDING_ENTRY
                    && sim.createdAt() != null
                    && Duration.between(sim.createdAt(), now).compareTo(PENDING_ENTRY_TIMEOUT) > 0) {
                TradeSimulation cancelled = sim.withStatus(TradeSimulationStatus.CANCELLED, now);
                TradeSimulation saved = simulationRepository.save(cancelled);
                publishSimulationEvent(saved);
                log.info("Simulation {} CANCELLED — PENDING_ENTRY expired after 1h (reviewType={}, reviewId={}, instrument={}, action={})",
                        saved.id(), saved.reviewType(), saved.reviewId(), saved.instrument(), saved.action());
            } else {
                surviving.add(sim);
            }
        }
        return surviving;
    }

    /**
     * When two open simulations exist for the same instrument with opposite
     * directions, the older one is closed: PENDING_ENTRY → CANCELLED,
     * ACTIVE → REVERSED. Writes exclusively to the simulation aggregate.
     */
    private List<TradeSimulation> reverseConflictingTrades(List<TradeSimulation> candidates, Instant now) {
        Map<String, List<TradeSimulation>> byInstrument = new HashMap<>();
        for (TradeSimulation sim : candidates) {
            if (sim.instrument() != null && sim.action() != null) {
                byInstrument.computeIfAbsent(sim.instrument(), k -> new ArrayList<>()).add(sim);
            }
        }

        List<TradeSimulation> toClose = new ArrayList<>();
        for (List<TradeSimulation> group : byInstrument.values()) {
            if (group.size() < 2) continue;
            boolean hasLong = group.stream().anyMatch(s -> "LONG".equalsIgnoreCase(s.action()));
            boolean hasShort = group.stream().anyMatch(s -> "SHORT".equalsIgnoreCase(s.action()));
            if (!hasLong || !hasShort) continue;

            group.sort(Comparator.comparing(s -> s.createdAt() != null ? s.createdAt() : Instant.MIN));

            TradeSimulation newest = group.get(group.size() - 1);
            for (TradeSimulation older : group) {
                if (older == newest) continue;
                if (older.action() != null && !older.action().equalsIgnoreCase(newest.action())) {
                    toClose.add(older);
                }
            }
        }

        List<TradeSimulation> surviving = new ArrayList<>(candidates);
        for (TradeSimulation stale : toClose) {
            TradeSimulationStatus closeStatus = stale.simulationStatus() == TradeSimulationStatus.ACTIVE
                    ? TradeSimulationStatus.REVERSED
                    : TradeSimulationStatus.CANCELLED;
            TradeSimulation closed = stale.withStatus(closeStatus, now);
            TradeSimulation saved = simulationRepository.save(closed);
            publishSimulationEvent(saved);
            surviving.remove(stale);
            log.info("Simulation {} {} — directional reversal on {} (was {}, newer {} signal exists)",
                    saved.id(), closeStatus, saved.instrument(), saved.action(),
                    "LONG".equalsIgnoreCase(saved.action()) ? "SHORT" : "LONG");
        }
        return surviving;
    }

    /**
     * Audit-side sibling of {@link #refreshPendingSimulations()}. Reads opens
     * from the simulation port (filtered to {@link ReviewType#AUDIT}), resolves
     * trade plans from the audit record, and writes only to the simulation
     * aggregate.
     */
    @Scheduled(fixedDelayString = "${riskdesk.trade-simulation.poll-ms:60000}")
    public void refreshPendingAuditSimulations() {
        List<TradeSimulation> openAuditSims = simulationRepository.findByStatuses(List.of(
            TradeSimulationStatus.PENDING_ENTRY,
            TradeSimulationStatus.ACTIVE
        )).stream().filter(s -> s.reviewType() == ReviewType.AUDIT).toList();

        Instant now = Instant.now();
        List<TradeSimulation> surviving = cancelExpiredPendingEntries(openAuditSims, now);
        surviving = reverseConflictingTrades(surviving, now);

        for (TradeSimulation sim : surviving) {
            try {
                MentorAudit audit = auditRepository.findById(sim.reviewId()).orElse(null);
                if (audit == null) continue;
                if (audit.getInstrument() == null) continue;
                Instrument instrument;
                try {
                    instrument = Instrument.valueOf(sim.instrument());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                Instant from = sim.activationTime() != null ? sim.activationTime() : sim.createdAt();
                List<Candle> candles = candleRepositoryPort.findCandles(instrument, SIMULATION_TIMEFRAME, from);
                if (candles.isEmpty()) continue;

                SimulationResult result = evaluateWithPlan(extractPlanFromAudit(audit), currentSimState(sim), candles);
                if (hasSimulationChanged(sim, result)) {
                    TradeSimulation next = applyResult(sim, result);
                    TradeSimulation saved = simulationRepository.save(next);
                    publishSimulationEvent(saved);
                }
            } catch (Exception e) {
                log.debug("Audit simulation skipped for simulation {} (reviewId={}): {}",
                    sim.id(), sim.reviewId(), e.getMessage());
            }
        }
    }

    /**
     * Publishes a simulation update on {@code /topic/simulations}. Phase 3
     * removed the legacy {@code /topic/mentor-alerts} push for simulation
     * events — that topic is now reserved for non-simulation mentor events.
     */
    private void publishSimulationEvent(TradeSimulation sim) {
        try {
            SimpMessagingTemplate messaging = messagingProvider.getIfAvailable();
            if (messaging != null && sim != null) {
                messaging.convertAndSend("/topic/simulations", sim);
                log.debug("Published simulation event on /topic/simulations for {}:{} → {}",
                    sim.reviewType(), sim.reviewId(), sim.simulationStatus());
            }
        } catch (Exception e) {
            log.debug("Could not push simulation event on /topic/simulations: {}", e.getMessage());
        }
    }

    private static boolean hasSimulationChanged(TradeSimulation sim, SimulationResult result) {
        return sim.simulationStatus() != result.status()
            || !sameInstant(sim.activationTime(), result.activationTime())
            || !sameInstant(sim.resolutionTime(), result.resolutionTime())
            || bigDecimalCompare(sim.maxDrawdownPoints(), result.maxDrawdownPoints()) != 0
            || !sameTrailing(sim.trailingStopResult(), result.trailingStopResult())
            || bigDecimalCompare(sim.trailingExitPrice(), result.trailingExitPrice()) != 0
            || bigDecimalCompare(sim.bestFavorablePrice(), result.bestFavorablePrice()) != 0;
    }

    private static boolean sameTrailing(String existing, TrailingStopResult incoming) {
        String incomingName = incoming == null ? null : incoming.name();
        if (existing == null && incomingName == null) return true;
        if (existing == null || incomingName == null) return false;
        return existing.equals(incomingName);
    }

    private static int bigDecimalCompare(BigDecimal left, BigDecimal right) {
        BigDecimal a = left == null ? BigDecimal.ZERO : left;
        BigDecimal b = right == null ? BigDecimal.ZERO : right;
        return a.compareTo(b);
    }

    private static boolean sameInstant(Instant left, Instant right) {
        if (left == null && right == null) return true;
        if (left == null || right == null) return false;
        return left.equals(right);
    }

    /**
     * Build a new {@link TradeSimulation} by applying the evaluation result to
     * the existing aggregate. Identity and provenance (id, reviewId, reviewType,
     * instrument, action, createdAt) are preserved; every other field comes from
     * the fresh evaluation.
     */
    private static TradeSimulation applyResult(TradeSimulation sim, SimulationResult result) {
        return new TradeSimulation(
            sim.id(),
            sim.reviewId(),
            sim.reviewType(),
            sim.instrument(),
            sim.action(),
            result.status(),
            result.activationTime(),
            result.resolutionTime(),
            result.maxDrawdownPoints(),
            result.trailingStopResult() != null ? result.trailingStopResult().name() : null,
            result.trailingExitPrice(),
            result.bestFavorablePrice(),
            sim.createdAt()
        );
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

    /**
     * Aggregate trailing-vs-fixed win-rate / net P&L stats over the last
     * {@code days} days. Reads resolved simulations from the simulation port
     * (Phase 3 — no longer sources from review sim fields), then resolves the
     * trade plan from the matching review for P&L computation.
     */
    public TrailingStopStatsResponse computeTrailingStats(int days) {
        List<TradeSimulation> resolved = simulationRepository.findByStatuses(List.of(
            TradeSimulationStatus.WIN,
            TradeSimulationStatus.LOSS
        ));

        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        List<TradeSimulation> recent = resolved.stream()
            .filter(s -> s.reviewType() == ReviewType.SIGNAL)
            .filter(s -> s.resolutionTime() != null && s.resolutionTime().isAfter(cutoff))
            .toList();

        int fixedTrades = 0, fixedWins = 0;
        double fixedNetPnl = 0.0;
        int trailingTrades = 0, trailingWins = 0;
        double trailingNetPnl = 0.0;

        for (TradeSimulation sim : recent) {
            MentorSignalReviewRecord review = reviewRepository.findById(sim.reviewId()).orElse(null);
            if (review == null) continue;
            TradePlan plan = extractPlan(review);
            if (plan == null) continue;

            fixedTrades++;
            double entryPrice = plan.entryPrice().doubleValue();
            double sl = plan.stopLoss().doubleValue();
            double tp = plan.takeProfit().doubleValue();

            if (sim.simulationStatus() == TradeSimulationStatus.WIN) {
                fixedWins++;
                fixedNetPnl += plan.isLong() ? (tp - entryPrice) : (entryPrice - tp);
            } else {
                fixedNetPnl += plan.isLong() ? (sl - entryPrice) : (entryPrice - sl);
            }

            if (sim.trailingStopResult() != null) {
                trailingTrades++;
                if (TrailingStopResult.TRAILING_WIN.name().equals(sim.trailingStopResult())
                        || TrailingStopResult.TRAILING_BE.name().equals(sim.trailingStopResult())) {
                    trailingWins++;
                }
                if (sim.trailingExitPrice() != null) {
                    double exitPrice = sim.trailingExitPrice().doubleValue();
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

    /**
     * Snapshot of the current simulation state, sourced either from the new
     * simulation aggregate (scheduler path) or from the legacy review/audit
     * fields (unit tests still feed us those records). Decouples the
     * evaluation algorithm from which persistence view it's reading.
     */
    private record SimState(
        TradeSimulationStatus status,
        Instant activationTime,
        Instant resolutionTime,
        BigDecimal maxDrawdownPoints,
        TrailingStopResult trailingStopResult,
        BigDecimal trailingExitPrice,
        BigDecimal bestFavorablePrice
    ) { }

    private static SimState currentSimState(TradeSimulation sim) {
        return new SimState(
            sim.simulationStatus(),
            sim.activationTime(),
            sim.resolutionTime(),
            sim.maxDrawdownPoints(),
            sim.trailingStopResult() == null ? null : TrailingStopResult.valueOf(sim.trailingStopResult()),
            sim.trailingExitPrice(),
            sim.bestFavorablePrice()
        );
    }

    @SuppressWarnings("deprecation")
    private static SimState currentSimState(MentorSignalReviewRecord review) {
        return new SimState(
            review.getSimulationStatus(),
            review.getActivationTime(),
            review.getResolutionTime(),
            review.getMaxDrawdownPoints(),
            review.getTrailingStopResult(),
            review.getTrailingExitPrice(),
            review.getBestFavorablePrice()
        );
    }

    @SuppressWarnings("deprecation")
    private static SimState currentSimState(MentorAudit audit) {
        return new SimState(
            audit.getSimulationStatus(),
            audit.getActivationTime(),
            audit.getResolutionTime(),
            audit.getMaxDrawdownPoints(),
            null, null, null
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

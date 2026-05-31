package com.riskdesk.application.service;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrMarginPreflightService.PreflightDecision;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.playbook.automation.PlaybookAutomationState;
import com.riskdesk.domain.playbook.automation.PlaybookDecision;
import com.riskdesk.domain.playbook.automation.PlaybookDecisionSummary;
import com.riskdesk.domain.playbook.automation.PlaybookExecutionProfile;
import com.riskdesk.domain.playbook.automation.PlaybookProfilePlan;
import com.riskdesk.domain.playbook.automation.PlaybookProfilePlanner;
import com.riskdesk.domain.playbook.automation.PlaybookRoutingDecision;
import com.riskdesk.domain.playbook.automation.PlaybookRoutingOutcome;
import com.riskdesk.domain.playbook.automation.PlaybookRoutingPolicy;
import com.riskdesk.domain.playbook.automation.port.PlaybookAutomationStatePort;
import com.riskdesk.domain.playbook.automation.port.PlaybookDecisionRepositoryPort;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import com.riskdesk.infrastructure.config.PlaybookAutomationProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class PlaybookAutomationService {

    private static final Logger log = LoggerFactory.getLogger(PlaybookAutomationService.class);
    private static final String REQUESTED_BY = "playbook-auto";
    private static final PlaybookRoutingPolicy ROUTING_POLICY = new PlaybookRoutingPolicy();
    private static final Set<ExecutionTriggerSource> CROSS_STRATEGY_BLOCKING_SOURCES = EnumSet.of(
        ExecutionTriggerSource.PLAYBOOK_AUTO,
        ExecutionTriggerSource.WTX_AUTO,
        ExecutionTriggerSource.WTXRSI_AUTO,
        ExecutionTriggerSource.QUANT_AUTO_ARM,
        ExecutionTriggerSource.PERFECT_SETUP
    );

    private final PlaybookService playbookService;
    private final PlaybookAutomationStatePort statePort;
    private final PlaybookDecisionRepositoryPort decisionRepository;
    private final TradeSimulationRepositoryPort simulationRepository;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrOrderService ibkrOrderService;
    private final IbkrProperties ibkrProperties;
    private final PlaybookAutomationProperties properties;
    private final ObjectProvider<IbkrMarginPreflightService> marginPreflightProvider;
    private final ObjectProvider<MarketDataService> marketDataServiceProvider;
    private final ObjectProvider<SimpMessagingTemplate> messagingProvider;

    public PlaybookAutomationService(PlaybookService playbookService,
                                     PlaybookAutomationStatePort statePort,
                                     PlaybookDecisionRepositoryPort decisionRepository,
                                     TradeSimulationRepositoryPort simulationRepository,
                                     TradeExecutionRepositoryPort executionRepository,
                                     IbkrOrderService ibkrOrderService,
                                     IbkrProperties ibkrProperties,
                                     PlaybookAutomationProperties properties,
                                     ObjectProvider<IbkrMarginPreflightService> marginPreflightProvider,
                                     ObjectProvider<MarketDataService> marketDataServiceProvider,
                                     ObjectProvider<SimpMessagingTemplate> messagingProvider) {
        this.playbookService = playbookService;
        this.statePort = statePort;
        this.decisionRepository = decisionRepository;
        this.simulationRepository = simulationRepository;
        this.executionRepository = executionRepository;
        this.ibkrOrderService = ibkrOrderService;
        this.ibkrProperties = ibkrProperties;
        this.properties = properties;
        this.marginPreflightProvider = marginPreflightProvider;
        this.marketDataServiceProvider = marketDataServiceProvider;
        this.messagingProvider = messagingProvider;
    }

    @EventListener
    public void onCandleClosed(CandleClosed event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        if (!containsIgnoreCase(properties.getInstruments(), event.instrument())
            || !containsIgnoreCase(properties.getTimeframes(), event.timeframe())) {
            return;
        }

        Instrument instrument;
        try {
            instrument = Instrument.valueOf(event.instrument());
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (!instrument.isExchangeTradedFuture()) {
            return;
        }

        PlaybookAutomationState state = getState(event.instrument(), event.timeframe());
        PlaybookEvaluation evaluation;
        try {
            evaluation = playbookService.evaluate(instrument, event.timeframe());
        } catch (Exception e) {
            log.debug("PLAYBOOK automation skipped for {} {}: {}",
                event.instrument(), event.timeframe(), e.getMessage());
            return;
        }
        if (evaluation == null || evaluation.checklistScore() < state.paperThreshold()
            || evaluation.bestSetup() == null || evaluation.plan() == null) {
            return;
        }

        PriceSnapshot price = currentPrice(instrument);
        PlaybookDecision candidate = buildDecision(
            event.instrument(), event.timeframe(), event.timestamp(), evaluation, price);

        boolean existing = decisionRepository.findByDecisionKey(candidate.decisionKey()).isPresent();
        PlaybookDecision decision = decisionRepository.createIfAbsent(candidate);
        if (existing || !sameInstant(candidate.createdAt(), decision.createdAt())) {
            return;
        }

        PlaybookRoutingDecision policyDecision = ROUTING_POLICY.evaluate(decision, state);
        if (policyDecision.paperSimulationAllowed()) {
            ensureSimulation(decision, state);
        }
        PlaybookDecision routed = applyRouting(decision, state, instrument);
        publishDecision(routed);
    }

    public PlaybookAutomationState getState(String instrument, String timeframe) {
        String normalizedInstrument = normalizeInstrument(instrument);
        String normalizedTimeframe = normalizeTimeframe(timeframe);
        return statePort.load(normalizedInstrument, normalizedTimeframe)
            .orElseGet(() -> statePort.save(
                PlaybookAutomationState.initial(normalizedInstrument, normalizedTimeframe)));
    }

    public PlaybookAutomationState updateState(String instrument,
                                               String timeframe,
                                               Boolean paperEnabled,
                                               Boolean autoExecutionEnabled,
                                               Integer configuredOrderQty,
                                               String brokerAccountId,
                                               String armedProfile,
                                               Boolean scalpProfileValidated) {
        PlaybookAutomationState current = getState(instrument, timeframe);
        String account = brokerAccountId == null ? current.brokerAccountId() : brokerAccountId;
        if (Boolean.TRUE.equals(autoExecutionEnabled) && (account == null || account.isBlank())) {
            throw new IllegalArgumentException("brokerAccountId is required when Auto-IBKR is enabled");
        }
        if (configuredOrderQty != null && (configuredOrderQty <= 0 || configuredOrderQty > 100)) {
            throw new IllegalArgumentException("configuredOrderQty must be between 1 and 100");
        }
        PlaybookExecutionProfile profile = armedProfile == null
            ? null
            : PlaybookExecutionProfile.parseOrDefault(armedProfile);
        if (profile != null && profile != PlaybookExecutionProfile.LEGACY
            && (!"MGC".equalsIgnoreCase(current.instrument()) || !"10m".equalsIgnoreCase(current.timeframe()))) {
            throw new IllegalArgumentException("non-legacy PLAYBOOK profiles are only available for MGC 10m");
        }
        if (profile != null && profile.benchmarkOnly()) {
            throw new IllegalArgumentException("1R profile is benchmark-only and cannot be armed");
        }
        PlaybookAutomationState updated = current.withSettings(
            paperEnabled, autoExecutionEnabled, configuredOrderQty, brokerAccountId, profile, scalpProfileValidated);
        return statePort.save(updated);
    }

    public List<PlaybookDecision> recentDecisions(String instrument, String timeframe, int limit) {
        return decisionRepository.findRecent(
            normalizeInstrument(instrument),
            normalizeTimeframe(timeframe),
            limit <= 0 ? 20 : Math.min(limit, 100));
    }

    public PlaybookDecisionSummary summarize(String instrument, String timeframe, int limit) {
        List<PlaybookDecision> decisions = recentDecisions(instrument, timeframe, limit);
        if (decisions.isEmpty()) {
            return PlaybookDecisionSummary.empty();
        }
        int wins = 0;
        int losses = 0;
        int missed = 0;
        int resolved = 0;
        BigDecimal drawdownSum = BigDecimal.ZERO;
        int drawdownCount = 0;
        for (PlaybookDecision decision : decisions) {
            Optional<TradeSimulation> sim = decision.id() == null
                ? Optional.empty()
                : simulationRepository.findByReviewId(decision.id(), ReviewType.PLAYBOOK);
            if (sim.isEmpty()) {
                continue;
            }
            TradeSimulationStatus status = sim.get().simulationStatus();
            if (status == TradeSimulationStatus.WIN) {
                wins++;
                resolved++;
            } else if (status == TradeSimulationStatus.LOSS) {
                losses++;
                resolved++;
            } else if (status == TradeSimulationStatus.MISSED) {
                missed++;
                resolved++;
            }
            if (sim.get().maxDrawdownPoints() != null) {
                drawdownSum = drawdownSum.add(sim.get().maxDrawdownPoints());
                drawdownCount++;
            }
        }
        double winRate = resolved == 0 ? 0.0 : (double) wins / resolved;
        BigDecimal avgDrawdown = drawdownCount == 0
            ? BigDecimal.ZERO
            : drawdownSum.divide(BigDecimal.valueOf(drawdownCount), 6, RoundingMode.HALF_UP);
        return new PlaybookDecisionSummary(
            decisions.size(), resolved, wins, losses, missed, winRate, avgDrawdown);
    }

    public Optional<TradeSimulation> simulationFor(PlaybookDecision decision) {
        if (decision == null || decision.id() == null) {
            return Optional.empty();
        }
        return simulationRepository.findByReviewId(decision.id(), ReviewType.PLAYBOOK);
    }

    private void ensureSimulation(PlaybookDecision decision, PlaybookAutomationState state) {
        if (!state.paperEnabled() || decision.id() == null) {
            return;
        }
        if (simulationRepository.findByReviewId(decision.id(), ReviewType.PLAYBOOK).isPresent()) {
            return;
        }
        TradeSimulation sim = new TradeSimulation(
            null,
            decision.id(),
            ReviewType.PLAYBOOK,
            decision.instrument(),
            decision.direction(),
            TradeSimulationStatus.PENDING_ENTRY,
            null,
            null,
            BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP),
            null,
            null,
            null,
            decision.createdAt()
        );
        simulationRepository.save(sim);
    }

    private PlaybookDecision applyRouting(PlaybookDecision decision,
                                          PlaybookAutomationState state,
                                          Instrument instrument) {
        PlaybookRoutingDecision policyDecision = ROUTING_POLICY.evaluate(decision, state);
        PlaybookDecision routed;
        if (!policyDecision.liveRoutingAllowed()) {
            routed = decision.withRouting(policyDecision.outcome(), policyDecision.reason(), null);
        } else {
            routed = routeLive(decision, state, instrument);
        }
        PlaybookDecision saved = decisionRepository.save(routed);
        log.info("PLAYBOOK [{} {}] score={}/7 outcome={}",
            saved.instrument(), saved.timeframe(), saved.checklistScore(), saved.routingOutcome());
        return saved;
    }

    private PlaybookDecision routeLive(PlaybookDecision decision,
                                       PlaybookAutomationState state,
                                       Instrument instrument) {
        if (decision.entryPrice() == null || decision.stopLoss() == null || decision.takeProfit1() == null) {
            return decision.withRouting(PlaybookRoutingOutcome.SKIPPED_NO_PLAN, "missing entry/SL/TP", null);
        }
        if (decision.lateEntry()) {
            return decision.withRouting(PlaybookRoutingOutcome.SKIPPED_LATE_ENTRY, "late entry", null);
        }
        if (state.configuredOrderQty() <= 0) {
            return decision.withRouting(PlaybookRoutingOutcome.SKIPPED_NO_QTY, "quantity must be positive", null);
        }
        if (state.brokerAccountId() == null || state.brokerAccountId().isBlank()) {
            return decision.withRouting(PlaybookRoutingOutcome.SKIPPED_NO_ACCOUNT, "broker account missing", null);
        }
        if (!isLiveSource(decision.priceSource())) {
            return decision.withRouting(
                PlaybookRoutingOutcome.SKIPPED_STALE_PRICE_SOURCE,
                "live IBKR price source required, got " + blankFallback(decision.priceSource(), "UNKNOWN"),
                null);
        }
        if (!ibkrProperties.isEnabled()) {
            return decision.withRouting(PlaybookRoutingOutcome.SKIPPED_IBKR_DISABLED, "IBKR disabled", null);
        }
        IbkrMarginPreflightService marginPreflight = marginPreflightProvider.getIfAvailable();
        if (marginPreflight != null) {
            PreflightDecision preflight = marginPreflight.canAffordOrder(
                instrument,
                decision.direction(),
                state.configuredOrderQty(),
                decision.entryPrice());
            if (!preflight.allowed()) {
                return decision.withRouting(
                    PlaybookRoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN,
                    truncate(preflight.denyReason(), 200),
                    null);
            }
        }
        Optional<TradeExecutionRecord> crossStrategyConflict = findCrossStrategyConflict(decision, state);
        if (crossStrategyConflict.isPresent()) {
            TradeExecutionRecord active = crossStrategyConflict.get();
            return decision.withRouting(
                PlaybookRoutingOutcome.SKIPPED_DUPLICATE,
                "active " + active.getTriggerSource() + " execution already exists: " + active.getId(),
                active.getId());
        }
        Optional<TradeExecutionRecord> active = executionRepository
            .findActiveByInstrumentAndTimeframeAndTriggerSource(
                decision.instrument(), decision.timeframe(), ExecutionTriggerSource.PLAYBOOK_AUTO);
        if (active.isPresent()) {
            return decision.withRouting(
                PlaybookRoutingOutcome.SKIPPED_DUPLICATE,
                "active PLAYBOOK_AUTO execution already exists: " + active.get().getId(),
                active.get().getId());
        }

        PlaybookProfilePlan profilePlan = PlaybookProfilePlanner.planFor(decision, state.armedProfile());
        String executionKey = executionKey(decision, state.armedProfile());
        Optional<TradeExecutionRecord> existingExecution = executionRepository.findByExecutionKey(executionKey);
        if (existingExecution.isPresent()) {
            return decision.withRouting(
                PlaybookRoutingOutcome.SKIPPED_DUPLICATE,
                "execution already exists: " + existingExecution.get().getId(),
                existingExecution.get().getId());
        }

        Instant now = Instant.now();
        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey(executionKey);
        candidate.setMentorSignalReviewId(null);
        candidate.setReviewAlertKey(decision.decisionKey());
        candidate.setReviewRevision(null);
        candidate.setBrokerAccountId(state.brokerAccountId());
        candidate.setInstrument(decision.instrument());
        candidate.setTimeframe(decision.timeframe());
        candidate.setAction(decision.direction());
        candidate.setQuantity(state.configuredOrderQty());
        candidate.setTriggerSource(ExecutionTriggerSource.PLAYBOOK_AUTO);
        candidate.setRequestedBy(REQUESTED_BY);
        candidate.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        candidate.setStatusReason("PLAYBOOK " + state.armedProfile().name()
            + " score " + decision.checklistScore() + "/7 armed");
        candidate.setNormalizedEntryPrice(normalizeToTick(decision.entryPrice(), instrument));
        candidate.setVirtualStopLoss(normalizeToTick(decision.stopLoss(), instrument));
        candidate.setVirtualTakeProfit(normalizeToTick(profilePlan.takeProfit(), instrument));
        candidate.setLastReliableLivePrice(decision.entryPrice());
        candidate.setLastReliableLivePriceAt(decision.priceTimestamp());
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);

        TradeExecutionRecord persisted = executionRepository.createIfAbsent(candidate);
        if (!sameInstant(now, persisted.getCreatedAt())) {
            return decision.withRouting(
                PlaybookRoutingOutcome.SKIPPED_DUPLICATE,
                "execution already exists: " + persisted.getId(),
                persisted.getId());
        }
        try {
            BrokerEntryOrderSubmission submission = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                persisted.getId(),
                persisted.getExecutionKey(),
                persisted.getBrokerAccountId(),
                persisted.getInstrument(),
                persisted.getAction(),
                persisted.getQuantity(),
                persisted.getNormalizedEntryPrice()
            ));
            persisted.setEntryOrderId(submission.brokerOrderId());
            persisted.setIbkrOrderId(toIbkrOrderId(submission.brokerOrderId()));
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("PLAYBOOK submitted: " + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submission.submittedAt() == null ? now : submission.submittedAt());
            persisted.setUpdatedAt(Instant.now());
            TradeExecutionRecord savedExecution = executionRepository.save(persisted);
            return decision.withRouting(PlaybookRoutingOutcome.ROUTED, null, savedExecution.getId());
        } catch (IbkrOrderRejectionException e) {
            return handleBrokerRejection(decision, persisted, e);
        } catch (RuntimeException e) {
            String message = truncate(e.getMessage(), 200);
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason("PLAYBOOK submission failed: " + message);
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            return decision.withRouting(PlaybookRoutingOutcome.FAILED, message, persisted.getId());
        }
    }

    private Optional<TradeExecutionRecord> findCrossStrategyConflict(PlaybookDecision decision,
                                                                     PlaybookAutomationState state) {
        String account = state.brokerAccountId();
        return executionRepository.findAllActive().stream()
            .filter(row -> sameIgnoreCase(row.getInstrument(), decision.instrument()))
            .filter(row -> CROSS_STRATEGY_BLOCKING_SOURCES.contains(row.getTriggerSource()))
            .filter(row -> account == null || row.getBrokerAccountId() == null
                || sameIgnoreCase(row.getBrokerAccountId(), account))
            .findFirst();
    }

    private static String executionKey(PlaybookDecision decision, PlaybookExecutionProfile profile) {
        if (profile == null || profile == PlaybookExecutionProfile.LEGACY) {
            return decision.decisionKey();
        }
        return decision.decisionKey() + ":" + profile.name();
    }

    private PlaybookDecision handleBrokerRejection(PlaybookDecision decision,
                                                   TradeExecutionRecord row,
                                                   IbkrOrderRejectionException e) {
        String brokerText = e.brokerMessage() != null ? e.brokerMessage() : e.getMessage();
        String shortMsg = truncate(brokerText, 200);
        PlaybookRoutingOutcome outcome;
        if (e.kind() == IbkrOrderRejectionException.Kind.TIMEOUT) {
            Long brokerOrderId = e.brokerOrderId();
            if (brokerOrderId != null) {
                outcome = PlaybookRoutingOutcome.ACK_PENDING;
                row.setEntryOrderId(brokerOrderId);
                row.setIbkrOrderId(toIbkrOrderId(brokerOrderId));
                row.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
                row.setStatusReason("PLAYBOOK sent to IBKR; acknowledgement pending (broker order " + brokerOrderId + ")");
                row.setEntrySubmittedAt(Instant.now());
            } else {
                outcome = PlaybookRoutingOutcome.FAILED_TIMEOUT;
                row.setStatusReason("PLAYBOOK timeout — ack lost, manual reconcile required");
            }
        } else {
            outcome = PlaybookRoutingOutcome.FAILED_BROKER_REJECT;
            row.setStatus(ExecutionStatus.FAILED);
            row.setStatusReason("PLAYBOOK rejected: " + shortMsg);
        }
        row.setUpdatedAt(Instant.now());
        TradeExecutionRecord saved = executionRepository.save(row);
        return decision.withRouting(outcome, shortMsg, saved.getId());
    }

    private PlaybookDecision buildDecision(String instrument,
                                           String timeframe,
                                           Instant candleTs,
                                           PlaybookEvaluation evaluation,
                                           PriceSnapshot price) {
        SetupCandidate setup = evaluation.bestSetup();
        PlaybookPlan plan = evaluation.plan();
        String direction = evaluation.filters().tradeDirection().name();
        String setupIdentity = buildSetupIdentity(instrument, timeframe, candleTs, setup, direction);
        String key = "playbook:" + setupIdentity;
        return new PlaybookDecision(
            null,
            key,
            instrument,
            timeframe,
            setupIdentity,
            setup.type().name(),
            setup.zoneName(),
            direction,
            evaluation.checklistScore(),
            truncate(evaluation.verdict(), 250),
            plan.entryPrice(),
            plan.stopLoss(),
            plan.takeProfit1(),
            plan.takeProfit2(),
            BigDecimal.valueOf(plan.rrRatio()).setScale(4, RoundingMode.HALF_UP),
            BigDecimal.valueOf(plan.riskPercent()).setScale(6, RoundingMode.HALF_UP),
            evaluation.lateEntry(),
            price.source(),
            price.timestamp(),
            candleTs,
            Instant.now(),
            null,
            null,
            null
        );
    }

    private PriceSnapshot currentPrice(Instrument instrument) {
        try {
            MarketDataService marketDataService = marketDataServiceProvider.getIfAvailable();
            if (marketDataService == null) {
                return new PriceSnapshot(null, null, "UNKNOWN");
            }
            MarketDataService.StoredPrice stored = marketDataService.currentPrice(instrument);
            if (stored == null) {
                return new PriceSnapshot(null, null, "UNKNOWN");
            }
            return new PriceSnapshot(stored.price(), stored.timestamp(), stored.source());
        } catch (Exception e) {
            return new PriceSnapshot(null, null, "UNKNOWN");
        }
    }

    private void publishDecision(PlaybookDecision decision) {
        try {
            SimpMessagingTemplate ws = messagingProvider.getIfAvailable();
            if (ws != null) {
                ws.convertAndSend("/topic/playbook-decisions/" + decision.instrument() + "/" + decision.timeframe(),
                    decision);
            }
        } catch (Exception e) {
            log.debug("Failed to publish PLAYBOOK decision {}: {}", decision.decisionKey(), e.getMessage());
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String needle) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String buildSetupIdentity(String instrument,
                                             String timeframe,
                                             Instant candleTs,
                                             SetupCandidate setup,
                                             String direction) {
        return String.join(":",
            instrument,
            timeframe,
            String.valueOf(candleTs.getEpochSecond()),
            direction,
            setup.type().name(),
            slug(setup.zoneName())
        );
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "setup";
        }
        String normalized = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            return "setup";
        }
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    private static boolean isLiveSource(String source) {
        return source != null && source.startsWith("LIVE");
    }

    private static String normalizeInstrument(String instrument) {
        if (instrument == null || instrument.isBlank()) {
            throw new IllegalArgumentException("instrument is required");
        }
        return instrument.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeTimeframe(String timeframe) {
        if (timeframe == null || timeframe.isBlank()) {
            throw new IllegalArgumentException("timeframe is required");
        }
        return timeframe.trim();
    }

    private static BigDecimal normalizeToTick(BigDecimal price, Instrument instrument) {
        BigDecimal tickSize = instrument.getTickSize();
        return price.divide(tickSize, 0, RoundingMode.HALF_UP)
            .multiply(tickSize)
            .setScale(tickSize.scale(), RoundingMode.HALF_UP);
    }

    private static Integer toIbkrOrderId(Long brokerOrderId) {
        return brokerOrderId == null ? null : brokerOrderId.intValue();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean sameIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static boolean sameInstant(Instant left, Instant right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private record PriceSnapshot(BigDecimal price, Instant timestamp, String source) {
    }
}

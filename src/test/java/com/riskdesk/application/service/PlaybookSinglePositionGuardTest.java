package com.riskdesk.application.service;

import com.riskdesk.domain.engine.playbook.model.ChecklistItem;
import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.FilterResult;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SetupType;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.playbook.automation.PlaybookAutomationState;
import com.riskdesk.domain.playbook.automation.PlaybookDecision;
import com.riskdesk.domain.playbook.automation.PlaybookExecutionProfile;
import com.riskdesk.domain.playbook.automation.PlaybookRoutingOutcome;
import com.riskdesk.domain.playbook.automation.port.PlaybookAutomationStatePort;
import com.riskdesk.domain.playbook.automation.port.PlaybookDecisionRepositoryPort;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import com.riskdesk.infrastructure.config.PlaybookAutomationProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one-position-at-a-time rule: while a PLAYBOOK simulation is open
 * (PENDING_ENTRY/ACTIVE) on a panel, new qualified decisions are logged with
 * SKIPPED_POSITION_OPEN and create neither a simulation nor a live route; once
 * the position resolves, the next decision simulates again.
 */
class PlaybookSinglePositionGuardTest {

    private static final Instant T0 = Instant.parse("2026-06-12T14:00:00Z");

    private final InMemoryDecisions decisions = new InMemoryDecisions();
    private final InMemorySims sims = new InMemorySims();
    private final PlaybookAutomationService service = newService(PlaybookExecutionProfile.LEGACY);

    @Test
    void secondDecisionWhilePositionOpenIsLoggedButNotSimulated() {
        service.onCandleClosed(new CandleClosed("MNQ", "10m", T0));
        assertEquals(1, decisions.rows.size());
        assertEquals(1, sims.rows.size());
        assertEquals(PlaybookRoutingOutcome.PAPER_ONLY, latestDecision().routingOutcome());

        service.onCandleClosed(new CandleClosed("MNQ", "10m", T0.plusSeconds(600)));
        assertEquals(2, decisions.rows.size());
        assertEquals(1, sims.rows.size()); // no overlapping sim
        assertEquals(PlaybookRoutingOutcome.SKIPPED_POSITION_OPEN, latestDecision().routingOutcome());

        // resolve the open position -> the panel frees up
        TradeSimulation open = sims.rows.values().iterator().next();
        sims.save(new TradeSimulation(open.id(), open.reviewId(), open.reviewType(), open.instrument(),
            open.action(), TradeSimulationStatus.WIN, T0, T0.plusSeconds(900),
            BigDecimal.ZERO, null, null, null, open.createdAt()));

        service.onCandleClosed(new CandleClosed("MNQ", "10m", T0.plusSeconds(1_200)));
        assertEquals(3, decisions.rows.size());
        assertEquals(2, sims.rows.size());
        assertEquals(PlaybookRoutingOutcome.PAPER_ONLY, latestDecision().routingOutcome());
    }

    @Test
    void openPositionOnAnotherTimeframeDoesNotBlockThePanel() {
        service.onCandleClosed(new CandleClosed("MNQ", "5m", T0));
        assertEquals(1, sims.rows.size());

        service.onCandleClosed(new CandleClosed("MNQ", "10m", T0.plusSeconds(60)));
        assertEquals(2, sims.rows.size()); // different panel -> not blocked
        assertEquals(PlaybookRoutingOutcome.PAPER_ONLY, latestDecision().routingOutcome());
    }

    @Test
    void confirmationProfileIsSoleStream_legacyChallengerRetired() {
        PlaybookAutomationService confirm = newService(PlaybookExecutionProfile.MNQ_10M_CONFIRMATION);

        // T0 = 14:00Z = 10:00 ET (Friday) — inside RTH so the LONG confirmation gate passes.
        // Only the confirmation stream runs now — no legacy shadow decision.
        confirm.onCandleClosed(new CandleClosed("MNQ", "10m", T0));
        assertEquals(1, decisions.rows.size());
        assertEquals(1, sims.rows.size());
        assertTrue(decisions.rows.values().iterator().next().isStopEntry());
        assertEquals(PlaybookRoutingOutcome.PAPER_ONLY, latestDecision().routingOutcome());

        // Next candle: the zone is already attempted (open confirmation sim) → the candidate
        // is suppressed in buildConfirmationDecision before any row is written.
        confirm.onCandleClosed(new CandleClosed("MNQ", "10m", T0.plusSeconds(600)));
        assertEquals(1, decisions.rows.size());
        assertEquals(1, sims.rows.size());

        // Resolve the confirmation position.
        TradeSimulation open = sims.rows.values().iterator().next();
        sims.save(new TradeSimulation(open.id(), open.reviewId(), open.reviewType(),
            open.instrument(), open.action(), TradeSimulationStatus.WIN, T0, T0.plusSeconds(900),
            BigDecimal.ZERO, null, null, null, open.createdAt()));

        // 3h later (zone cooldown expired, still RTH): a fresh confirmation entry is taken.
        confirm.onCandleClosed(new CandleClosed("MNQ", "10m", T0.plusSeconds(3 * 3_600)));
        assertEquals(2, decisions.rows.size());
        assertEquals(2, sims.rows.size());
        assertTrue(decisions.rows.values().stream().allMatch(PlaybookDecision::isStopEntry));
        assertEquals(PlaybookRoutingOutcome.PAPER_ONLY, latestDecision().routingOutcome());
    }

    private PlaybookDecision latestDecision() {
        return decisions.rows.values().stream()
            .max(Comparator.comparing(PlaybookDecision::id))
            .orElseThrow();
    }

    // ── wiring ───────────────────────────────────────────────────────────────

    private PlaybookAutomationService newService(PlaybookExecutionProfile armedProfile) {
        return new PlaybookAutomationService(
            stubPlaybookService(),
            statePort(armedProfile),
            decisions,
            sims,
            null,
            null,
            new IbkrProperties(),
            new PlaybookAutomationProperties(),
            emptyProvider(),
            emptyProvider(),
            emptyProvider(),
            emptyProvider());
    }

    private static PlaybookService stubPlaybookService() {
        return new PlaybookService(null, null) {
            @Override
            public java.math.BigDecimal computeAtr(Instrument instrument, String timeframe) {
                return new BigDecimal("40");
            }

            @Override
            public PlaybookEvaluation evaluate(Instrument instrument, String timeframe) {
                SetupCandidate setup = new SetupCandidate(SetupType.ZONE_RETEST, "OB 29600",
                    new BigDecimal("29620"), new BigDecimal("29580"), new BigDecimal("29600"),
                    0.0, true, true, true, 2.0, 5);
                PlaybookPlan plan = new PlaybookPlan(new BigDecimal("29600"), new BigDecimal("29560"),
                    new BigDecimal("29680"), null, 2.0, 0.01, "test", "test");
                FilterResult filters = new FilterResult(true, "BULLISH", Direction.LONG,
                    true, 1, 0, 1, 1.0, true, null, true);
                return new PlaybookEvaluation(filters, List.of(setup), setup, plan,
                    List.<ChecklistItem>of(), 5, "LONG — ZONE RETEST — 5/7", Instant.now(), false);
            }
        };
    }

    private static PlaybookAutomationStatePort statePort(PlaybookExecutionProfile armedProfile) {
        return new PlaybookAutomationStatePort() {
            @Override public Optional<PlaybookAutomationState> load(String instrument, String timeframe) {
                return Optional.of(PlaybookAutomationState.initial(instrument, timeframe)
                    .withSettings(null, null, null, null, armedProfile, null));
            }
            @Override public PlaybookAutomationState save(PlaybookAutomationState state) { return state; }
        };
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public T getObject() { throw new UnsupportedOperationException(); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }

    private static final class InMemoryDecisions implements PlaybookDecisionRepositoryPort {
        final Map<Long, PlaybookDecision> rows = new HashMap<>();
        final AtomicLong seq = new AtomicLong();

        @Override public Optional<PlaybookDecision> findById(Long id) {
            return Optional.ofNullable(rows.get(id));
        }
        @Override public Optional<PlaybookDecision> findByDecisionKey(String decisionKey) {
            return rows.values().stream().filter(d -> d.decisionKey().equals(decisionKey)).findFirst();
        }
        @Override public List<PlaybookDecision> findRecent(String instrument, String timeframe, int limit) {
            return rows.values().stream()
                .filter(d -> d.instrument().equals(instrument) && d.timeframe().equals(timeframe))
                .sorted(Comparator.comparing(PlaybookDecision::id).reversed())
                .limit(limit)
                .toList();
        }
        @Override public PlaybookDecision createIfAbsent(PlaybookDecision decision) {
            return findByDecisionKey(decision.decisionKey()).orElseGet(() -> save(decision));
        }
        @Override public PlaybookDecision save(PlaybookDecision decision) {
            long id = decision.id() != null ? decision.id() : seq.incrementAndGet();
            PlaybookDecision withId = new PlaybookDecision(id, decision.decisionKey(),
                decision.instrument(), decision.timeframe(), decision.setupIdentity(),
                decision.setupType(), decision.zoneName(), decision.direction(),
                decision.checklistScore(), decision.verdict(), decision.entryPrice(),
                decision.stopLoss(), decision.takeProfit1(), decision.takeProfit2(),
                decision.rrRatio(), decision.riskPercent(), decision.lateEntry(),
                decision.priceSource(), decision.priceTimestamp(), decision.evaluatedCandleTs(),
                decision.createdAt(), decision.routingOutcome(), decision.routingErrorMessage(),
                decision.executionId(), decision.entryType(), decision.invalidationPrice());
            rows.put(id, withId);
            return withId;
        }
    }

    private static final class InMemorySims implements TradeSimulationRepositoryPort {
        final Map<Long, TradeSimulation> rows = new HashMap<>();
        final AtomicLong seq = new AtomicLong();

        @Override public Optional<TradeSimulation> findByReviewId(long reviewId, ReviewType type) {
            return rows.values().stream()
                .filter(s -> s.reviewId() == reviewId && s.reviewType() == type)
                .findFirst();
        }
        @Override public List<TradeSimulation> findByInstrument(String instrument, int limit) {
            return List.of();
        }
        @Override public List<TradeSimulation> findByStatuses(Collection<TradeSimulationStatus> statuses) {
            return rows.values().stream().filter(s -> statuses.contains(s.simulationStatus())).toList();
        }
        @Override public List<TradeSimulation> findRecent(int limit) {
            return new ArrayList<>(rows.values());
        }
        @Override public TradeSimulation save(TradeSimulation sim) {
            long id = sim.id() != null ? sim.id() : seq.incrementAndGet();
            TradeSimulation withId = new TradeSimulation(id, sim.reviewId(), sim.reviewType(),
                sim.instrument(), sim.action(), sim.simulationStatus(), sim.activationTime(),
                sim.resolutionTime(), sim.maxDrawdownPoints(), sim.trailingStopResult(),
                sim.trailingExitPrice(), sim.bestFavorablePrice(), sim.createdAt());
            rows.put(id, withId);
            return withId;
        }
    }
}

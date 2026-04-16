package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.dto.MacroCorrelationSnapshot;
import com.riskdesk.application.dto.PortfolioSummary;
import com.riskdesk.domain.engine.playbook.agent.*;
import com.riskdesk.domain.engine.playbook.event.AgentDecisionEvent;
import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.FinalVerdict;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.port.MarketDepthPort;
import com.riskdesk.domain.shared.TradingSessionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the trading agents that gate a setup before it becomes a Mentor review.
 *
 * <p>Topology (post-refactor):
 * <ol>
 *   <li>Deterministic <b>Risk gate</b> ({@link RiskManagementService}) — drawdown kill switch,
 *       max positions, correlation, margin, DXY headwind, R:R sanity. Runs in microseconds.
 *       If it blocks, we short-circuit and skip all Gemini calls.</li>
 *   <li>One deterministic agent: {@link SessionTimingAgent} (kill zones, maintenance window).</li>
 *   <li>Three Gemini-powered agents: {@link MtfConfluenceAIAgent}, {@link OrderFlowAIAgent},
 *       {@link ZoneQualityAIAgent} — each enriched with Phase 5 signals (real ticks,
 *       L2 depth, absorption, OB/FVG quality scores, volume profile, BOS/CHoCH OK-vs-FAKE).</li>
 * </ol>
 *
 * <p>AI agents fail gracefully to rule-based fallbacks when Gemini is unavailable, so the
 * orchestrator never throws for LLM reasons.
 */
@Service
public class AgentOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorService.class);
    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration MTF_TIMEOUT = Duration.ofSeconds(5);

    private final List<TradingAgent> gates;
    private final List<TradingAgent> scorers;
    private final RiskManagementService riskManagementService;
    private final PositionService positionService;
    private final MentorIntermarketService intermarketService;
    private final IndicatorService indicatorService;
    private final TickDataPort tickDataPort;
    private final MarketDepthPort marketDepthPort;
    private final AbsorptionCache absorptionCache;
    private final ExecutorService agentExecutor;
    private final ApplicationEventPublisher eventPublisher;

    public AgentOrchestratorService(
            List<TradingAgent> agents,
            RiskManagementService riskManagementService,
            PositionService positionService,
            MentorIntermarketService intermarketService,
            IndicatorService indicatorService,
            TickDataPort tickDataPort,
            MarketDepthPort marketDepthPort,
            AbsorptionCache absorptionCache,
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            ApplicationEventPublisher eventPublisher) {
        // Spring collects every TradingAgent bean (see TradingAgentConfig) and hands
        // them to us as an ordered list. We partition once at construction so the
        // orchestrate() path does not re-filter on every call.
        //
        //   Gates  → deterministic, fast, may BLOCK     → run sequentially, short-circuit
        //   Others → AI / slow / non-blocking           → run in parallel afterwards
        //
        // Anything not tagged {@code Gate} is treated as a scorer; missing a Scorer
        // tag is a degrade-gracefully case rather than a hard failure.
        List<TradingAgent> gateList = new ArrayList<>();
        List<TradingAgent> scorerList = new ArrayList<>();
        for (TradingAgent a : agents) {
            if (a instanceof Gate) gateList.add(a);
            else scorerList.add(a);
        }
        this.gates = List.copyOf(gateList);
        this.scorers = List.copyOf(scorerList);
        this.riskManagementService = riskManagementService;
        this.positionService = positionService;
        this.intermarketService = intermarketService;
        this.indicatorService = indicatorService;
        this.tickDataPort = tickDataPort;
        this.marketDepthPort = marketDepthPort;
        this.absorptionCache = absorptionCache;
        this.agentExecutor = agentExecutor;
        this.eventPublisher = eventPublisher;

        log.info("AgentOrchestratorService initialized with {} gate(s) {} and {} scorer(s) {}",
            this.gates.size(),
            this.gates.stream().map(TradingAgent::name).toList(),
            this.scorers.size(),
            this.scorers.stream().map(TradingAgent::name).toList());
    }

    public FinalVerdict orchestrate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.bestSetup() == null || playbook.plan() == null) {
            FinalVerdict fv = new FinalVerdict(
                playbook.verdict(), null, 0, List.of(),
                List.of("No setup detected"), "INELIGIBLE"
            );
            publishDecisionEvent(context, playbook, fv);
            return fv;
        }

        // ── 1. Deterministic risk gate — runs first, may short-circuit AI calls ─
        RiskManagementService.RiskGateVerdict risk =
            riskManagementService.evaluate(playbook, context);
        if (risk.blocked()) {
            log.info("Orchestrator: risk gate BLOCKED — {}", risk.blockReason());
            FinalVerdict fv = new FinalVerdict(
                "BLOCKED — " + risk.blockReason(),
                null, 0, List.of(),
                risk.warnings(), "BLOCKED"
            );
            publishDecisionEvent(context, playbook, fv);
            return fv;
        }

        // ── 2. Run GATES sequentially — short-circuit on any hard block ─────
        // Gates are microsecond-fast and deterministic; running them first means a
        // SessionTimingAgent rejection no longer costs 3 Gemini calls + 20s of
        // wall-clock time.
        List<AgentVerdict> gateVerdicts = new ArrayList<>(gates.size());
        for (TradingAgent gate : gates) {
            AgentVerdict v = safeEvaluate(gate, playbook, context);
            gateVerdicts.add(v);
            if (v.adjustments().blocked()) {
                log.info("Orchestrator: gate {} BLOCKED — skipping {} scorer(s)",
                    gate.name(), scorers.size());
                FinalVerdict fv = resolveVerdicts(playbook, gateVerdicts, risk);
                publishDecisionEvent(context, playbook, fv);
                return fv;
            }
        }

        // ── 3. Run SCORERS in parallel on the dedicated executor ────────────
        List<CompletableFuture<AgentVerdict>> futures = scorers.stream()
            .map(agent -> CompletableFuture.supplyAsync(
                () -> safeEvaluate(agent, playbook, context), agentExecutor))
            .toList();

        // Global timeout: we wait for all scorers collectively, not per-agent in a loop.
        // A slow first scorer no longer pushes the wall-clock deadline of later scorers.
        CompletableFuture<Void> all = CompletableFuture.allOf(
            futures.toArray(CompletableFuture[]::new));
        try {
            all.get(AGENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Scorer orchestration timed out after {}ms — collecting completed verdicts",
                AGENT_TIMEOUT.toMillis());
        } catch (Exception e) {
            log.warn("Scorer orchestration failed: {}", e.getMessage());
        }

        // Collect whichever verdicts completed; stragglers become timeout verdicts.
        List<AgentVerdict> verdicts = new ArrayList<>(gates.size() + futures.size());
        verdicts.addAll(gateVerdicts);
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<AgentVerdict> f = futures.get(i);
            String agentName = scorers.get(i).name();
            if (f.isDone() && !f.isCompletedExceptionally() && !f.isCancelled()) {
                verdicts.add(f.getNow(AgentVerdict.timeout(agentName)));
            } else {
                f.cancel(true);
                log.warn("Scorer {} did not complete within {}ms", agentName, AGENT_TIMEOUT.toMillis());
                verdicts.add(AgentVerdict.timeout(agentName));
            }
        }

        // ── 4. Resolve conflicts, publish decision event, return ────────────
        FinalVerdict fv = resolveVerdicts(playbook, verdicts, risk);
        publishDecisionEvent(context, playbook, fv);
        return fv;
    }

    /**
     * Publishes a best-effort {@link AgentDecisionEvent} on every decision. Emission
     * failures are swallowed — the orchestration result is authoritative, the event is
     * an audit-trail side effect.
     */
    private void publishDecisionEvent(AgentContext context, PlaybookEvaluation playbook,
                                       FinalVerdict fv) {
        try {
            String setupType = playbook.bestSetup() != null
                ? playbook.bestSetup().type().name()
                : "NONE";
            List<AgentDecisionEvent.AgentSummary> summaries = fv.agentVerdicts().stream()
                .map(v -> new AgentDecisionEvent.AgentSummary(
                    v.agentName(), v.confidence(), v.reasoning(),
                    v.adjustments() != null && v.adjustments().blocked()))
                .toList();
            AgentDecisionEvent event = new AgentDecisionEvent(
                context.instrument(),
                context.timeframe(),
                setupType,
                fv.eligibility(),
                fv.sizePercent(),
                summaries,
                fv.warnings() != null ? fv.warnings().size() : 0,
                fv.verdict(),
                Instant.now()
            );
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.debug("Failed to publish AgentDecisionEvent: {}", e.getMessage());
        }
    }

    /** Runs an agent's {@code evaluate} guarded against exceptions. */
    private AgentVerdict safeEvaluate(TradingAgent agent, PlaybookEvaluation playbook,
                                       AgentContext context) {
        try {
            return agent.evaluate(playbook, context);
        } catch (Exception e) {
            log.warn("Agent {} failed: {}", agent.name(), e.getMessage());
            return AgentVerdict.error(agent.name(), e.getMessage());
        }
    }

    private FinalVerdict resolveVerdicts(PlaybookEvaluation playbook,
                                          List<AgentVerdict> verdicts,
                                          RiskManagementService.RiskGateVerdict risk) {
        List<String> warnings = new ArrayList<>(risk.warnings());
        // Start from the risk-gate cap so the gate always wins
        double sizePct = Math.min(playbook.plan().riskPercent(), risk.sizePct());
        boolean blocked = false;

        for (AgentVerdict v : verdicts) {
            var adj = v.adjustments();

            // Agent-proposed size cap (agents can only REDUCE, never raise)
            if (adj.sizePctCap().isPresent()) {
                sizePct = Math.min(sizePct, adj.sizePctCap().get());
            }

            // Hard block (maintenance window, market closed, etc.)
            if (adj.blocked()) {
                blocked = true;
                warnings.add(v.agentName() + ": " + v.reasoning());
            }

            // Low-confidence warning (unless already flagged blocked)
            if (v.confidence() == Confidence.LOW && !adj.blocked()) {
                warnings.add(v.agentName() + ": " + v.reasoning());
            }
        }

        // ── 4. Majority-LOW rule: 2+ of 3 AI agents LOW → treat as ineligible ─
        // Gates are excluded: their LOW is surfaced via 'blocked' or size_pct.
        long aiLow = verdicts.stream()
            .filter(v -> !"Session-Timing".equals(v.agentName()))
            .filter(v -> v.confidence() == Confidence.LOW)
            .count();

        String eligibility;
        if (blocked) {
            eligibility = "BLOCKED";
        } else if (aiLow >= 2) {
            eligibility = "INELIGIBLE";
        } else if (sizePct > 0) {
            eligibility = "ELIGIBLE";
        } else {
            eligibility = "INELIGIBLE";
        }

        PlaybookPlan adjustedPlan = blocked ? null : playbook.plan().withAdjustedSize(sizePct);

        String verdict;
        if (blocked) {
            verdict = "BLOCKED — " + (warnings.isEmpty() ? "agent block" : warnings.get(0));
        } else if ("INELIGIBLE".equals(eligibility)) {
            verdict = playbook.verdict() + " — " + aiLow + " AI agent(s) LOW → stand down";
        } else if (warnings.isEmpty()) {
            verdict = playbook.verdict() + " — ALL AGENTS ALIGNED";
        } else {
            verdict = playbook.verdict() + " — " + warnings.size() + " warning(s)";
        }

        log.info("Orchestrator verdict: {} (size: {}%, agents: {} [{} AI LOW], warnings: {})",
            verdict, String.format("%.4f", sizePct * 100),
            verdicts.size(), aiLow, warnings.size());

        return new FinalVerdict(verdict, adjustedPlan, sizePct, verdicts, warnings, eligibility);
    }

    // ── Context builder ────────────────────────────────────────────────────

    /**
     * Builds a rich AgentContext from real application services.
     * This is the ONLY place where application-layer data is mapped
     * to domain-level agent records.
     */
    public AgentContext buildContext(Instrument instrument, String timeframe,
                                     IndicatorSnapshot snapshot, BigDecimal atr) {
        // 1. PlaybookInput (current TF)
        PlaybookInput input = PlaybookService.toPlaybookInput(snapshot, atr != null ? atr : BigDecimal.ONE);

        // 2. Real portfolio state
        AgentContext.PortfolioState portfolio = buildPortfolioState(instrument);

        // 3. Real macro/DXY context
        AgentContext.MacroSnapshot macro = buildMacroSnapshot(instrument);

        // 4. Multi-timeframe bias (enriched with BOS/CHoCH confirmation)
        AgentContext.MtfSnapshot mtf = buildMtfSnapshot(instrument);

        // 5. Momentum indicators (current TF)
        AgentContext.MomentumSnapshot momentum = buildMomentumSnapshot(snapshot);

        // 6. Session timing
        AgentContext.SessionInfo session = buildSessionInfo(instrument);

        // 7. Phase 5 enrichment — order flow, depth, absorption, volume profile, zone quality
        AgentContext.OrderFlowSnapshot orderFlow = buildOrderFlowSnapshot(instrument);
        AgentContext.DepthSnapshot depth = buildDepthSnapshot(instrument);
        AgentContext.AbsorptionSnapshot absorption = buildAbsorptionSnapshot(instrument);
        AgentContext.VolumeProfileSnapshot volumeProfile = buildVolumeProfileSnapshot(snapshot);
        AgentContext.ZoneQualitySnapshot zoneQuality = buildZoneQualitySnapshot(snapshot);

        return new AgentContext(
            instrument, timeframe, input,
            portfolio, macro, mtf, momentum, session, atr,
            orderFlow, depth, absorption, volumeProfile, zoneQuality
        );
    }

    /**
     * Convenience overload: builds context and also passes the playbook through so
     * {@link #buildZoneQualitySnapshot(IndicatorSnapshot, PlaybookEvaluation)} can
     * target the actual best-setup zone rather than the first active OB in the list.
     */
    public AgentContext buildContext(Instrument instrument, String timeframe,
                                     IndicatorSnapshot snapshot, BigDecimal atr,
                                     PlaybookEvaluation playbook) {
        AgentContext base = buildContext(instrument, timeframe, snapshot, atr);
        AgentContext.ZoneQualitySnapshot zq = buildZoneQualitySnapshot(snapshot, playbook);
        return new AgentContext(
            base.instrument(), base.timeframe(), base.input(),
            base.portfolio(), base.macro(), base.mtf(), base.momentum(),
            base.session(), base.atr(),
            base.orderFlow(), base.depth(), base.absorption(),
            base.volumeProfile(), zq
        );
    }

    // ── Private builders ─────────────────────────────────────────────────

    private AgentContext.PortfolioState buildPortfolioState(Instrument instrument) {
        try {
            PortfolioSummary summary = positionService.getPortfolioSummary();
            if (summary == null) return AgentContext.PortfolioState.empty();

            boolean correlated = summary.openPositions() != null
                && summary.openPositions().stream()
                    .anyMatch(p -> p.instrument().equals(instrument.name()) && p.open());

            double marginPct = summary.marginUsedPct() != null
                ? summary.marginUsedPct().doubleValue() : 0;

            // Daily drawdown: simplified as unrealized loss % of total exposure
            double drawdown = 0;
            if (summary.totalUnrealizedPnL() != null && summary.totalUnrealizedPnL().doubleValue() < 0
                    && summary.totalExposure() != null && summary.totalExposure().doubleValue() > 0) {
                drawdown = Math.abs(summary.totalUnrealizedPnL().doubleValue()
                    / summary.totalExposure().doubleValue()) * 100;
            }

            return new AgentContext.PortfolioState(
                summary.totalUnrealizedPnL() != null ? summary.totalUnrealizedPnL().doubleValue() : 0,
                drawdown,
                (int) summary.openPositionCount(),
                correlated,
                marginPct
            );
        } catch (Exception e) {
            log.debug("Failed to build portfolio state: {}", e.getMessage());
            return AgentContext.PortfolioState.empty();
        }
    }

    private AgentContext.MacroSnapshot buildMacroSnapshot(Instrument instrument) {
        try {
            MacroCorrelationSnapshot macro = intermarketService.currentForAssetClass(
                instrument, instrument.assetClass());

            String sessionPhase = null;
            boolean killZone = false;
            try {
                var phase = TradingSessionResolver.currentPhase();
                sessionPhase = phase != null ? phase.name() : null;
                killZone = TradingSessionResolver.isWithinKillZone(Instant.now());
            } catch (Exception ignored) {}

            return new AgentContext.MacroSnapshot(
                macro.dxyPctChange(),
                macro.dxyTrend(),
                macro.correlationAlignment(),
                macro.dataAvailability(),
                sessionPhase,
                killZone
            );
        } catch (Exception e) {
            log.debug("Failed to build macro snapshot: {}", e.getMessage());
            return AgentContext.MacroSnapshot.empty();
        }
    }

    private AgentContext.MtfSnapshot buildMtfSnapshot(Instrument instrument) {
        try {
            // Fire all three timeframe snapshots in parallel — no inter-dependency
            // between H1 / H4 / Daily. Wall-clock ~= max(tfLatency) instead of sum.
            CompletableFuture<IndicatorSnapshot> h1F = snapshotFuture(instrument, "1h");
            CompletableFuture<IndicatorSnapshot> h4F = snapshotFuture(instrument, "4h");
            CompletableFuture<IndicatorSnapshot> dF  = snapshotFuture(instrument, "1d");

            try {
                CompletableFuture.allOf(h1F, h4F, dF)
                    .get(MTF_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.debug("MTF snapshot load timed out after {}ms for {} — using whatever is ready",
                    MTF_TIMEOUT.toMillis(), instrument);
            } catch (Exception ignored) {
                // Individual futures handle their own errors and return null
            }

            IndicatorSnapshot h1 = h1F.getNow(null);
            IndicatorSnapshot h4 = h4F.getNow(null);
            IndicatorSnapshot daily = dF.getNow(null);

            String h1Swing = null, h1Internal = null, h1Break = null;
            String h4Swing = null, h4Break = null;
            String dailySwing = null;
            Double h1BreakConfidence = null, h4BreakConfidence = null;
            Boolean h1BreakConfirmed = null, h4BreakConfirmed = null;

            if (h1 != null) {
                h1Swing = h1.swingBias();
                h1Internal = h1.internalBias();
                h1Break = h1.lastBreakType();
                var latestH1Break = latestStructureBreak(h1);
                if (latestH1Break != null) {
                    h1BreakConfidence = latestH1Break.breakConfidenceScore();
                    h1BreakConfirmed = latestH1Break.confirmed();
                }
            }
            if (h4 != null) {
                h4Swing = h4.swingBias();
                h4Break = h4.lastBreakType();
                var latestH4Break = latestStructureBreak(h4);
                if (latestH4Break != null) {
                    h4BreakConfidence = latestH4Break.breakConfidenceScore();
                    h4BreakConfirmed = latestH4Break.confirmed();
                }
            }
            if (daily != null) {
                dailySwing = daily.swingBias();
            }

            return new AgentContext.MtfSnapshot(
                h1Swing, h1Internal, h4Swing, dailySwing,
                h1Break, h4Break,
                h1BreakConfidence, h1BreakConfirmed,
                h4BreakConfidence, h4BreakConfirmed
            );
        } catch (Exception e) {
            log.debug("Failed to build MTF snapshot: {}", e.getMessage());
            return AgentContext.MtfSnapshot.empty();
        }
    }

    /** Async snapshot load that returns {@code null} on any failure — never throws. */
    private CompletableFuture<IndicatorSnapshot> snapshotFuture(Instrument instrument, String tf) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return indicatorService.computeSnapshot(instrument, tf);
            } catch (Exception e) {
                log.trace("No {} snapshot for {}: {}", tf, instrument, e.getMessage());
                return null;
            }
        }, agentExecutor);
    }

    private AgentContext.MomentumSnapshot buildMomentumSnapshot(IndicatorSnapshot snap) {
        if (snap == null) return AgentContext.MomentumSnapshot.empty();

        return new AgentContext.MomentumSnapshot(
            snap.rsi(),
            snap.rsiSignal(),
            snap.macdHistogram(),
            snap.macdCrossover(),
            snap.wtWt1(),
            snap.wtWt2(),
            snap.wtSignal(),
            snap.bbPct(),
            snap.bbTrendExpanding(),
            snap.bbTrendSignal(),
            snap.supertrendBullish(),
            snap.stochSignal(),
            snap.stochCrossover()
        );
    }

    private AgentContext.SessionInfo buildSessionInfo(Instrument instrument) {
        try {
            Instant now = Instant.now();
            var phase = TradingSessionResolver.currentPhase(now);
            boolean killZone = TradingSessionResolver.isWithinKillZone(now);
            boolean marketOpen = TradingSessionResolver.isMarketOpen(now, instrument);
            boolean maintenance = TradingSessionResolver.isStandardMaintenanceWindow(now);

            return new AgentContext.SessionInfo(
                phase != null ? phase.name() : null,
                killZone,
                marketOpen,
                maintenance
            );
        } catch (Exception e) {
            log.debug("Failed to build session info: {}", e.getMessage());
            return AgentContext.SessionInfo.empty();
        }
    }

    private AgentContext.OrderFlowSnapshot buildOrderFlowSnapshot(Instrument instrument) {
        try {
            Optional<TickAggregation> tick = tickDataPort.currentAggregation(instrument);
            if (tick.isEmpty()) return AgentContext.OrderFlowSnapshot.empty();
            TickAggregation t = tick.get();
            return new AgentContext.OrderFlowSnapshot(
                t.source(),
                t.buyVolume(),
                t.sellVolume(),
                t.delta(),
                t.cumulativeDelta(),
                t.buyRatioPct(),
                t.deltaTrend(),
                t.divergenceDetected(),
                t.divergenceType()
            );
        } catch (Exception e) {
            log.debug("Failed to build order-flow snapshot: {}", e.getMessage());
            return AgentContext.OrderFlowSnapshot.empty();
        }
    }

    private AgentContext.DepthSnapshot buildDepthSnapshot(Instrument instrument) {
        try {
            Optional<DepthMetrics> depth = marketDepthPort.currentDepth(instrument);
            if (depth.isEmpty()) return AgentContext.DepthSnapshot.empty();
            DepthMetrics d = depth.get();
            return new AgentContext.DepthSnapshot(
                true,
                d.totalBidSize(),
                d.totalAskSize(),
                d.depthImbalance(),
                d.bestBid(),
                d.bestAsk(),
                d.spreadTicks(),
                d.bidWall() != null,
                d.askWall() != null
            );
        } catch (Exception e) {
            log.debug("Failed to build depth snapshot: {}", e.getMessage());
            return AgentContext.DepthSnapshot.empty();
        }
    }

    private AgentContext.AbsorptionSnapshot buildAbsorptionSnapshot(Instrument instrument) {
        try {
            Optional<AbsorptionSignal> signal = absorptionCache.latest(instrument);
            if (signal.isEmpty()) return AgentContext.AbsorptionSnapshot.empty();
            AbsorptionSignal s = signal.get();
            return new AgentContext.AbsorptionSnapshot(
                true,
                s.side() != null ? s.side().name() : null,
                s.absorptionScore(),
                s.priceMoveTicks(),
                s.totalVolume()
            );
        } catch (Exception e) {
            log.debug("Failed to build absorption snapshot: {}", e.getMessage());
            return AgentContext.AbsorptionSnapshot.empty();
        }
    }

    private AgentContext.VolumeProfileSnapshot buildVolumeProfileSnapshot(IndicatorSnapshot snap) {
        if (snap == null) return AgentContext.VolumeProfileSnapshot.empty();
        Double poc = snap.pocPrice();
        Double vah = snap.valueAreaHigh();
        Double val = snap.valueAreaLow();
        boolean inVa = false;
        if (poc != null && vah != null && val != null && snap.lastPrice() != null) {
            double last = snap.lastPrice().doubleValue();
            inVa = last >= val && last <= vah;
        }
        return new AgentContext.VolumeProfileSnapshot(poc, vah, val, inVa);
    }

    private AgentContext.ZoneQualitySnapshot buildZoneQualitySnapshot(IndicatorSnapshot snap) {
        return buildZoneQualitySnapshot(snap, null);
    }

    /**
     * Extracts enrichment fields from the active OB / FVG / last structure break / nearest
     * equal level. When {@code playbook} is provided, we try to target the OB/FVG actually
     * referenced by the best setup; otherwise we fall back to the first active zone.
     */
    private AgentContext.ZoneQualitySnapshot buildZoneQualitySnapshot(
            IndicatorSnapshot snap, PlaybookEvaluation playbook) {
        if (snap == null) return AgentContext.ZoneQualitySnapshot.empty();

        IndicatorSnapshot.OrderBlockView ob = pickOrderBlock(snap, playbook);
        IndicatorSnapshot.FairValueGapView fvg = pickFairValueGap(snap, playbook);
        IndicatorSnapshot.StructureBreakView br = latestStructureBreak(snap);
        IndicatorSnapshot.EqualLevelView eq = nearestEqualLevel(snap);

        Double obFormationScore = ob != null ? ob.obFormationScore() : null;
        Double obLiveScore = ob != null ? ob.obLiveScore() : null;
        Boolean obDefended = ob != null ? ob.defended() : null;
        Double obAbsorptionScore = ob != null ? ob.absorptionScore() : null;
        Double fvgQualityScore = fvg != null ? fvg.fvgQualityScore() : null;
        Double breakConfidence = br != null ? br.breakConfidenceScore() : null;
        Boolean breakConfirmed = br != null ? br.confirmed() : null;
        Double eqLiquidityScore = eq != null ? eq.liquidityConfirmScore() : null;

        return new AgentContext.ZoneQualitySnapshot(
            obFormationScore, obLiveScore, obDefended, obAbsorptionScore,
            fvgQualityScore, breakConfidence, breakConfirmed, eqLiquidityScore
        );
    }

    // ── Small helpers for zone-quality picking ─────────────────────────────

    private static IndicatorSnapshot.OrderBlockView pickOrderBlock(
            IndicatorSnapshot snap, PlaybookEvaluation playbook) {
        if (snap.activeOrderBlocks() == null || snap.activeOrderBlocks().isEmpty()) return null;
        SetupCandidate setup = playbook != null ? playbook.bestSetup() : null;
        // zoneName convention: "OB <BULLISH|BEARISH> <lo>-<hi>" or "Breaker <lo>-<hi>"
        if (setup != null && setup.zoneName() != null
                && (setup.zoneName().startsWith("OB ") || setup.zoneName().startsWith("Breaker "))
                && setup.zoneHigh() != null && setup.zoneLow() != null) {
            return snap.activeOrderBlocks().stream()
                .filter(o -> o.high() != null && o.low() != null
                    && o.high().compareTo(setup.zoneHigh()) == 0
                    && o.low().compareTo(setup.zoneLow()) == 0)
                .findFirst()
                .orElse(snap.activeOrderBlocks().get(0));
        }
        return snap.activeOrderBlocks().get(0);
    }

    private static IndicatorSnapshot.FairValueGapView pickFairValueGap(
            IndicatorSnapshot snap, PlaybookEvaluation playbook) {
        if (snap.activeFairValueGaps() == null || snap.activeFairValueGaps().isEmpty()) return null;
        SetupCandidate setup = playbook != null ? playbook.bestSetup() : null;
        // zoneName convention: "FVG <BULLISH|BEARISH> <lo>-<hi>"
        if (setup != null && setup.zoneName() != null && setup.zoneName().startsWith("FVG ")
                && setup.zoneHigh() != null && setup.zoneLow() != null) {
            return snap.activeFairValueGaps().stream()
                .filter(f -> f.top() != null && f.bottom() != null
                    && f.top().compareTo(setup.zoneHigh()) == 0
                    && f.bottom().compareTo(setup.zoneLow()) == 0)
                .findFirst()
                .orElse(snap.activeFairValueGaps().get(0));
        }
        return snap.activeFairValueGaps().get(0);
    }

    private static IndicatorSnapshot.StructureBreakView latestStructureBreak(IndicatorSnapshot snap) {
        if (snap.recentBreaks() == null || snap.recentBreaks().isEmpty()) return null;
        // recentBreaks is expected in chronological order — pick the last
        return snap.recentBreaks().get(snap.recentBreaks().size() - 1);
    }

    private static IndicatorSnapshot.EqualLevelView nearestEqualLevel(IndicatorSnapshot snap) {
        // Prefer equal highs, fall back to equal lows
        if (snap.equalHighs() != null && !snap.equalHighs().isEmpty()) {
            return snap.equalHighs().get(0);
        }
        if (snap.equalLows() != null && !snap.equalLows().isEmpty()) {
            return snap.equalLows().get(0);
        }
        return null;
    }
}

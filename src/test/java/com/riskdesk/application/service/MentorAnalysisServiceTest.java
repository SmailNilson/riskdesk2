package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentorAnalysisServiceTest {

    @Mock
    private MentorModelClient mentorModelClient;

    @Mock
    private MentorAuditRepositoryPort mentorAuditRepository;

    @Mock
    private MentorMemoryService mentorMemoryService;

    @Mock
    private TradeSimulationRepositoryPort simulationRepository;

    private final MentorProperties mentorProperties = new MentorProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void analyze_parsesStructuredResponseAndPersistsAudit() throws Exception {
        mentorProperties.setPersistAudits(true);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient,
            mentorMemoryService,
            mentorAuditRepository,
            mentorProperties,
            objectMapper,
            new MentorParseMetrics(),
            simulationRepository
        );

        String rawJson = """
            {
              "technicalQuickAnalysis": "Trend bullish near VWAP.",
              "strengths": ["Respect de la structure"],
              "errors": ["Corrélations absentes"],
              "verdict": "Trade Validé - Discipline Respectée",
              "executionEligibilityStatus": "ELIGIBLE",
              "executionEligibilityReason": "Plan complet et setup conforme.",
              "improvementTip": "Attends plus de confluence macro.",
              "proposedTradePlan": {
                "entryPrice": 3012.5,
                "stopLoss": 3005.0,
                "takeProfit": 3035.0,
                "rewardToRiskRatio": 3.0,
                "rationale": "Entrée sur reprise.",
                "safeDeepEntry": {
                  "entryPrice": 3008.0,
                  "rationale": "Entrée plus basse si le money flow reste vendeur."
                }
              }
            }
            """;
        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of(
            new MentorSimilarAudit(9L, Instant.parse("2026-03-25T02:00:00Z"), "MGC1!", "M5", "LONG", "Trade Validé - Discipline Respectée", 0.91, "MGC1! | LONG | VWAP")
        ));
        when(mentorModelClient.analyze(any(), any())).thenReturn(new MentorModelClient.MentorModelResult("gemini-test", rawJson));
        when(mentorAuditRepository.save(any())).thenAnswer(invocation -> {
            MentorAudit audit = invocation.getArgument(0);
            audit.setId(42L);
            return audit;
        });

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {
              "metadata": {"asset": "MGC1!", "timeframe_focus": "M5", "selected_timezone": "Africa/Casablanca"},
              "trade_intention": {"action": "LONG"}
            }
            """));

        assertThat(response.auditId()).isEqualTo(42L);
        assertThat(response.model()).isEqualTo("gemini-test");
        assertThat(response.analysis().verdict()).isEqualTo("Trade Validé - Discipline Respectée");
        assertThat(response.analysis().executionEligibilityStatus()).isEqualTo(ExecutionEligibilityStatus.ELIGIBLE);
        assertThat(response.analysis().strengths()).containsExactly("Respect de la structure");
        assertThat(response.analysis().proposedTradePlan()).isNotNull();
        assertThat(response.analysis().proposedTradePlan().safeDeepEntry()).isNotNull();
        assertThat(response.similarAudits()).hasSize(1);
        ArgumentCaptor<MentorAudit> auditCaptor = ArgumentCaptor.forClass(MentorAudit.class);
        verify(mentorAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getSelectedTimezone()).isEqualTo("Africa/Casablanca");
    }

    @Test
    void analyze_fallsBackWhenModelReturnsNonJson() throws Exception {
        mentorProperties.setPersistAudits(false);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient,
            mentorMemoryService,
            mentorAuditRepository,
            mentorProperties,
            objectMapper,
            new MentorParseMetrics(),
            simulationRepository
        );

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any())).thenReturn(new MentorModelClient.MentorModelResult("gemini-test", "plain text answer"));

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "SHORT"}}
            """));

        assertThat(response.auditId()).isNull();
        assertThat(response.analysis().technicalQuickAnalysis()).isEqualTo("plain text answer");
        assertThat(response.analysis().errors()).contains("La réponse du modèle n'était pas un JSON strictement exploitable.");
    }

    @Test
    void analyze_recoversFromTruncatedJson() throws Exception {
        // Reproduces the prod bug: Gemini response truncated mid-string in the first field
        // (maxOutputTokens exhausted). The strict parser fails; the recovery path should
        // salvage whatever fields are present and mark the response as partial.
        mentorProperties.setPersistAudits(false);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient,
            mentorMemoryService,
            mentorAuditRepository,
            mentorProperties,
            objectMapper,
            new MentorParseMetrics(),
            simulationRepository
        );

        // Truncated mid-technicalQuickAnalysis — no closing quote, no closing brace.
        String truncated = "{\"technicalQuickAnalysis\": \"Trend is bullish near VWAP with absorption on demand OB";

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", truncated));

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "LONG"}}
            """));

        assertThat(response.analysis().technicalQuickAnalysis())
            .isEqualTo("Trend is bullish near VWAP with absorption on demand OB");
        // Recovered responses are surfaced as MENTOR_UNAVAILABLE (distinct from a
        // real INELIGIBLE rejection). A truncated Gemini reply was never a full
        // verdict — promotion to ELIGIBLE happens downstream via the playbook.
        assertThat(response.analysis().executionEligibilityStatus())
            .isEqualTo(ExecutionEligibilityStatus.MENTOR_UNAVAILABLE);
        assertThat(response.analysis().errors())
            .anySatisfy(e -> assertThat(e).contains("tronquée"));
        assertThat(response.analysis().verdict()).contains("Analyse Partielle");
    }

    // ── PENDING_ENTRY simulation-queue gate ───────────────────────────────
    // Before this, every successful audit (including INELIGIBLE verdicts and
    // manual Ask-Mentor calls with no trade plan) was marked PENDING_ENTRY
    // and then clogged TradeSimulationService's poller forever — the poller
    // cannot resolve a row with no Entry/SL/TP.

    @Test
    void analyze_eligibleWithFullPlan_createsAuditSimulationInPendingEntry() throws Exception {
        // Phase 3: simulation state no longer lives on the audit row. Instead,
        // the service writes a TradeSimulation(PENDING_ENTRY, AUDIT) into the
        // simulation aggregate when the verdict is ELIGIBLE with a complete
        // trade plan.
        mentorProperties.setPersistAudits(true);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository, mentorProperties, objectMapper, new MentorParseMetrics(), simulationRepository);

        String rawJson = """
            {
              "verdict": "Trade Validé - Discipline Respectée",
              "executionEligibilityStatus": "ELIGIBLE",
              "executionEligibilityReason": "Plan complet.",
              "proposedTradePlan": {
                "entryPrice": 3012.5,
                "stopLoss": 3005.0,
                "takeProfit": 3035.0,
                "rewardToRiskRatio": 3.0,
                "rationale": "Entry on pullback."
              }
            }
            """;
        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", rawJson));
        when(mentorAuditRepository.save(any())).thenAnswer(inv -> {
            MentorAudit a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(simulationRepository.findByReviewId(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(ReviewType.AUDIT)))
            .thenReturn(java.util.Optional.empty());
        when(simulationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "LONG"}}
            """));

        ArgumentCaptor<MentorAudit> auditCaptor = ArgumentCaptor.forClass(MentorAudit.class);
        verify(mentorAuditRepository).save(auditCaptor.capture());
        // Legacy audit field is NEVER written in Phase 3.
        assertThat(auditCaptor.getValue().getSimulationStatus()).isNull();

        ArgumentCaptor<TradeSimulation> simCaptor = ArgumentCaptor.forClass(TradeSimulation.class);
        verify(simulationRepository).save(simCaptor.capture());
        TradeSimulation sim = simCaptor.getValue();
        assertThat(sim.reviewType()).isEqualTo(ReviewType.AUDIT);
        assertThat(sim.reviewId()).isEqualTo(1L);
        assertThat(sim.simulationStatus()).isEqualTo(TradeSimulationStatus.PENDING_ENTRY);
        assertThat(sim.instrument()).isEqualTo("MGC1!");
        assertThat(sim.action()).isEqualTo("LONG");
    }

    @Test
    void analyze_ineligibleVerdict_doesNotMarkPendingEntry() throws Exception {
        mentorProperties.setPersistAudits(true);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository, mentorProperties, objectMapper, new MentorParseMetrics(), simulationRepository);

        String rawJson = """
            {
              "verdict": "Trade Rejeté - Structure Cassée",
              "executionEligibilityStatus": "INELIGIBLE",
              "executionEligibilityReason": "Structure non validée.",
              "proposedTradePlan": {
                "entryPrice": 3012.5, "stopLoss": 3005.0, "takeProfit": 3035.0,
                "rationale": "Plan proposé mais INELIGIBLE."
              }
            }
            """;
        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", rawJson));
        when(mentorAuditRepository.save(any())).thenAnswer(inv -> {
            MentorAudit a = inv.getArgument(0);
            a.setId(2L);
            return a;
        });

        service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "SHORT"}}
            """));

        ArgumentCaptor<MentorAudit> c = ArgumentCaptor.forClass(MentorAudit.class);
        verify(mentorAuditRepository).save(c.capture());
        assertThat(c.getValue().getSimulationStatus()).isNull();
    }

    @Test
    void analyze_eligibleButMissingSlOrTp_doesNotMarkPendingEntry() throws Exception {
        // Manual "Ask Mentor" style response — verdict is ELIGIBLE but no SL/TP.
        // Without the gate this row would be picked up by TradeSimulationService
        // and stay in PENDING_ENTRY forever.
        mentorProperties.setPersistAudits(true);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository, mentorProperties, objectMapper, new MentorParseMetrics(), simulationRepository);

        String rawJson = """
            {
              "verdict": "Trade Validé - Discipline Respectée",
              "executionEligibilityStatus": "ELIGIBLE",
              "executionEligibilityReason": "Validé en principe.",
              "proposedTradePlan": {
                "entryPrice": 3012.5,
                "rationale": "Plan partiel sans SL/TP."
              }
            }
            """;
        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", rawJson));
        when(mentorAuditRepository.save(any())).thenAnswer(inv -> {
            MentorAudit a = inv.getArgument(0);
            a.setId(3L);
            return a;
        });

        service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "LONG"}}
            """));

        ArgumentCaptor<MentorAudit> c = ArgumentCaptor.forClass(MentorAudit.class);
        verify(mentorAuditRepository).save(c.capture());
        assertThat(c.getValue().getSimulationStatus()).isNull();
    }

    @Test
    void analyze_buildsSemanticTextFromCurrentV2Keys() throws Exception {
        // Regression for the silent RAG bug: before the fix, the semantic text
        // read `market_structure_the_king` / `momentum_and_flow_the_trigger` —
        // keys the v2 payload writer no longer emits — so every embedding was
        // built from asset|action|"" |"" |"". The new builder reads v2 keys first.
        mentorProperties.setPersistAudits(true);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository, mentorProperties, objectMapper, new MentorParseMetrics(), simulationRepository);

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test",
                "{\"verdict\":\"Trade Validé - Discipline Respectée\"}"));
        when(mentorAuditRepository.save(any())).thenAnswer(inv -> {
            MentorAudit a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });

        service.analyze(objectMapper.readTree("""
            {
              "metadata": {"asset": "MGC1!", "market_session": "NEW_YORK"},
              "trade_intention": {"action": "LONG", "analysis_mode": "CONSERVATIVE"},
              "market_structure_smc": {"last_event": "BOS_UP"},
              "momentum_oscillators": {"money_flow_state": "BUYING"},
              "risk_and_emotional_check": {"reward_to_risk_ratio": "2.5"}
            }
            """));

        ArgumentCaptor<MentorAudit> captor = ArgumentCaptor.forClass(MentorAudit.class);
        verify(mentorAuditRepository).save(captor.capture());
        String semantic = captor.getValue().getSemanticText();
        // Before the fix BOS_UP and BUYING would be missing from the embedding text.
        assertThat(semantic).contains("BOS_UP").contains("BUYING");
    }

    @Test
    void analyze_buildsSemanticTextFromLegacyKeysAsFallback() throws Exception {
        // Historical audits stored under v1 keys must still produce a usable
        // embedding when re-indexed. The builder reads v2 first, v1 second.
        mentorProperties.setPersistAudits(true);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository, mentorProperties, objectMapper, new MentorParseMetrics(), simulationRepository);

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test",
                "{\"verdict\":\"Trade Validé - Discipline Respectée\"}"));
        when(mentorAuditRepository.save(any())).thenAnswer(inv -> {
            MentorAudit a = inv.getArgument(0);
            a.setId(2L);
            return a;
        });

        service.analyze(objectMapper.readTree("""
            {
              "metadata": {"asset": "MGC1!"},
              "trade_intention": {"action": "LONG"},
              "market_structure_the_king": {"last_event": "LEGACY_BOS"},
              "momentum_and_flow_the_trigger": {"money_flow_state": "LEGACY_BUYING"}
            }
            """));

        ArgumentCaptor<MentorAudit> captor = ArgumentCaptor.forClass(MentorAudit.class);
        verify(mentorAuditRepository).save(captor.capture());
        String semantic = captor.getValue().getSemanticText();
        assertThat(semantic).contains("LEGACY_BOS").contains("LEGACY_BUYING");
    }

    @Test
    void analyze_recoversPartialJsonWithTradePlanFields() throws Exception {
        // Truncated later in the response — we got strengths + part of the trade plan.
        mentorProperties.setPersistAudits(false);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient,
            mentorMemoryService,
            mentorAuditRepository,
            mentorProperties,
            objectMapper,
            new MentorParseMetrics(),
            simulationRepository
        );

        String truncated = """
            {
              "technicalQuickAnalysis": "Bullish setup with BOS confirmed.",
              "strengths": ["Respect de la structure", "Flow acheteur"],
              "verdict": "Trade Validé - Discipline Respectée",
              "executionEligibilityStatus": "ELIGIBLE",
              "executionEligibilityReason": "Plan conforme.",
              "proposedTradePlan": {
                "entryPrice": 3012.5,
                "stopLoss": 3005.0,
                "takeProfit": 3035.0,
                "rationale": "Entree sur pullback EMA
            """;

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", truncated));

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "LONG"}}
            """));

        assertThat(response.analysis().strengths()).containsExactly("Respect de la structure", "Flow acheteur");
        assertThat(response.analysis().proposedTradePlan()).isNotNull();
        assertThat(response.analysis().proposedTradePlan().entryPrice()).isEqualTo(3012.5);
        assertThat(response.analysis().proposedTradePlan().stopLoss()).isEqualTo(3005.0);
        assertThat(response.analysis().proposedTradePlan().takeProfit()).isEqualTo(3035.0);
        // MENTOR_UNAVAILABLE — the strict-schema promise was broken. Downstream
        // decides whether to promote to ELIGIBLE via the playbook fallback.
        assertThat(response.analysis().executionEligibilityStatus())
            .isEqualTo(ExecutionEligibilityStatus.MENTOR_UNAVAILABLE);
        assertThat(response.analysis().errors())
            .anySatisfy(e -> assertThat(e).contains("tronquée"));
    }

    // ── Truncated-but-validated: the "missed trade" diagnostic ──────────────

    @Test
    void analyze_recoveredResponseWithEligibleVerdict_countsAsMissedTrade() throws Exception {
        // Reproduces Opus audit #3460 — E6 1h SHORT where Gemini had written
        // "executionEligibilityStatus": "ELIGIBLE" in the raw text but the
        // response was truncated before the plan fields were serialized. The
        // system still lands on MENTOR_UNAVAILABLE (safe) but the diagnostics
        // counter must record it as a missed trade.
        mentorProperties.setPersistAudits(false);
        MentorParseMetrics metrics = new MentorParseMetrics();
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository,
            mentorProperties, objectMapper, metrics, simulationRepository);

        String truncatedWithEligible = "{"
            + "\"technicalQuickAnalysis\": \"Confluence macro baissière forte alignée avec CHoCH baissier.\","
            + "\"verdict\": \"Trade Validé - Discipline Respectée\","
            + "\"executionEligibilityStatus\": \"ELIGIBLE\","
            + "\"executionEligibilityReason\": \"";  // truncated mid-string

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", truncatedWithEligible));

        service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "6E1!"}, "trade_intention": {"action": "SHORT"}}
            """));

        MentorParseMetrics.Snapshot snap = metrics.snapshot();
        assertThat(snap.recovered()).isEqualTo(1);
        assertThat(snap.missedTrades())
            .as("truncated response carried an ELIGIBLE verdict — must be counted")
            .isEqualTo(1);
    }

    @Test
    void analyze_recoveredResponseWithoutEligibleVerdict_doesNotCountAsMissedTrade() throws Exception {
        // A truncated response that was never going to validate the trade
        // must NOT bump the missed-trade counter.
        mentorProperties.setPersistAudits(false);
        MentorParseMetrics metrics = new MentorParseMetrics();
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository,
            mentorProperties, objectMapper, metrics, simulationRepository);

        String truncatedIneligible = "{"
            + "\"technicalQuickAnalysis\": \"Setup invalidé — RSI overbought.\","
            + "\"verdict\": \"Trade Non-Conforme - Erreur de Processus\","
            + "\"executionEligibilityStatus\": \"INELIGIBLE\","
            + "\"executionEligibilityReason\": \"";  // truncated mid-string

        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", truncatedIneligible));

        service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MNQ1!"}, "trade_intention": {"action": "LONG"}}
            """));

        MentorParseMetrics.Snapshot snap = metrics.snapshot();
        assertThat(snap.recovered()).isEqualTo(1);
        assertThat(snap.missedTrades())
            .as("INELIGIBLE in raw text — no tradeable setup was dropped")
            .isZero();
    }

    @Test
    void wasValidatedBeforeTruncation_ignoresIneligibleSubstring() {
        // Guard against the trivial "INELIGIBLE" containing "ELIGIBLE".
        String raw = "{\"executionEligibilityStatus\": \"INELIGIBLE\", \"verdict\": \"Trade Non";
        assertThat(MentorAnalysisService.wasValidatedBeforeTruncation(raw)).isFalse();
    }

    @Test
    void wasValidatedBeforeTruncation_detectsTradeValideLiteral() {
        // The French literal verdict string is a second signal, useful when
        // the status field comes AFTER the verdict and is lost to truncation.
        String raw = "{\"verdict\": \"Trade Validé - Discipline Respectée\", \"executionEligibility";
        assertThat(MentorAnalysisService.wasValidatedBeforeTruncation(raw)).isTrue();
    }

    // ── Inverse bias hint (Opus audit #3496) ────────────────────────────────

    @Test
    void analyze_rejectedLongWithBearishErrors_populatesInverseShortHint() throws Exception {
        mentorProperties.setPersistAudits(false);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository,
            mentorProperties, objectMapper, new MentorParseMetrics(), simulationRepository);

        // Mirrors prod E6 1h WT LONG rejection — four bearish contradictions
        // that should surface a SHORT hint.
        String rawJson = """
            {
              "technicalQuickAnalysis": "Tendance H1 haussière mais prix en zone PREMIUM suite à CHoCH baissier.",
              "strengths": ["Tendance H1 globale haussière."],
              "errors": [
                "Divergence baissière détectée sur l'Order Flow réel (BEARISH_DIVERGENCE).",
                "Dernier événement structurel est un CHoCH baissier.",
                "Buy ratio faible (43.2%) indiquant un manque de pression acheteuse agressive.",
                "DXY haussier (0.137%) tiré par la faiblesse de l'EUR, ce qui est baissier pour 6E."
              ],
              "verdict": "Trade Non-Conforme - Erreur de Processus",
              "executionEligibilityStatus": "INELIGIBLE",
              "executionEligibilityReason": "Divergence baissière de l'Order Flow réel.",
              "improvementTip": "Attendre un retour sur le FVG haussier."
            }
            """;
        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", rawJson));

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "6E1!"}, "trade_intention": {"action": "LONG"}}
            """));

        assertThat(response.analysis().inverseBiasHint())
            .as("Four bearish contradictions must surface a SHORT hint")
            .isNotNull();
        assertThat(response.analysis().inverseBiasHint().direction()).isEqualTo("SHORT");
        assertThat(response.analysis().inverseBiasHint().supportingErrors()).hasSize(4);
        assertThat(response.analysis().inverseBiasHint().confidenceScore()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void analyze_eligibleTrade_doesNotProduceInverseHint() throws Exception {
        // Don't annotate validated trades — the inverse is implicitly not on
        // the table when Gemini agreed with the requested direction.
        mentorProperties.setPersistAudits(false);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository,
            mentorProperties, objectMapper, new MentorParseMetrics(), simulationRepository);

        String rawJson = """
            {
              "technicalQuickAnalysis": "Setup propre, flow acheteur confirmé.",
              "strengths": ["Flow acheteur", "Structure OK"],
              "errors": ["bearish divergence mineure", "CHoCH baissier ancien", "distribution"],
              "verdict": "Trade Validé - Discipline Respectée",
              "executionEligibilityStatus": "ELIGIBLE",
              "executionEligibilityReason": "Plan complet.",
              "proposedTradePlan": {
                "entryPrice": 3012.5, "stopLoss": 3005.0, "takeProfit": 3035.0,
                "rewardToRiskRatio": 3.0, "rationale": "Entry on pullback."
              }
            }
            """;
        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", rawJson));

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MGC1!"}, "trade_intention": {"action": "LONG"}}
            """));

        assertThat(response.analysis().executionEligibilityStatus()).isEqualTo(ExecutionEligibilityStatus.ELIGIBLE);
        assertThat(response.analysis().inverseBiasHint())
            .as("ELIGIBLE verdict — inverse hint suppressed")
            .isNull();
    }

    @Test
    void analyze_rejectedWithInsufficientContradictions_noInverseHint() throws Exception {
        mentorProperties.setPersistAudits(false);
        MentorAnalysisService service = new MentorAnalysisService(
            mentorModelClient, mentorMemoryService, mentorAuditRepository,
            mentorProperties, objectMapper, new MentorParseMetrics(), simulationRepository);

        String rawJson = """
            {
              "technicalQuickAnalysis": "RSI overbought — attendre pullback.",
              "strengths": ["Trend OK"],
              "errors": ["RSI overbought"],
              "verdict": "Trade Non-Conforme - Erreur de Processus",
              "executionEligibilityStatus": "INELIGIBLE",
              "executionEligibilityReason": "Attendre repli structurel.",
              "improvementTip": "Patience."
            }
            """;
        when(mentorMemoryService.findSimilar(any())).thenReturn(List.of());
        when(mentorModelClient.analyze(any(), any()))
            .thenReturn(new MentorModelClient.MentorModelResult("gemini-test", rawJson));

        MentorAnalyzeResponse response = service.analyze(objectMapper.readTree("""
            {"metadata": {"asset": "MNQ1!"}, "trade_intention": {"action": "LONG"}}
            """));

        assertThat(response.analysis().inverseBiasHint())
            .as("Only 1 contradiction — below threshold, no hint")
            .isNull();
    }
}

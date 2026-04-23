package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAlternativeEntry;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorInverseBiasHint;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.application.analysis.InverseBiasAnalyzer;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.simulation.ReviewType;
import com.riskdesk.domain.simulation.TradeSimulation;
import com.riskdesk.domain.simulation.port.TradeSimulationRepositoryPort;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class MentorAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MentorAnalysisService.class);
    private static final long SIMILAR_AUDITS_TIMEOUT_MS = 2500L;
    public static final String MANUAL_SOURCE_PREFIX = "manual-mentor:";
    public static final String ALERT_SOURCE_PREFIX = "alert-review:";

    private final MentorModelClient mentorModelClient;
    private final MentorMemoryService mentorMemoryService;
    private final MentorAuditRepositoryPort mentorAuditRepository;
    private final MentorProperties mentorProperties;
    private final ObjectMapper objectMapper;
    private final MentorParseMetrics parseMetrics;
    private final TradeSimulationRepositoryPort simulationRepository;

    public MentorAnalysisService(MentorModelClient mentorModelClient,
                                 MentorMemoryService mentorMemoryService,
                                 MentorAuditRepositoryPort mentorAuditRepository,
                                 MentorProperties mentorProperties,
                                 ObjectMapper objectMapper,
                                 MentorParseMetrics parseMetrics,
                                 TradeSimulationRepositoryPort simulationRepository) {
        this.mentorModelClient = mentorModelClient;
        this.mentorMemoryService = mentorMemoryService;
        this.mentorAuditRepository = mentorAuditRepository;
        this.mentorProperties = mentorProperties;
        this.objectMapper = objectMapper;
        this.parseMetrics = parseMetrics;
        this.simulationRepository = simulationRepository;
    }

    public MentorAnalyzeResponse analyze(JsonNode payload) {
        return analyze(payload, MANUAL_SOURCE_PREFIX + Instant.now().toEpochMilli());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MentorAnalyzeResponse analyze(JsonNode payload, String sourceRef) {
        List<MentorSimilarAudit> similarAudits = findSimilarAuditsBounded(payload);
        try {
            MentorModelClient.MentorModelResult raw = mentorModelClient.analyze(payload, similarAudits);
            MentorStructuredResponse parsed = parseStructuredResponse(raw.rawText());
            MentorStructuredResponse structured = attachInverseBiasHint(parsed, payload);
            Long auditId = mentorProperties.isPersistAudits() ? persistSuccess(payload, raw, structured, sourceRef) : null;
            return new MentorAnalyzeResponse(auditId, raw.model(), payload, structured, raw.rawText(), similarAudits);
        } catch (IllegalStateException e) {
            if (mentorProperties.isPersistAudits()) {
                persistFailure(payload, e, sourceRef);
            }
            throw e;
        }
    }

    /**
     * Attaches an inverse-bias hint to the structured response when Gemini
     * rejected the trade with enough contradictions pointing the other way.
     * The hint is advisory — never used to flip the eligibility state — and
     * is only computed for non-ELIGIBLE outcomes to keep noise down. Reads
     * the original action from {@code payload.trade_intention.action}; bails
     * out silently if the payload shape is unexpected.
     */
    private MentorStructuredResponse attachInverseBiasHint(MentorStructuredResponse base, JsonNode payload) {
        if (base == null || payload == null) return base;
        // Don't annotate successful trades — the inverse is implicitly not on
        // the table when Gemini validated the direction we asked about.
        if (base.executionEligibilityStatus() == ExecutionEligibilityStatus.ELIGIBLE) {
            return base;
        }
        JsonNode intention = payload.path("trade_intention");
        String action = intention.path("action").asText(null);
        MentorInverseBiasHint hint = InverseBiasAnalyzer.analyze(action, base.errors());
        if (hint == null) return base;
        log.info("Inverse bias hint attached: rejected={} → suggested={} (score={})",
            action, hint.direction(), String.format("%.2f", hint.confidenceScore()));
        return new MentorStructuredResponse(
            base.technicalQuickAnalysis(),
            base.strengths(),
            base.errors(),
            base.verdict(),
            base.executionEligibilityStatus(),
            base.executionEligibilityReason(),
            base.improvementTip(),
            base.proposedTradePlan(),
            hint
        );
    }

    private List<MentorSimilarAudit> findSimilarAuditsBounded(JsonNode payload) {
        try {
            return CompletableFuture
                .supplyAsync(() -> mentorMemoryService.findSimilar(payload))
                .completeOnTimeout(List.of(), SIMILAR_AUDITS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .join();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private MentorStructuredResponse parseStructuredResponse(String rawText) {
        try {
            MentorStructuredResponse strict = objectMapper.readValue(rawText, MentorStructuredResponse.class);
            parseMetrics.recordSuccess();
            return strict;
        } catch (Exception strictFailure) {
            MentorStructuredResponse recovered = tryRecoverPartialResponse(rawText);
            if (recovered != null) {
                log.warn("Mentor response recovered from partial JSON (original length={} chars)", rawText == null ? 0 : rawText.length());
                parseMetrics.recordRecovered();
                if (wasValidatedBeforeTruncation(rawText)) {
                    // Gemini had already committed to ELIGIBLE / Trade Validé in
                    // the raw text, but the plan was truncated away. The
                    // persisted review will still land on MENTOR_UNAVAILABLE
                    // (we won't synthesize an entry/SL/TP from thin air) —
                    // surface it as a "missed trade" on the diagnostics
                    // endpoint so operators can see how much tradeable signal
                    // we're dropping.
                    parseMetrics.recordTruncatedButValidated();
                    log.warn("Gemini response truncated after ELIGIBLE verdict — missed tradeable setup");
                }
                return recovered;
            }
            parseMetrics.recordFailure();
            // Parse failure: Gemini did not return a consumable JSON. This is a
            // TECHNICAL failure, not a trade rejection. Use MENTOR_UNAVAILABLE
            // so the UI can surface it as "retry needed" instead of masking it
            // as a real rejection. Prod audit showed ~90% of SIGNAL reviews
            // landed here because of thinking-budget-induced truncation.
            return new MentorStructuredResponse(
                rawText,
                List.of(),
                List.of("La réponse du modèle n'était pas un JSON strictement exploitable."),
                "Mentor Indisponible - Réponse Incomplète",
                ExecutionEligibilityStatus.MENTOR_UNAVAILABLE,
                "Structured mentor response unavailable.",
                "Rends la sortie du mentor plus structurée avant de l'utiliser en décision.",
                null
            );
        }
    }

    /**
     * Tries to salvage a truncated Gemini response (e.g. cut off mid-string because of
     * maxOutputTokens). We repair the JSON envelope, then extract whichever fields are
     * present. Recovered responses stay INELIGIBLE by default — promotion to ELIGIBLE
     * happens downstream via the playbook fallback circuit when warranted.
     */
    private MentorStructuredResponse tryRecoverPartialResponse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String repaired = repairTruncatedJson(rawText);
        if (repaired == null) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(repaired);
        } catch (Exception ignored) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }

        String technical = textOrNull(node, "technicalQuickAnalysis");
        List<String> strengths = readStringList(node, "strengths");
        List<String> errors = new ArrayList<>(readStringList(node, "errors"));
        errors.add("Réponse du mentor tronquée — reconstruction partielle appliquée.");
        String verdict = textOrNull(node, "verdict");
        if (verdict == null || verdict.isBlank()) {
            verdict = "Trade Non-Conforme - Analyse Partielle";
        }
        String reason = textOrNull(node, "executionEligibilityReason");
        if (reason == null || reason.isBlank()) {
            reason = "Gemini response truncated; recovered fields only.";
        }
        String improvement = textOrNull(node, "improvementTip");
        if (improvement == null || improvement.isBlank()) {
            improvement = "Augmente la robustesse de la sortie structurée du mentor.";
        }
        // Recovered responses are kept MENTOR_UNAVAILABLE here on purpose:
        // we cannot trust a Gemini verdict that was reconstructed from a
        // truncated JSON (the closing fields may have flipped it). The UI
        // surfaces this distinctly from a real INELIGIBLE. The downstream
        // playbook fallback (MentorSignalReviewService) may still override
        // to ELIGIBLE when the mechanical plan + checklist score justify it.
        ExecutionEligibilityStatus eligibility = ExecutionEligibilityStatus.MENTOR_UNAVAILABLE;
        return new MentorStructuredResponse(
            technical == null ? "" : technical,
            strengths,
            errors,
            verdict,
            eligibility,
            reason,
            improvement,
            parseProposedPlan(node.get("proposedTradePlan"))
        );
    }

    /**
     * Detects whether a truncated Gemini response carried an explicit positive
     * verdict before it was cut off. We look for either the ELIGIBLE status
     * token or the "Trade Validé" verdict string — both are unambiguous
     * signals that, had the plan not been truncated, the system would have
     * armed the trade.
     *
     * <p>Kept deliberately substring-based: the text may be mid-word or
     * mid-escape when truncated, so we don't require well-formed JSON. False
     * positives (the model writing "ELIGIBLE" in a rejection rationale) are
     * rare because the schema-guided prompt makes those tokens keys, not
     * free-text content.
     */
    static boolean wasValidatedBeforeTruncation(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return false;
        }
        String text = rawText;
        // Guard against the trivial "INELIGIBLE" containing "ELIGIBLE": match
        // the status field specifically, tolerating whitespace variations.
        boolean eligible = text.contains("\"executionEligibilityStatus\"")
            && text.contains("\"ELIGIBLE\"")
            && !containsJsonString(text, "executionEligibilityStatus", "INELIGIBLE");
        boolean tradeValide = text.contains("Trade Validé");
        return eligible || tradeValide;
    }

    private static boolean containsJsonString(String text, String key, String value) {
        // Looks for the "key": "value" pattern with optional whitespace.
        String needle = "\"" + key + "\"";
        int idx = text.indexOf(needle);
        if (idx < 0) return false;
        int colon = text.indexOf(':', idx + needle.length());
        if (colon < 0) return false;
        int valueStart = text.indexOf('"', colon);
        if (valueStart < 0) return false;
        int valueEnd = text.indexOf('"', valueStart + 1);
        if (valueEnd < 0) return false;
        return value.equals(text.substring(valueStart + 1, valueEnd));
    }

    private static String repairTruncatedJson(String raw) {
        String text = raw.trim();
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        text = text.substring(start);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        boolean inString = false;
        boolean escape = false;
        int braces = 0;
        int brackets = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') braces++;
            else if (c == '}') braces--;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
        }
        if (inString) {
            sb.append('"');
        }
        // Strip dangling separators (trailing "," or ":" with nothing after)
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last == ',' || last == ':') {
                sb.deleteCharAt(sb.length() - 1);
            } else {
                break;
            }
        }
        for (int i = 0; i < brackets; i++) {
            sb.append(']');
        }
        for (int i = 0; i < braces; i++) {
            sb.append('}');
        }
        return sb.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        return child.isTextual() ? child.asText() : child.toString();
    }

    private static List<String> readStringList(JsonNode node, String field) {
        if (node == null) return List.of();
        JsonNode child = node.get(field);
        if (child == null || !child.isArray()) return List.of();
        List<String> out = new ArrayList<>(child.size());
        for (Iterator<JsonNode> it = child.elements(); it.hasNext();) {
            JsonNode el = it.next();
            if (el != null && !el.isNull()) {
                out.add(el.isTextual() ? el.asText() : el.toString());
            }
        }
        return out;
    }

    private static MentorProposedTradePlan parseProposedPlan(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        Double entry = numberOrNull(node, "entryPrice");
        Double sl = numberOrNull(node, "stopLoss");
        Double tp = numberOrNull(node, "takeProfit");
        Double rr = numberOrNull(node, "rewardToRiskRatio");
        String rationale = textOrNull(node, "rationale");
        String tpSource = textOrNull(node, "tpSource");
        MentorAlternativeEntry safeDeep = null;
        JsonNode safeDeepNode = node.get("safeDeepEntry");
        if (safeDeepNode != null && safeDeepNode.isObject()) {
            safeDeep = new MentorAlternativeEntry(
                numberOrNull(safeDeepNode, "entryPrice"),
                textOrNull(safeDeepNode, "rationale")
            );
        }
        if (entry == null && sl == null && tp == null && rr == null && rationale == null && safeDeep == null) {
            return null;
        }
        return new MentorProposedTradePlan(entry, sl, tp, rr, rationale, safeDeep, tpSource);
    }

    private static Double numberOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        if (child.isNumber()) return child.doubleValue();
        if (child.isTextual()) {
            try {
                return Double.parseDouble(child.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns {@code true} only when the structured response has a complete,
     * tradable plan (non-null entry, SL, TP) AND Gemini flagged the setup
     * ELIGIBLE for execution.
     *
     * <p>This is the gate for initial {@code simulationStatus = PENDING_ENTRY}
     * on {@link MentorAudit}: previously every successful audit (including
     * manual Ask-Mentor calls with no trade plan and explicitly INELIGIBLE
     * verdicts) was enqueued into {@code TradeSimulationService}, producing
     * "stuck" rows the simulation poller could never resolve. Gating here
     * keeps the queue clean without widening the Simulation Decoupling Rule
     * surface (no new fields on MentorAudit).
     */
    private boolean isSimulationCandidate(MentorStructuredResponse structured) {
        if (structured == null) return false;
        if (structured.executionEligibilityStatus() != ExecutionEligibilityStatus.ELIGIBLE) return false;
        MentorProposedTradePlan plan = structured.proposedTradePlan();
        return plan != null
            && plan.entryPrice() != null
            && plan.stopLoss() != null
            && plan.takeProfit() != null;
    }

    private Long persistSuccess(JsonNode payload,
                                MentorModelClient.MentorModelResult raw,
                                MentorStructuredResponse structured,
                                String sourceRef) {
        try {
            MentorAudit audit = new MentorAudit();
            audit.setSourceRef(sourceRef);
            audit.setCreatedAt(Instant.now());
            audit.setSelectedTimezone(extractSelectedTimezone(payload));
            audit.setInstrument(payload.path("metadata").path("asset").asText(null));
            audit.setTimeframe(payload.path("metadata").path("timeframe_focus").asText(null));
            audit.setAction(payload.path("trade_intention").path("action").asText(null));
            audit.setModel(raw.model());
            audit.setPayloadJson(objectMapper.writeValueAsString(payload));
            audit.setResponseJson(raw.rawText());
            audit.setVerdict(structured.verdict());
            audit.setSuccess(true);
            audit.setSemanticText(buildSemanticText(payload, structured));
            // Phase 3: simulation state is no longer stamped on the audit.
            // Save the audit first so we have an id, then — only when the
            // verdict is actually executable — create a matching row in the
            // trade_simulations aggregate.
            MentorAudit saved = mentorAuditRepository.save(audit);
            if (isSimulationCandidate(structured) && saved.getId() != null) {
                initializeAuditSimulation(saved);
            }
            CompletableFuture.runAsync(() -> mentorMemoryService.indexAudit(saved))
                .exceptionally(ex -> {
                    log.error("Async audit indexing failed for audit {}", saved.getId(), ex);
                    return null;
                });
            return saved.getId();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Phase 3: creates a {@code TradeSimulation(PENDING_ENTRY, AUDIT)} row for
     * manual "Ask Mentor" audits that resolved to an ELIGIBLE verdict with a
     * complete trade plan. Idempotent — if a row already exists for this
     * (auditId, AUDIT) pair it is left alone.
     */
    private void initializeAuditSimulation(MentorAudit saved) {
        try {
            if (simulationRepository.findByReviewId(saved.getId(), ReviewType.AUDIT).isPresent()) {
                return;
            }
            String instrument = saved.getInstrument() != null ? saved.getInstrument() : "UNKNOWN";
            String action = saved.getAction() != null ? saved.getAction() : "LONG";
            Instant createdAt = saved.getCreatedAt() != null ? saved.getCreatedAt() : Instant.now();
            simulationRepository.save(new TradeSimulation(
                null,
                saved.getId(),
                ReviewType.AUDIT,
                instrument,
                action,
                TradeSimulationStatus.PENDING_ENTRY,
                null, null, null, null, null, null,
                createdAt
            ));
        } catch (Exception e) {
            log.warn("Failed to initialize AUDIT simulation aggregate for audit {}: {}",
                saved.getId(), e.getMessage());
        }
    }

    private void persistFailure(JsonNode payload, IllegalStateException e, String sourceRef) {
        try {
            MentorAudit audit = new MentorAudit();
            audit.setSourceRef(sourceRef);
            audit.setCreatedAt(Instant.now());
            audit.setSelectedTimezone(extractSelectedTimezone(payload));
            audit.setInstrument(payload.path("metadata").path("asset").asText(null));
            audit.setTimeframe(payload.path("metadata").path("timeframe_focus").asText(null));
            audit.setAction(payload.path("trade_intention").path("action").asText(null));
            audit.setModel(mentorProperties.getModel());
            audit.setPayloadJson(objectMapper.writeValueAsString(payload));
            audit.setResponseJson("");
            audit.setVerdict("Mentor unavailable");
            audit.setSuccess(false);
            audit.setErrorMessage(e.getMessage());
            audit.setSemanticText(buildSemanticText(payload, null));
            mentorAuditRepository.save(audit);
        } catch (Exception ignored) {
            // best effort only
        }
    }

    private String buildSemanticText(JsonNode payload, MentorStructuredResponse structured) {
        // Mentor IA v2 rename moved `market_structure_the_king` → `market_structure_smc`
        // and `momentum_and_flow_the_trigger` → `momentum_oscillators`.
        // Read the current keys first; fall back to the legacy keys so older audit
        // rows (pre-rename) still produce a non-empty embedding when re-indexed.
        return String.join(" | ",
            valueOrEmpty(payload.path("metadata").path("asset").asText(null)),
            valueOrEmpty(payload.path("trade_intention").path("action").asText(null)),
            valueOrEmpty(payload.path("trade_intention").path("analysis_mode").asText(null)),
            valueOrEmpty(payload.path("metadata").path("market_session").asText(null)),
            firstNonEmpty(
                payload.path("market_structure_smc").path("last_event").asText(null),
                payload.path("market_structure_the_king").path("last_event").asText(null)
            ),
            firstNonEmpty(
                payload.path("momentum_oscillators").path("money_flow_state").asText(null),
                payload.path("momentum_and_flow_the_trigger").path("money_flow_state").asText(null)
            ),
            valueOrEmpty(payload.path("risk_and_emotional_check").path("reward_to_risk_ratio").asText(null)),
            valueOrEmpty(structured == null ? null : structured.technicalQuickAnalysis()),
            valueOrEmpty(structured == null ? null : structured.verdict()),
            valueOrEmpty(structured == null ? null : structured.improvementTip())
        );
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Returns the first non-null, non-blank value — or empty string if both are missing.
     * Used to bridge the v1 → v2 payload-key rename so semantic embeddings remain
     * populated regardless of which schema version stored the audit.
     */
    private String firstNonEmpty(String primary, String legacy) {
        if (primary != null && !primary.isBlank()) return primary;
        if (legacy != null && !legacy.isBlank()) return legacy;
        return "";
    }

    private String extractSelectedTimezone(JsonNode payload) {
        return normalizeSelectedTimezone(payload.path("metadata").path("selected_timezone").asText(null));
    }

    private String normalizeSelectedTimezone(String selectedTimezone) {
        if (selectedTimezone == null || selectedTimezone.isBlank()) {
            return "UTC";
        }
        try {
            return ZoneId.of(selectedTimezone).getId();
        } catch (Exception ignored) {
            return "UTC";
        }
    }
}

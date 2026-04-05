package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
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

    public MentorAnalysisService(MentorModelClient mentorModelClient,
                                 MentorMemoryService mentorMemoryService,
                                 MentorAuditRepositoryPort mentorAuditRepository,
                                 MentorProperties mentorProperties,
                                 ObjectMapper objectMapper) {
        this.mentorModelClient = mentorModelClient;
        this.mentorMemoryService = mentorMemoryService;
        this.mentorAuditRepository = mentorAuditRepository;
        this.mentorProperties = mentorProperties;
        this.objectMapper = objectMapper;
    }

    public MentorAnalyzeResponse analyze(JsonNode payload) {
        return analyze(payload, MANUAL_SOURCE_PREFIX + Instant.now().toEpochMilli());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MentorAnalyzeResponse analyze(JsonNode payload, String sourceRef) {
        List<MentorSimilarAudit> similarAudits = findSimilarAuditsBounded(payload);
        try {
            MentorModelClient.MentorModelResult raw = mentorModelClient.analyze(payload, similarAudits);
            MentorStructuredResponse structured = parseStructuredResponse(raw.rawText());
            Long auditId = mentorProperties.isPersistAudits() ? persistSuccess(payload, raw, structured, sourceRef) : null;
            return new MentorAnalyzeResponse(auditId, raw.model(), payload, structured, raw.rawText(), similarAudits);
        } catch (IllegalStateException e) {
            if (mentorProperties.isPersistAudits()) {
                persistFailure(payload, e, sourceRef);
            }
            throw e;
        }
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
            return objectMapper.readValue(rawText, MentorStructuredResponse.class);
        } catch (Exception ignored) {
            return new MentorStructuredResponse(
                rawText,
                List.of(),
                List.of("La réponse du modèle n'était pas un JSON strictement exploitable."),
                "Trade Non-Conforme - Erreur de Processus",
                ExecutionEligibilityStatus.INELIGIBLE,
                "Structured mentor response unavailable.",
                "Rends la sortie du mentor plus structurée avant de l'utiliser en décision.",
                null
            );
        }
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
            audit.setSimulationStatus(TradeSimulationStatus.PENDING_ENTRY);
            MentorAudit saved = mentorAuditRepository.save(audit);
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
        return String.join(" | ",
            valueOrEmpty(payload.path("metadata").path("asset").asText(null)),
            valueOrEmpty(payload.path("trade_intention").path("action").asText(null)),
            valueOrEmpty(payload.path("trade_intention").path("analysis_mode").asText(null)),
            valueOrEmpty(payload.path("metadata").path("market_session").asText(null)),
            valueOrEmpty(payload.path("market_structure_the_king").path("last_event").asText(null)),
            valueOrEmpty(payload.path("momentum_and_flow_the_trigger").path("money_flow_state").asText(null)),
            valueOrEmpty(payload.path("risk_and_emotional_check").path("reward_to_risk_ratio").asText(null)),
            valueOrEmpty(structured == null ? null : structured.technicalQuickAnalysis()),
            valueOrEmpty(structured == null ? null : structured.verdict()),
            valueOrEmpty(structured == null ? null : structured.improvementTip())
        );
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
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

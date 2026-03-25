package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.infrastructure.config.MentorProperties;
import com.riskdesk.presentation.dto.MentorAnalyzeResponse;
import com.riskdesk.presentation.dto.MentorProposedTradePlan;
import com.riskdesk.presentation.dto.MentorSimilarAudit;
import com.riskdesk.presentation.dto.MentorStructuredResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class MentorAnalysisService {

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
        List<MentorSimilarAudit> similarAudits = mentorMemoryService.findSimilar(payload);
        try {
            MentorModelClient.MentorModelResult raw = mentorModelClient.analyze(payload, similarAudits);
            MentorStructuredResponse structured = parseStructuredResponse(raw.rawText());
            Long auditId = mentorProperties.isPersistAudits() ? persistSuccess(payload, raw, structured) : null;
            return new MentorAnalyzeResponse(auditId, raw.model(), payload, structured, raw.rawText(), similarAudits);
        } catch (IllegalStateException e) {
            if (mentorProperties.isPersistAudits()) {
                persistFailure(payload, e);
            }
            throw e;
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
                "Rends la sortie du mentor plus structurée avant de l'utiliser en décision.",
                null
            );
        }
    }

    private Long persistSuccess(JsonNode payload,
                                MentorModelClient.MentorModelResult raw,
                                MentorStructuredResponse structured) {
        try {
            MentorAudit audit = new MentorAudit();
            audit.setCreatedAt(Instant.now());
            audit.setInstrument(payload.path("metadata").path("asset").asText(null));
            audit.setTimeframe(payload.path("metadata").path("timeframe_focus").asText(null));
            audit.setAction(payload.path("trade_intention").path("action").asText(null));
            audit.setModel(raw.model());
            audit.setPayloadJson(objectMapper.writeValueAsString(payload));
            audit.setResponseJson(raw.rawText());
            audit.setVerdict(structured.verdict());
            audit.setSuccess(true);
            audit.setSemanticText(buildSemanticText(payload, structured));
            MentorAudit saved = mentorAuditRepository.save(audit);
            mentorMemoryService.indexAudit(saved);
            return saved.getId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void persistFailure(JsonNode payload, IllegalStateException e) {
        try {
            MentorAudit audit = new MentorAudit();
            audit.setCreatedAt(Instant.now());
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
}

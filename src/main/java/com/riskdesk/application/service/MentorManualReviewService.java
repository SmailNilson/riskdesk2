package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorManualReview;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.MentorAudit;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MentorManualReviewService {

    private static final int DEFAULT_LIMIT = 30;

    private final MentorAuditRepositoryPort mentorAuditRepository;
    private final ObjectMapper objectMapper;

    public MentorManualReviewService(MentorAuditRepositoryPort mentorAuditRepository,
                                     ObjectMapper objectMapper) {
        this.mentorAuditRepository = mentorAuditRepository;
        this.objectMapper = objectMapper;
    }

    public List<MentorManualReview> getRecentManualReviews() {
        return mentorAuditRepository.findRecentBySourceRefPrefix(MentorAnalysisService.MANUAL_SOURCE_PREFIX, DEFAULT_LIMIT).stream()
            .map(this::toDto)
            .toList();
    }

    private MentorManualReview toDto(MentorAudit audit) {
        JsonNode payload = parseJson(audit.getPayloadJson());
        MentorStructuredResponse structured = parseStructured(audit.getResponseJson());
        MentorAnalyzeResponse response = new MentorAnalyzeResponse(
            audit.getId(),
            audit.getModel(),
            payload,
            structured,
            audit.getResponseJson(),
            List.of()
        );
        return new MentorManualReview(
            audit.getId(),
            "MANUAL_MENTOR",
            audit.getCreatedAt() == null ? null : audit.getCreatedAt().toString(),
            audit.getInstrument(),
            audit.getTimeframe(),
            audit.getAction(),
            audit.getModel(),
            audit.getVerdict(),
            audit.isSuccess(),
            audit.getErrorMessage(),
            response,
            audit.getSimulationStatus(),
            audit.getActivationTime() == null ? null : audit.getActivationTime().toString(),
            audit.getResolutionTime() == null ? null : audit.getResolutionTime().toString(),
            audit.getMaxDrawdownPoints() == null ? null : audit.getMaxDrawdownPoints().doubleValue()
        );
    }

    private JsonNode parseJson(String rawJson) {
        try {
            return rawJson == null || rawJson.isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(rawJson);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private MentorStructuredResponse parseStructured(String rawResponse) {
        try {
            return rawResponse == null || rawResponse.isBlank()
                ? null
                : objectMapper.readValue(rawResponse, MentorStructuredResponse.class);
        } catch (Exception ignored) {
            return new MentorStructuredResponse(
                rawResponse == null ? "" : rawResponse,
                List.of(),
                List.of("La réponse sauvegardée n'était pas un JSON strictement exploitable."),
                "Trade Non-Conforme - Erreur de Processus",
                "Relance une analyse manuelle si tu veux une version plus structurée.",
                null
            );
        }
    }
}

package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorManualReview;
import com.riskdesk.application.dto.MentorSimilarAudit;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.MentorAudit;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MentorManualReviewService {

    private static final int DEFAULT_LIMIT = 30;

    private final MentorAuditRepositoryPort mentorAuditRepository;
    private final MentorMemoryService mentorMemoryService;
    private final ObjectMapper objectMapper;

    public MentorManualReviewService(MentorAuditRepositoryPort mentorAuditRepository,
                                     MentorMemoryService mentorMemoryService,
                                     ObjectMapper objectMapper) {
        this.mentorAuditRepository = mentorAuditRepository;
        this.mentorMemoryService = mentorMemoryService;
        this.objectMapper = objectMapper;
    }

    public List<MentorManualReview> getRecentManualReviews() {
        return mentorAuditRepository.findRecentBySourceRefPrefix(MentorAnalysisService.MANUAL_SOURCE_PREFIX, DEFAULT_LIMIT).stream()
            .map(this::toDto)
            .toList();
    }

    private MentorManualReview toDto(MentorAudit audit) {
        JsonNode payload = parseJson(audit.getPayloadJson());
        ContractMetadata contractMetadata = extractContractMetadata(payload);
        MentorStructuredResponse structured = parseStructured(audit.getResponseJson());
        List<MentorSimilarAudit> similarAudits = mentorMemoryService.findSimilar(payload).stream()
            .filter(match -> !match.auditId().equals(audit.getId()))
            .toList();
        MentorAnalyzeResponse response = new MentorAnalyzeResponse(
            audit.getId(),
            audit.getModel(),
            payload,
            structured,
            audit.getResponseJson(),
            similarAudits
        );
        return new MentorManualReview(
            audit.getId(),
            "MANUAL_MENTOR",
            audit.getCreatedAt() == null ? null : audit.getCreatedAt().toString(),
            audit.getInstrument(),
            contractMetadata.asset(),
            contractMetadata.contractMonth(),
            contractMetadata.contractSymbol(),
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

    private ContractMetadata extractContractMetadata(JsonNode payload) {
        JsonNode metadata = payload.path("metadata");
        return new ContractMetadata(
            textOrNull(metadata, "asset"),
            textOrNull(metadata, "active_contract_month"),
            textOrNull(metadata, "active_contract_symbol")
        );
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
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

    private record ContractMetadata(
        String asset,
        String contractMonth,
        String contractSymbol
    ) {
    }
}

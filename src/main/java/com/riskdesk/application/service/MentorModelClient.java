package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskdesk.application.dto.MentorSimilarAudit;

import java.util.List;

public interface MentorModelClient {
    MentorModelResult analyze(JsonNode payload, List<MentorSimilarAudit> similarAudits);

    record MentorModelResult(
        String model,
        String rawText
    ) {}
}

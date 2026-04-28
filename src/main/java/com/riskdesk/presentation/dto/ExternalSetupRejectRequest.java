package com.riskdesk.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalSetupRejectRequest(
    @Size(max = 512) String reason,
    String rejectedBy
) {
}

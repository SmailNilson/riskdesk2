package com.riskdesk.application.dto;

public record PlaybookAutomationUpdateRequest(
    Integer paperThreshold,
    Integer liveThreshold,
    Boolean paperEnabled,
    Boolean autoIbkrEnabled,
    Integer quantity,
    String brokerAccountId
) {
}

package com.riskdesk.application.dto;

public record IbkrAccountView(
    String id,
    String displayName,
    String currency,
    boolean selected
) {
}

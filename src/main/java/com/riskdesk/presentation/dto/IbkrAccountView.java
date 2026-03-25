package com.riskdesk.presentation.dto;

public record IbkrAccountView(
    String id,
    String displayName,
    String currency,
    boolean selected
) {}
